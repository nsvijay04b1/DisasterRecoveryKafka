package com.example.kafkafailover.service;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.util.Config;

import javax.annotation.PostConstruct;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class KafkaConsumerService {
    private static final Logger logger = LoggerFactory.getLogger(KafkaConsumerService.class);
    private static final String KAFKA_LISTENER_ID = "kafkaListenerContainerId";

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @Autowired
    private OffsetResolverService offsetResolverService;

    @Value("${kafka.topic:test-topic}")
    private String defaultTopic;

    @Value("${kubernetes.namespace}")
    private String namespace;

    @Value("${kubernetes.configmap.name:failover-config}")
    private String configMapName;

    private AtomicBoolean isActive = new AtomicBoolean(false);
    private AtomicReference<String> currentTopic = new AtomicReference<>();
    private AtomicBoolean needsOffsetReset = new AtomicBoolean(false);
    private AtomicReference<Map<TopicPartition, Long>> targetOffsets = new AtomicReference<>(Collections.emptyMap());

    @PostConstruct
    public void init() {
        currentTopic.set(defaultTopic);
        // Default behavior: if in region-a, start as active, else standby
        if (namespace.equals("region-a")) {
            isActive.set(true);
            logger.info("Starting in ACTIVE mode (region-a)");
        } else {
            isActive.set(false);
            logger.info("Starting in STANDBY mode (region-b)");
            // Pause the Kafka listener until activated
            pauseConsumer();
        }
    }

    /**
     * Main Kafka listener method
     */
    @KafkaListener(id = KAFKA_LISTENER_ID, topics = "${kafka.topic:test-topic}", groupId = "${spring.kafka.consumer.group-id:failover-consumer-group}")
    public void listen(ConsumerRecord<String, String> record, Consumer<String, String> consumer, Acknowledgment ack) {
        logger.info("Consumer group: {} | Processing message: topic={}, partition={}, offset={}, key={}, value={}",
                consumer.groupMetadata().groupId(), record.topic(), record.partition(), record.offset(), record.key(), record.value());

        if (!isActive.get()) {
            logger.debug("In STANDBY mode - not processing messages");
            ack.acknowledge();
            return;
        }

        // Always check if we need to seek to a specific offset before processing
        if (needsOffsetReset.getAndSet(false)) {
            Map<TopicPartition, Long> offsets = targetOffsets.get();
            if (offsets != null && !offsets.isEmpty()) {
                for (Map.Entry<TopicPartition, Long> entry : offsets.entrySet()) {
                    consumer.seek(entry.getKey(), entry.getValue());
                    logger.info("Consumer sought to offset {} for partition {}", entry.getValue(), entry.getKey().partition());
                }
            }
        }

        // Only process messages at or after the DR timestamp offset
        Map<TopicPartition, Long> offsets = targetOffsets.get();
        if (offsets != null && offsets.containsKey(new TopicPartition(record.topic(), record.partition()))) {
            long drOffset = offsets.get(new TopicPartition(record.topic(), record.partition()));
            if (record.offset() < drOffset) {
                logger.info("Skipping message at offset {} (before DR offset {})", record.offset(), drOffset);
                ack.acknowledge();
                return;
            }
        }

        // Process the record
        logger.info("Processing message: topic={}, partition={}, offset={}, key={}, value={}",
                record.topic(), record.partition(), record.offset(), record.key(), record.value());
        ack.acknowledge();
    }

    /**
     * Prevent the application from exiting by running a background thread that blocks
     */
    @PostConstruct
    public void keepAlive() {
        Thread keepAliveThread = new Thread(() -> {
            try {
                // Block forever
                synchronized (this) {
                    this.wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        keepAliveThread.setDaemon(false); // Prevent JVM exit
        keepAliveThread.start();
    }

    /**
     * Update the consumer state based on ConfigMap changes
     */
    public void updateConsumerState(String mode, String drTimestamp, String topic) {
        boolean wasActive = isActive.get();
        boolean nowActive = "active".equalsIgnoreCase(mode);
        
        // Update the topic if provided
        if (topic != null && !topic.isEmpty()) {
            currentTopic.set(topic);
        }

        // If switching from standby to active mode
        if (!wasActive && nowActive) {
            logger.info("Switching from STANDBY to ACTIVE mode");
            
            if (drTimestamp != null && !drTimestamp.isEmpty()) {
                long timestamp = offsetResolverService.parseTimestamp(drTimestamp);
                String iso8601 = offsetResolverService.formatTimestampAsIso8601(timestamp);
                logger.info("DR timestamp provided: {} (epoch: {}, ISO-8601 UTC: {})", drTimestamp, timestamp, iso8601);
                // Resolve offsets for the timestamp
                Map<TopicPartition, Long> offsets = offsetResolverService.resolveOffsetsForTime(
                        currentTopic.get(), timestamp);
                if (!offsets.isEmpty()) {
                    targetOffsets.set(offsets);
                    needsOffsetReset.set(true);
                    logger.info("Offsets resolved for DR timestamp: {}", offsets);
                    
                    // Store the resolved offsets in the ConfigMap for reference
                    storeOffsetsInConfigMap(offsets);
                }
            }
            
            // Resume the consumer
            resumeConsumer();
            isActive.set(true);
        } 
        // If switching from active to standby mode
        else if (wasActive && !nowActive) {
            logger.info("Switching from ACTIVE to STANDBY mode");
            pauseConsumer();
            isActive.set(false);
        }
        // If mode remains the same but other parameters changed
        else if (nowActive && wasActive && drTimestamp != null && !drTimestamp.isEmpty()) {
            logger.info("Already in ACTIVE mode, but DR timestamp changed: {}", drTimestamp);
            // Handle timestamp change while already active
            long timestamp = offsetResolverService.parseTimestamp(drTimestamp);
            String iso8601 = offsetResolverService.formatTimestampAsIso8601(timestamp);
            logger.info("DR timestamp provided: {} (epoch: {}, ISO-8601 UTC: {})", drTimestamp, timestamp, iso8601);
            Map<TopicPartition, Long> offsets = offsetResolverService.resolveOffsetsForTime(
                    currentTopic.get(), timestamp);
            
            if (!offsets.isEmpty()) {
                targetOffsets.set(offsets);
                needsOffsetReset.set(true);
                logger.info("Offsets resolved for new DR timestamp: {}", offsets);
                storeOffsetsInConfigMap(offsets);
            }
        }
    }

    /**
     * Pause the Kafka message listener
     */
    private void pauseConsumer() {
        MessageListenerContainer listenerContainer = 
                kafkaListenerEndpointRegistry.getListenerContainer(KAFKA_LISTENER_ID);
        if (listenerContainer != null && listenerContainer.isRunning()) {
            listenerContainer.pause();
            logger.info("Kafka listener paused");
        }
    }

    /**
     * Resume the Kafka message listener
     */
    private void resumeConsumer() {
        MessageListenerContainer listenerContainer = 
                kafkaListenerEndpointRegistry.getListenerContainer(KAFKA_LISTENER_ID);
        if (listenerContainer != null) {
            // MessageListenerContainer does not have isPaused(), so only check running state
            if (!listenerContainer.isRunning()) {
                listenerContainer.start();
                logger.info("Kafka listener started");
            } else {
                listenerContainer.resume();
                logger.info("Kafka listener resumed");
            }
        }
    }

    /**
     * Store the resolved offsets in the ConfigMap for reference
     */
    private void storeOffsetsInConfigMap(Map<TopicPartition, Long> offsets) {
        try {
            ApiClient client = Config.defaultClient();
            Configuration.setDefaultApiClient(client);
            CoreV1Api api = new CoreV1Api();

            V1ConfigMap configMap = api.readNamespacedConfigMap(configMapName, namespace, null, null, null);
            
            // Add the offsets information to the ConfigMap
            StringBuilder offsetsStr = new StringBuilder();
            for (Map.Entry<TopicPartition, Long> entry : offsets.entrySet()) {
                offsetsStr.append(entry.getKey().topic())
                         .append('-')
                         .append(entry.getKey().partition())
                         .append(':')
                         .append(entry.getValue())
                         .append(',');
            }
            
            // Remove trailing comma
            if (offsetsStr.length() > 0) {
                offsetsStr.setLength(offsetsStr.length() - 1);
            }
            
            Map<String, String> data = configMap.getData();
            if (data != null) {
                data.put("resolvedOffsets", offsetsStr.toString());
            }
            
            // Update the ConfigMap
            api.replaceNamespacedConfigMap(configMapName, namespace, configMap, null, null, null);
            logger.info("Updated ConfigMap with resolved offsets: {}", offsetsStr);
            
        } catch (ApiException | IOException e) {
            logger.error("Error updating ConfigMap with offsets", e);
        }
    }
}
