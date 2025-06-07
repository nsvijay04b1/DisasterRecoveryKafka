# Kafka

## Install 
  helm install my-kafka bitnami/kafka --namespace kafka \
  --set replicaCount=1 \
  --set service.type=ClusterIP \
  --set listeners.client.protocol=PLAINTEXT \
  --set 'extraEnv[0].name=KAFKA_CFG_ADVERTISED_LISTENERS' \
  --set 'extraEnv[0].value=PLAINTEXT://my-kafka.kafka.svc.cluster.local:9092\,PLAINTEXT://localhost:9092'



## Access 
** Please be patient while the chart is being deployed **

Kafka can be accessed by consumers via port 9092 on the following DNS name from within your cluster:

    ```bash
    my-kafka.kafka.svc.cluster.local:9092`
    ```

Each Kafka broker can be accessed by producers via port 9092 on the following DNS name(s) from within your cluster:

    ```bash
    my-kafka-controller-0.my-kafka-controller-headless.kafka.svc.cluster.local:9092
    my-kafka-controller-1.my-kafka-controller-headless.kafka.svc.cluster.local:9092
    my-kafka-controller-2.my-kafka-controller-headless.kafka.svc.cluster.local:9092
    ```

To create a pod that you can use as a Kafka client run the following commands:

    ```bash
    kubectl run my-kafka-client --restart='Never' --image docker.io/bitnami/kafka:4.0.0-debian-12-r4 --namespace kafka --command -- sleep infinity

    kubectl exec --tty -i my-kafka-client --namespace kafka -- bash
    ```

    PRODUCER:

    ```bash
    kafka-console-producer.sh \
            --bootstrap-server my-kafka.kafka.svc.cluster.local:9092 \
            --topic test-topic
    ```

    CONSUMER:

    ```bash
    kafka-console-consumer.sh \
            --bootstrap-server my-kafka.kafka.svc.cluster.local:9092 \
            --topic test-topic \
            --from-beginning
    ```

    LIST TOPICS

    ```bash
    kafka-topics.sh --bootstrap-server my-kafka.kafka.svc.cluster.local:9092 --list
    ```

WARNING: There are "resources" sections in the chart not set. Using "resourcesPreset" is not recommended for production. For production installations, please set the following values according to your workload needs:
  - controller.resources
  - defaultInitContainers.prepareConfig.resources
+info https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/


mvn clean package -DskipTests

docker build -t kafka-failover:latest .


kafka-console-consumer.sh \
  --bootstrap-server my-kafka.kafka.svc.cluster.local:9092 \
  --topic test-topic \
  --from-beginning \
  --property print.partition=true \
  --property print.offset=true \
  --property print.timestamp=true


helm repo add kafka-ui https://provectus.github.io/kafka-ui-charts
helm upgrade --install kafka-ui  kafka-ui/kafka-ui -f values.yml
kubectl --namespace default port-forward kafka-ui-5976bdb5cd-ts9m4 9090:8080


kafka-consumer-groups.sh --bootstrap-server my-kafka.kafka.svc.cluster.local:9092 \
  --describe --group failover-consumer-group

GROUP                   TOPIC           PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG             CONSUMER-ID                                                             HOST            CLIENT-ID
failover-consumer-group test-topic      0          80              80              0               consumer-failover-consumer-group-3-8bf41636-53c9-4029-8582-09268bf522e2 /10.42.0.35     consumer-failover-consumer-group-3