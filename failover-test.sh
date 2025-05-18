#!/bin/bash
set -e

echo "Starting Kafka failover test..."

# Get pod names
CONSUMER_A_POD=$(kubectl get pods -n region-a -l app=kafka-consumer,region=region-a -o name | head -n 1 | cut -d'/' -f2)
CONSUMER_B_POD=$(kubectl get pods -n region-b -l app=kafka-consumer,region=region-b -o name | head -n 1 | cut -d'/' -f2)

echo "Consumer A pod: $CONSUMER_A_POD (region-a)"
echo "Consumer B pod: $CONSUMER_B_POD (region-b)"

# Check if pods are ready
if [ -z "$CONSUMER_A_POD" ] || [ -z "$CONSUMER_B_POD" ]; then
    echo "Error: One or both consumer pods not found."
    exit 1
fi

# Get initial logs from consumer-a
echo "Initial logs from consumer-a (should be in ACTIVE mode):"
kubectl logs -n region-a $CONSUMER_A_POD --tail=20

# Get initial logs from consumer-b
echo "Initial logs from consumer-b (should be in STANDBY mode):"
kubectl logs -n region-b $CONSUMER_B_POD --tail=20

# Get current timestamp for failover
TIMESTAMP=$(date +%s000)
echo "Current timestamp for failover: $TIMESTAMP"

# Check consumer group offsets before failover
echo "Checking consumer group offsets before failover..."
if [ -z "$(kubectl get pods -l app.kubernetes.io/name=kafka -o name 2>/dev/null)" ]; then
    # Using docker-compose local Kafka
    docker run --rm --network host bitnami/kafka:latest kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --group failover-consumer-group \
        --describe
else
    # Using Helm-deployed Kafka
    KAFKA_POD=$(kubectl get pods -l app.kubernetes.io/name=kafka -o name | head -n 1 | cut -d'/' -f2)
    kubectl exec $KAFKA_POD -- kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --group failover-consumer-group \
        --describe
fi

# Simulate failure of consumer-a
echo "Simulating failure of consumer-a in region-a..."
kubectl scale deployment consumer-a -n region-a --replicas=0

echo "Waiting 5 seconds for consumer-a to be terminated..."
sleep 5

# Update the ConfigMap in region-b to activate failover
echo "Triggering failover by updating ConfigMap in region-b..."
kubectl patch configmap failover-config -n region-b --type=merge -p "{\"data\":{\"mode\":\"active\",\"drtimestamp\":\"$TIMESTAMP\"}}"

echo "Waiting 10 seconds for consumer-b to detect the change..."
sleep 10

# Get logs from consumer-b after failover
echo "Logs from consumer-b after failover (should now be in ACTIVE mode):"
kubectl logs -n region-b $CONSUMER_B_POD --tail=50

# Check consumer group offsets after failover
echo "Checking consumer group offsets after failover..."
if [ -z "$(kubectl get pods -l app.kubernetes.io/name=kafka -o name 2>/dev/null)" ]; then
    # Using docker-compose local Kafka
    docker run --rm --network host bitnami/kafka:latest kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --group failover-consumer-group \
        --describe
else
    # Using Helm-deployed Kafka
    kubectl exec $KAFKA_POD -- kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --group failover-consumer-group \
        --describe
fi

# Add more test messages to verify consumer-b is working
echo "Adding more test messages to verify consumer-b is working..."
if [ -z "$(kubectl get pods -l app.kubernetes.io/name=kafka -o name 2>/dev/null)" ]; then
    # Using docker-compose local Kafka
    for i in {21..30}; do
        echo "Producing message $i to test-topic"
        echo "message-$i-after-failover" | docker run --rm --interactive --network host \
            bitnami/kafka:latest kafka-console-producer.sh \
            --bootstrap-server localhost:9092 \
            --topic test-topic
    done
else
    # Using Helm-deployed Kafka
    for i in {21..30}; do
        echo "Producing message $i to test-topic"
        echo "message-$i-after-failover" | kubectl exec -i $KAFKA_POD -- kafka-console-producer.sh \
            --bootstrap-server localhost:9092 \
            --topic test-topic
    done
fi

echo "Waiting 5 seconds for consumer-b to process new messages..."
sleep 5

# Get final logs from consumer-b
echo "Final logs from consumer-b (should show processing of new messages):"
kubectl logs -n region-b $CONSUMER_B_POD --tail=30

echo "Failover test completed!"
echo ""
echo "To return the system to its initial state, run:"
echo "kubectl scale deployment consumer-a -n region-a --replicas=1"
echo "kubectl patch configmap failover-config -n region-b --type=merge -p '{\"data\":{\"mode\":\"standby\",\"drtimestamp\":\"\"}}'"
