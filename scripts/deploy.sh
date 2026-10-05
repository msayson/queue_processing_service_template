#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# Usage
# ---------------------------------------------------------------------------
usage() {
    echo "Usage: $0 --awsAccountId ACCOUNT_ID --awsRegion REGION [--stage STAGE]"
    echo "  STAGE defaults to 'dev' (single NAT gateway); 'prod' uses one NAT gateway per AZ"
    exit 1
}

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
AWS_ACCOUNT_ID=""
AWS_REGION=""
STAGE="dev"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --awsAccountId)
            AWS_ACCOUNT_ID="${2:?--awsAccountId requires a value}"
            shift 2
            ;;
        --awsRegion)
            AWS_REGION="${2:?--awsRegion requires a value}"
            shift 2
            ;;
        --stage)
            STAGE="${2:?--stage requires a value}"
            shift 2
            ;;
        *)
            echo "Unknown argument: $1"
            usage
            ;;
    esac
done

[[ -z "$AWS_ACCOUNT_ID" || -z "$AWS_REGION" ]] && usage

# ---------------------------------------------------------------------------
# Derived constants
# ---------------------------------------------------------------------------
ECR_REGISTRY="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
ECR_REPO="templates/queue-processing-service"
SERVICE_STACK="QueueProcessingServiceStack"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---------------------------------------------------------------------------
# Step 1: Build and test the service before deploying anything. The Docker
# image build only packages the JAR, so this is the deploy's test gate.
# ---------------------------------------------------------------------------
echo "==> Building and testing service..."
cd "$ROOT_DIR/service"
./gradlew build

# ---------------------------------------------------------------------------
# Step 2: Deploy QueueProcessorVpcStack (networking only)
# ---------------------------------------------------------------------------
echo "==> Installing CDK dependencies..."
cd "$ROOT_DIR/infrastructure"
npm install

export QUEUE_PROCESSING_ACCOUNT="$AWS_ACCOUNT_ID"
export QUEUE_PROCESSING_REGION="$AWS_REGION"
export QUEUE_NAME="QueueProcessingService-InputQueue"
export STAGE

echo "==> Bootstrapping CDK environment (safe to re-run)..."
npx cdk bootstrap "aws://$AWS_ACCOUNT_ID/$AWS_REGION"

echo "==> Deploying QueueProcessorVpcStack..."
npx cdk deploy QueueProcessorVpcStack --require-approval never

# ---------------------------------------------------------------------------
# Step 3: Ensure ECR repository exists, then authenticate and push the image.
# The repository must exist before QueueProcessingServiceStack is deployed so
# the ECS service can pull the image on creation and CloudFormation can reach
# a stable state without a CannotPullContainerError.
# ---------------------------------------------------------------------------
echo "==> Ensuring ECR repository exists..."
aws ecr describe-repositories \
    --repository-names "$ECR_REPO" \
    --region "$AWS_REGION" \
    --output text > /dev/null 2>&1 \
    || aws ecr create-repository \
        --repository-name "$ECR_REPO" \
        --region "$AWS_REGION" \
        --output text > /dev/null

echo "==> Authenticating Docker to ECR..."
aws ecr get-login-password --region "$AWS_REGION" | \
    docker login --username AWS --password-stdin "$ECR_REGISTRY"

# ---------------------------------------------------------------------------
# Step 4: Build and push the Docker image (linux/arm64 matches ECS task definition)
# ---------------------------------------------------------------------------
echo "==> Checking Docker daemon is running..."
docker info > /dev/null 2>&1 || { echo "ERROR: Docker daemon is not running. Start Docker Desktop and retry."; exit 1; }

echo "==> Building and pushing Docker image..."
cd "$ROOT_DIR/service"
docker buildx build --platform linux/arm64 \
    -t "$ECR_REGISTRY/$ECR_REPO:latest" \
    --push .

# ---------------------------------------------------------------------------
# Step 5: Deploy QueueProcessingServiceStack (image now in ECR)
# ---------------------------------------------------------------------------
cd "$ROOT_DIR/infrastructure"
echo "==> Deploying QueueProcessingServiceStack..."
npx cdk deploy QueueProcessingServiceStack --require-approval never

# ---------------------------------------------------------------------------
# Step 6: Force a new ECS deployment so the task pulls the new image immediately
# ---------------------------------------------------------------------------
echo "==> Forcing new ECS deployment..."

CLUSTER=$(aws cloudformation describe-stack-resources \
    --stack-name "$SERVICE_STACK" \
    --region "$AWS_REGION" \
    --query "StackResources[?ResourceType=='AWS::ECS::Cluster'].PhysicalResourceId" \
    --output text)

SERVICE=$(aws cloudformation describe-stack-resources \
    --stack-name "$SERVICE_STACK" \
    --region "$AWS_REGION" \
    --query "StackResources[?ResourceType=='AWS::ECS::Service'].PhysicalResourceId" \
    --output text)

aws ecs update-service \
    --region "$AWS_REGION" \
    --cluster "$CLUSTER" \
    --service "$SERVICE" \
    --force-new-deployment \
    --output text \
    --query "service.serviceName"

echo ""
echo "==> Deployment complete."
echo "    Image: $ECR_REGISTRY/$ECR_REPO:latest"
echo "    Monitor: aws ecs describe-services --region $AWS_REGION --cluster $CLUSTER --services $SERVICE"
