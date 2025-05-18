#!/bin/bash
set -e

echo "Setting up Kubernetes-based Kafka failover system..."

# Check if Rancher Desktop is running with Kubernetes
if ! command -v kubectl &> /dev/null; then
    echo "kubectl not found. Please ensure Rancher Desktop is installed and running."
    exit 1
fi

echo "Checking Kubernetes connection..."
kubectl get nodes

# 1. Create Namespaces
echo "Creating region-a and region-b namespaces..."
kubectl apply -f kubernetes/region-a-namespace.yaml
kubectl apply -f kubernetes/region-b-namespace.yaml

# 2. Deploy Kafka using Helm (if Bitnami chart is preferred)
echo "Checking if Helm is installed..."
if ! command -v helm &> /dev/null; then
    echo "Helm not found. Using docker-compose for Kafka deployment."
    echo "Starting Kafka with docker-compose..."
    docker-compose up -d
else
    echo "Deploying Kafka via Helm chart..."
    helm repo add bitnami https://charts.bitnami.com/bitnami
    helm repo update
    helm install kafka bitnami/kafka \
        --set replicaCount=1 \
        --set autoCreateTopicsEnable=true \
        --set deleteTopicEnable=true
    
    # Wait for Kafka to be ready
    echo "Waiting for Kafka to be ready..."
    kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=kafka --timeout=300s
fi

# 3. Create test topic and load some data
echo "Creating test-topic and producing sample data..."
if [ -z "$(kubectl get pods -l app.kubernetes.io/name=kafka -o name 2>/dev/null)" ]; then
    # Using docker-compose local Kafka
    echo "Using local Kafka from docker-compose..."
    # Wait for Kafka to be ready
    sleep 10
    
    # Create the topic
    docker run --rm --network host bitnami/kafka:latest kafka-topics.sh \
        --create --topic test-topic \
        --bootstrap-server localhost:9092 \
        --partitions 3 \
        --replication-factor 1
    
    # Produce sample data
    for i in {1..20}; do
        echo "Producing message $i to test-topic"
        echo "message-$i" | docker run --rm --interactive --network host \
            bitnami/kafka:latest kafka-console-producer.sh \
            --bootstrap-server localhost:9092 \
            --topic test-topic
    done
else
    # Using Helm-deployed Kafka
    # Get the Kafka pod name
    KAFKA_POD=$(kubectl get pods -l app.kubernetes.io/name=kafka -o name | head -n 1 | cut -d'/' -f2)
    
    # Create the topic
    kubectl exec $KAFKA_POD -- kafka-topics.sh \
        --create --topic test-topic \
        --bootstrap-server localhost:9092 \
        --partitions 3 \
        --replication-factor 1
    
    # Produce sample data
    for i in {1..20}; do
        echo "Producing message $i to test-topic"
        echo "message-$i" | kubectl exec -i $KAFKA_POD -- kafka-console-producer.sh \
            --bootstrap-server localhost:9092 \
            --topic test-topic
    done
fi

# 4. Set up RBAC resources
echo "Creating RBAC resources..."
kubectl apply -f kubernetes/rbac.yaml

# 5. Create ConfigMaps
echo "Creating ConfigMaps for both regions..."
kubectl apply -f kubernetes/configmap-region-a.yaml
kubectl apply -f kubernetes/configmap-region-b.yaml

# 6. Build and load the application Docker image
echo "Building Docker image..."
docker build -t kafka-failover:latest .

# 7. Deploy consumers
echo "Deploying consumer pods..."
kubectl apply -f kubernetes/consumer-a-deployment.yaml
kubectl apply -f kubernetes/consumer-b-deployment.yaml

echo "Waiting for consumer pods to be ready..."
kubectl wait --for=condition=ready pod -l app=kafka-consumer,region=region-a -n region-a --timeout=300s
kubectl wait --for=condition=ready pod -l app=kafka-consumer,region=region-b -n region-b --timeout=300s

echo "Setup complete! The system is now ready for testing."
echo "Use ./failover-test.sh to test the failover functionality"
