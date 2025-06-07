package com.example.kafkafailover.service;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.Watch;
import io.kubernetes.client.util.Watch.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.google.gson.reflect.TypeToken;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class ConfigMapWatchService {
    private static final Logger logger = LoggerFactory.getLogger(ConfigMapWatchService.class);

    @Value("${kubernetes.namespace}")
    private String namespace;

    @Value("${kubernetes.configmap.name:failover-config}")
    private String configMapName;

    @Value("${kubernetes.watch.timeout:300}")
    private int watchTimeoutSeconds;

    @Autowired
    private KafkaConsumerService kafkaConsumerService;

    @PostConstruct
    public void init() {
        Thread watchThread = new Thread(this::watchConfigMap);
        watchThread.setDaemon(true);
        watchThread.start();
        logger.info("ConfigMap watch thread started for namespace: {} and configMap: {}", 
                    namespace, configMapName);
    }

    private void watchConfigMap() {
        try {
            ApiClient client = Config.defaultClient();
            // Increase the timeout
            client.setReadTimeout(watchTimeoutSeconds * 1000);
            Configuration.setDefaultApiClient(client);
            CoreV1Api api = new CoreV1Api();

            // Initial read of the ConfigMap
            readCurrentConfigMap(api);

            // Setup watch
            while (!Thread.currentThread().isInterrupted()) {
                try (Watch<V1ConfigMap> watch = Watch.createWatch(
                        client,
                        api.listNamespacedConfigMapCall(
                                namespace, null, null, null, null, null, null, null, null, 
                                watchTimeoutSeconds, true, null),
                        new TypeToken<Response<V1ConfigMap>>() {}.getType())) {
                    
                    for (Response<V1ConfigMap> item : watch) {
                        V1ConfigMap configMap = item.object;
                        if (configMap != null) {
                            var metadata = configMap.getMetadata();
                            if (metadata != null) {
                                String name = metadata.getName();
                                if (name != null && name.equals(configMapName)) {
                                    logger.info("ConfigMap {} modification detected: {}", configMapName, item.type);
                                    handleConfigMapChange(configMap);
                                }
                            }
                        }
                    }
                }
            }
        } catch (ApiException | IOException e) {
            logger.error("Exception while watching ConfigMap", e);
            // Reconnect after pause
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            // Recursive call to restart the watch
            watchConfigMap();
        }
    }

    private void readCurrentConfigMap(CoreV1Api api) {
        try {
            V1ConfigMap configMap = api.readNamespacedConfigMap(configMapName, namespace, null, null, null);
            handleConfigMapChange(configMap);
        } catch (ApiException e) {
            logger.error("Error reading ConfigMap {}: {}", configMapName, e.getMessage());
        }
    }

    private void handleConfigMapChange(V1ConfigMap configMap) {
        if (configMap != null && configMap.getData() != null) {
            Map<String, String> data = configMap.getData();
            String mode = data != null ? data.get("mode") : null;
            String drTimestamp = data != null ? data.get("drtimestamp") : null;
            String topic = data != null ? data.get("topic") : null;
            logger.info("ConfigMap updated - Mode: {}, DR Timestamp: {}, Topic: {}", mode, drTimestamp, topic);
            kafkaConsumerService.updateConsumerState(mode, drTimestamp, topic);
        } else {
            logger.warn("ConfigMap or its data is null");
        }
    }
}
