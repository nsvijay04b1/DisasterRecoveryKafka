package com.example.kafkafailover.service;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OffsetResolverService {
    private static final Logger logger = LoggerFactory.getLogger(OffsetResolverService.class);

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    /**
     * Resolves offsets for a given topic at a specific timestamp.
     * 
     * @param topic The Kafka topic
     * @param timestamp The timestamp in milliseconds since epoch
     * @return Map of TopicPartition to resolved offset
     */
    public Map<TopicPartition, Long> resolveOffsetsForTime(String topic, long timestamp) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer()) {
            // Get the partitions for the topic
            Set<TopicPartition> partitions = consumer.partitionsFor(topic)
                .stream()
                .map(partitionInfo -> new TopicPartition(topic, partitionInfo.partition()))
                .collect(Collectors.toSet());
            
            if (partitions.isEmpty()) {
                logger.warn("No partitions found for topic: {}", topic);
                return Collections.emptyMap();
            }

            // Create a map of partitions to timestamp
            Map<TopicPartition, Long> timestampsToSearch = partitions.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> timestamp));

            // Find the offset for each partition at the given timestamp
            Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes = consumer.offsetsForTimes(timestampsToSearch);
            
            // Convert to map of TopicPartition to offset
            Map<TopicPartition, Long> resolvedOffsets = new HashMap<>();
            for (Map.Entry<TopicPartition, OffsetAndTimestamp> entry : offsetsForTimes.entrySet()) {
                if (entry.getValue() != null) {
                    resolvedOffsets.put(entry.getKey(), entry.getValue().offset());
                    logger.info("Resolved offset {} for partition {} at timestamp {}", 
                        entry.getValue().offset(), entry.getKey().partition(), timestamp);
                } else {
                    // If no offset found for the timestamp, use the earliest available
                    consumer.seekToBeginning(Collections.singleton(entry.getKey()));
                    long earliestOffset = consumer.position(entry.getKey());
                    resolvedOffsets.put(entry.getKey(), earliestOffset);
                    logger.info("No message found at timestamp {}. Using earliest offset {} for partition {}", 
                        timestamp, earliestOffset, entry.getKey().partition());
                }
            }

            return resolvedOffsets;
        } catch (Exception e) {
            logger.error("Error resolving offsets for topic {} at timestamp {}", topic, timestamp, e);
            return Collections.emptyMap();
        }
    }

    /**
     * Parse timestamp string into epoch milliseconds.
     * Accepts ISO-8601 format (e.g. "2023-10-15T14:30:00Z") or milliseconds since epoch.
     */
    public long parseTimestamp(String timestampStr) {
        try {
            // Try parsing as a long (milliseconds since epoch)
            return Long.parseLong(timestampStr);
        } catch (NumberFormatException e) {
            try {
                // Try parsing as ISO-8601 date
                return Instant.parse(timestampStr).toEpochMilli();
            } catch (Exception ex) {
                logger.error("Failed to parse timestamp: {}", timestampStr, ex);
                // Return current time if parsing fails
                return System.currentTimeMillis();
            }
        }
    }
}
