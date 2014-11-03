package com.linkedin.venice.kafka.consumer;

import com.linkedin.venice.server.VeniceConfig;
import com.linkedin.venice.serialization.VeniceMessageSerializer;
import com.linkedin.venice.storage.VeniceMessageException;
import com.linkedin.venice.storage.VeniceStorageException;
import com.linkedin.venice.storage.VeniceStorageNode;
import com.linkedin.venice.message.VeniceMessage;

import org.apache.log4j.Logger;

import kafka.api.FetchRequest;
import kafka.api.FetchRequestBuilder;
import kafka.api.FetchResponse;
import kafka.api.PartitionMetadata;
import kafka.api.PartitionOffsetRequestInfo;
import kafka.api.TopicMetadata;
import kafka.api.TopicMetadataRequest;
import kafka.api.TopicMetadataResponse;
import kafka.cluster.Broker;
import kafka.common.KafkaException;
import kafka.common.TopicAndPartition;
import kafka.consumer.SimpleConsumer;
import kafka.message.Message;
import kafka.message.MessageAndOffset;
import kafka.utils.VerifiableProperties;
import scala.collection.Iterator;
import scala.collection.JavaConversions;
import scala.collection.Seq;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;


/**
 * Runnable class which performs Kafka consumption from the Simple Consumer API.
 * Consumption is performed on a map of <topic, partitionids>
 */
public class SimpleKafkaConsumerTask implements Runnable {

  private static final Logger logger = Logger.getLogger(SimpleKafkaConsumerTask.class.getName());
  private final String ENCODING = "UTF-8";

  // Venice Serialization
  private VeniceMessage vm;
  private static VeniceMessageSerializer messageSerializer;

  // Seed kafka brokers
  private List<String> seedBrokers;
  // port for seed brokers
  private int port;
  // SimpleConsumer fetch buffer size.
  private final int fetchBufferSize;
  // SimpleConsumer socket socketTimeoutMs.
  private final int socketTimeoutMs;
  // Number of times the SimpleConsumer will retry fetching topic-partition leadership metadata.
  private final int numMetadataRefreshRetries;
  //Back off duration between metadata fetch retries.
  private int metadataRefreshBackoffMs;

  // Topic-partitions that will be consumed.
  private Map<String, Collection<Integer>> topicPartitions;
  // Replica kafka brokers
  private List<String> replicaBrokers;
  // storage destination for consumption
  private VeniceStorageNode node;

  private String topic;
  private int partition;

  public SimpleKafkaConsumerTask(SimpleKafkaConsumerConfig config, VeniceStorageNode node, String topic, int partition,
      int port) {

    this.seedBrokers = config.getSeedBrokers();
    this.fetchBufferSize = config.getFetchBufferSize();
    this.socketTimeoutMs = config.getSocketTimeoutMs();
    this.numMetadataRefreshRetries = config.getNumMetadataRefreshRetries();
    this.metadataRefreshBackoffMs = config.getMetadataRefreshBackoffMs();
    this.node = node;

    messageSerializer = new VeniceMessageSerializer(new VerifiableProperties());
    this.replicaBrokers = new ArrayList<String>();
    this.topic = topic;
    this.partition = partition;
  }

  /**
   *  Parallelized method which performs Kafka consumption and relays messages to the Storage Node
   * */
  public void run() {

    // find the meta data
    PartitionMetadata metadata = findLeader(seedBrokers, port, topic, partition);

    if (null == metadata) {
      logger.error("Cannot find metadata for Topic: " + topic + " Partition: " + partition);
      return;
    }

    if (null == metadata.leader()) {
      logger.error("Cannot find leader for Topic: " + topic + " Partition: " + partition);
      return;
    }

    String leadBroker = metadata.leader().get().host();
    String clientName = "Client_" + topic + "_" + partition;

    SimpleConsumer consumer = new SimpleConsumer(leadBroker, port, socketTimeoutMs, fetchBufferSize, clientName);
    long readOffset = getLastOffset(consumer, topic, partition, kafka.api.OffsetRequest.LatestTime(), clientName);

    int numErrors = 0;
    boolean execute = true;

    while (execute) {

      FetchRequest req =
          new FetchRequestBuilder().clientId(clientName).addFetch(topic, partition, readOffset, fetchBufferSize)
              .build();

      FetchResponse fetchResponse = consumer.fetch(req);

      if (fetchResponse.hasError()) {

        logger.error("Kafka error found! Skipping....");
        logger.error("Message: " + fetchResponse.errorCode(topic, partition));

        consumer.close();
        consumer = null;

        try {
          leadBroker = findNewLeader(leadBroker, topic, partition, port);
        } catch (Exception e) {
          logger.error("Error while finding new leader: " + e);
        }

        continue;
      }

      long numReads = 0;

      Iterator<MessageAndOffset> messageAndOffsetIterator = fetchResponse.messageSet(topic, partition).iterator();

      while (messageAndOffsetIterator.hasNext()) {

        MessageAndOffset messageAndOffset = messageAndOffsetIterator.next();
        long currentOffset = messageAndOffset.offset();

        if (currentOffset < readOffset) {
          logger.error("Found an old offset: " + currentOffset + " Expecting: " + readOffset);
          continue;
        }

        readOffset = messageAndOffset.nextOffset();

        try {

          Message msg = messageAndOffset.message();
          String keyString;

          // Get the Venice Key
          ByteBuffer key = msg.key();
          byte[] keyBytes = new byte[key.limit()];
          key.get(keyBytes);
          keyString = new String(keyBytes, ENCODING);

          // Read Payload
          ByteBuffer payload = messageAndOffset.message().payload();
          byte[] payloadBytes = new byte[payload.limit()];
          payload.get(payloadBytes);

          // De-serialize payload into Venice Message format
          vm = messageSerializer.fromBytes(payloadBytes);

          readMessage(keyString, vm);

          numReads++;
        } catch (UnsupportedEncodingException e) {

          logger.error("Encoding is not supported: " + ENCODING);
          logger.error(e);

          // end the thread
          execute = false;
        } catch (VeniceMessageException e) {

          logger.error("Received an illegal Venice message!");
          logger.error(e);
          e.printStackTrace();
        } catch (VeniceStorageException e) {

          logger.error("Venice Storage Node with nodeId " + node.getNodeId() + " has been corrupted! Exiting...");
          logger.error(e);
          e.printStackTrace();

          // end the thread
          execute = false;
        }
      }

      if (0 == numReads) {

        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
        }
      }
    }

    if (consumer != null) {
      logger.error("Closing consumer..");
      consumer.close();
    }
  }

  /**
   * Given the attached Node, interpret the VeniceMessage and perform the required action
   * */
  private void readMessage(String key, VeniceMessage msg)
      throws VeniceMessageException, VeniceStorageException {

    // check for invalid inputs
    if (null == msg) {
      throw new VeniceMessageException("Given null Venice Message.");
    }

    if (null == msg.getOperationType()) {
      throw new VeniceMessageException("Venice Message does not have operation type!");
    }

    switch (msg.getOperationType()) {

      // adding new values
      case PUT:
        logger.info("Putting: " + key + ", " + msg.getPayload());
        node.put(partition, key, msg.getPayload());
        break;

      // deleting values
      case DELETE:
        logger.info("Deleting: " + key);
        node.delete(partition, key);
        break;

      // partial update
      case PARTIAL_PUT:
        throw new UnsupportedOperationException("Partial puts not yet implemented");

        // error
      default:
        throw new VeniceMessageException("Invalid operation type submitted: " + msg.getOperationType());
    }
  }

  /**
   * Finds the latest offset after a given time
   * @param consumer - A SimpleConsumer object for Kafka consumption
   * @param topic - Kafka topic
   * @param partition - Partition number within the topic
   * @param whichTime - Time at which to being reading offsets
   * @param clientName - Name of the client (combination of topic + partition)
   * @return long - last offset after the given time
   * */
  public static long getLastOffset(SimpleConsumer consumer, String topic, int partition, long whichTime,
      String clientName) {

    TopicAndPartition tp = new TopicAndPartition(topic, partition);
    Map<TopicAndPartition, PartitionOffsetRequestInfo> requestInfoMap =
        new HashMap<TopicAndPartition, PartitionOffsetRequestInfo>();

    requestInfoMap.put(tp, new PartitionOffsetRequestInfo(whichTime, 1));

    // TODO: Investigate if the conversion can be done in a cleaner way
    kafka.javaapi.OffsetRequest req =
        new kafka.javaapi.OffsetRequest(requestInfoMap, kafka.api.OffsetRequest.CurrentVersion(), clientName);
    kafka.api.OffsetResponse scalaResponse = consumer.getOffsetsBefore(req.underlying());
    kafka.javaapi.OffsetResponse javaResponse = new kafka.javaapi.OffsetResponse(scalaResponse);

    if (javaResponse.hasError()) {
      throw new KafkaException("Error fetching data offset for topic: " + topic + " partition: " + partition);
    }

    long[] offsets = javaResponse.offsets(topic, partition);

    logger.info("Partition " + partition + " last offset at: " + offsets[0]);

    return offsets[0];
  }

  /**
   * This method taken from Kafka 0.8 SimpleConsumer Example
   * Used when the lead Kafka partition dies, and the new leader needs to be elected
   * */
  private String findNewLeader(String oldLeader, String topic, int partition, int port)
      throws Exception {

    for (int i = 0; i < numMetadataRefreshRetries; i++) {

      boolean goToSleep;
      PartitionMetadata metadata = findLeader(replicaBrokers, port, topic, partition);

      if (metadata == null) {
        goToSleep = true;
      } else if (metadata.leader() == null) {
        goToSleep = true;
      } else if (oldLeader.equalsIgnoreCase(metadata.leader().get().host()) && i == 0) {

        // first time through if the leader hasn't changed give ZooKeeper a second to recover
        // second time, assume the broker did recover before failover, or it was a non-Broker issue
        goToSleep = true;
      } else {
        return metadata.leader().get().host();
      }

      // introduce thread delay - for reasons above
      if (goToSleep) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ie) {
        }
      }
    }

    logger.error("Unable to find new leader after Broker failure. Exiting");
    throw new Exception("Unable to find new leader after Broker failure. Exiting");
  }

  /**
   * Finds the leader for a given Kafka topic and partition
   * @param seedBrokers - List of all Kafka Brokers
   * @param port - Port to connect to
   * @param topic - String name of the topic to search for
   * @param partition - Partition Number to search for
   * @return A PartitionMetadata Object for the partition found
   * */
  private PartitionMetadata findLeader(List<String> seedBrokers, int port, String topic, int partition) {

    PartitionMetadata returnMetaData = null;

    loop:
    /* Iterate through all the Brokers, Topics and their Partitions */
    for (String host : seedBrokers) {

      SimpleConsumer consumer = null;

      try {

        consumer = new SimpleConsumer(host, port, socketTimeoutMs, fetchBufferSize, "leaderLookup");

        Seq<String> topics = JavaConversions.asScalaBuffer(Collections.singletonList(topic));
        TopicMetadataRequest request = new TopicMetadataRequest(topics, 17);
        TopicMetadataResponse resp = consumer.send(request);

        Seq<TopicMetadata> metaData = resp.topicsMetadata();
        Iterator<TopicMetadata> it = metaData.iterator();

        while (it.hasNext()) {
          TopicMetadata item = it.next();

          Seq<PartitionMetadata> partitionMetaData = item.partitionsMetadata();
          Iterator<PartitionMetadata> innerIt = partitionMetaData.iterator();

          while (innerIt.hasNext()) {
            PartitionMetadata pm = innerIt.next();
            if (pm.partitionId() == partition) {
              returnMetaData = pm;
              break loop;
            }
          } /* End of Partition Loop */
        } /* End of Topic Loop */
      } catch (Exception e) {

        logger.error("Error communicating with " + host + " to find " + topic + ", " + partition);
        logger.error(e);
      } finally {

        // safely close consumer
        if (consumer != null) {
          consumer.close();
        }
      }
    } /* End of Broker Loop */

    if (returnMetaData != null) {
      replicaBrokers.clear();

      Seq<Broker> replicasSequence = returnMetaData.replicas();
      Iterator<Broker> replicaIterator = replicasSequence.iterator();

      while (replicaIterator.hasNext()) {
        Broker replica = replicaIterator.next();
        replicaBrokers.add(replica.host());
      }
    }

    return returnMetaData;
  }
}