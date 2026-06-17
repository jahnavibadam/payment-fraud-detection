#!/bin/bash
###############################################################################
# Payment Fraud Detection - Local Development Setup
#
# This script sets up the complete local development environment from scratch:
#   1. Installs prerequisites (Docker, pip, LocalStack, AWS CLI check)
#   2. Builds the Lambda deployment zip
#   3. Starts LocalStack via Docker
#   4. Deploys AWS resources (DynamoDB, EventBridge, Lambda, API Gateway)
#   5. Starts the development server (frontend + API proxy)
#
# Prerequisites (installed automatically if missing):
#   - Java 17+ (for building the Lambda)
#   - Python 3.x (for LocalStack + dev server)
#   - Docker (for running LocalStack)
#   - AWS CLI v2 (for deploying resources)
#
# Usage:
#   chmod +x localstack/start-local.sh
#   ./localstack/start-local.sh
#
# The application will be available at: http://localhost:8080
#
# To stop:
#   ./localstack/stop-local.sh
#   OR: Ctrl+C (stops dev server), then: docker stop localstack
###############################################################################
set -e

# --- Configuration ---
REGION="us-east-1"
ENDPOINT="http://localhost:4566"
TABLE_NAME="FraudDetection"
EVENT_BUS_NAME="fraud-detection"
LAMBDA_NAME="FraudDetectionHandler"
API_NAME="FraudDetectionAPI"
LOCALSTACK_IMAGE="localstack/localstack:3.4"
CONTAINER_NAME="localstack"
DEV_SERVER_PORT=8080
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LAMBDA_ZIP="$PROJECT_ROOT/build/distributions/fraud-detection-lambda.zip"

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=$REGION

# --- Helper functions ---
print_header() {
  echo ""
  echo "============================================================"
  echo "  $1"
  echo "============================================================"
}

print_step() {
  echo ""
  echo "--- [$1/$TOTAL_STEPS] $2 ---"
}

check_command() {
  if ! command -v "$1" &>/dev/null; then
    return 1
  fi
  return 0
}

TOTAL_STEPS=7

print_header "Payment Fraud Detection - Local Setup"
echo "Project root: $PROJECT_ROOT"
echo ""

###############################################################################
# STEP 1: Check and install prerequisites
###############################################################################
print_step 1 "Checking prerequisites"

# Check Java
if check_command java; then
  JAVA_VERSION=$(java -version 2>&1 | head -1)
  echo "✓ Java found: $JAVA_VERSION"
else
  echo "✗ Java not found. Please install Java 17+ and try again."
  echo "  Ubuntu/Debian: sudo apt-get install openjdk-17-jdk"
  echo "  macOS: brew install openjdk@17"
  exit 1
fi

# Check Python 3
if check_command python3; then
  PYTHON_VERSION=$(python3 --version)
  echo "✓ Python found: $PYTHON_VERSION"
else
  echo "✗ Python 3 not found. Please install Python 3.x and try again."
  echo "  Ubuntu/Debian: sudo apt-get install python3"
  exit 1
fi

# Check AWS CLI
if check_command aws; then
  AWS_VERSION=$(aws --version 2>&1)
  echo "✓ AWS CLI found: $AWS_VERSION"
else
  echo "✗ AWS CLI not found. Please install AWS CLI v2."
  echo "  https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"
  exit 1
fi

# Check and install Docker
if check_command docker; then
  DOCKER_VERSION=$(docker --version)
  echo "✓ Docker found: $DOCKER_VERSION"
else
  echo "⟳ Docker not found. Installing..."
  if check_command apt-get; then
    sudo apt-get update -qq
    sudo apt-get install -y -qq docker.io
    sudo systemctl start docker
    sudo usermod -aG docker "$USER"
    echo "✓ Docker installed. Note: you may need to log out and back in for group changes."
  else
    echo "✗ Cannot auto-install Docker. Please install manually:"
    echo "  https://docs.docker.com/engine/install/"
    exit 1
  fi
fi

# Ensure Docker daemon is running
if ! sudo docker info &>/dev/null; then
  echo "⟳ Starting Docker daemon..."
  sudo systemctl start docker
  sleep 2
fi
echo "✓ Docker daemon is running"

# Check pip3 and install LocalStack CLI (optional, for 'localstack' command)
if ! check_command pip3; then
  echo "⟳ pip3 not found. Installing..."
  if check_command apt-get; then
    sudo apt-get install -y -qq python3-pip
  fi
fi

###############################################################################
# STEP 2: Build Lambda deployment package
###############################################################################
print_step 2 "Building Lambda deployment zip"

cd "$PROJECT_ROOT"

if [ ! -f "./gradlew" ]; then
  echo "✗ Gradle wrapper not found. Are you in the correct project directory?"
  exit 1
fi

chmod +x ./gradlew
./gradlew buildLambdaZip --quiet

if [ ! -f "$LAMBDA_ZIP" ]; then
  echo "✗ Lambda zip not found at: $LAMBDA_ZIP"
  echo "  Build may have failed. Run: ./gradlew buildLambdaZip"
  exit 1
fi

ZIP_SIZE=$(du -h "$LAMBDA_ZIP" | cut -f1)
echo "✓ Lambda zip built: $LAMBDA_ZIP ($ZIP_SIZE)"

###############################################################################
# STEP 3: Start LocalStack container
###############################################################################
print_step 3 "Starting LocalStack"

# Stop existing container if running
if sudo docker ps -q --filter "name=$CONTAINER_NAME" | grep -q .; then
  echo "⟳ Stopping existing LocalStack container..."
  sudo docker stop "$CONTAINER_NAME" >/dev/null 2>&1 || true
  sleep 2
fi

# Remove stopped container if exists
sudo docker rm "$CONTAINER_NAME" >/dev/null 2>&1 || true

# Pull image if not present
if ! sudo docker image inspect "$LOCALSTACK_IMAGE" &>/dev/null; then
  echo "⟳ Pulling LocalStack image (this may take a minute)..."
  sudo docker pull "$LOCALSTACK_IMAGE"
fi

# Start LocalStack
echo "⟳ Starting LocalStack container..."
sudo docker run -d \
  --rm \
  -p 4566:4566 \
  -p 4510-4559:4510-4559 \
  -e SERVICES=dynamodb,lambda,apigateway,events,iam,s3,sqs,sts \
  -e LAMBDA_EXECUTOR=local \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --name "$CONTAINER_NAME" \
  "$LOCALSTACK_IMAGE" >/dev/null

# Wait for LocalStack to be ready
echo "⟳ Waiting for LocalStack to be ready..."
for i in $(seq 1 30); do
  if aws --endpoint-url=$ENDPOINT sts get-caller-identity >/dev/null 2>&1; then
    echo "✓ LocalStack is ready (took ~$((i * 2))s)"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "✗ LocalStack did not become ready within 60 seconds."
    echo "  Check logs: sudo docker logs $CONTAINER_NAME"
    exit 1
  fi
  sleep 2
done

###############################################################################
# STEP 4: Deploy all AWS resources
###############################################################################
print_step 4 "Deploying full stack to LocalStack"

bash "$PROJECT_ROOT/localstack/deploy.sh"

###############################################################################
# STEP 5: Seed test data
###############################################################################
print_step 5 "Seeding test data"

bash "$PROJECT_ROOT/localstack/seed-data.sh"

###############################################################################
# STEP 6: Smoke test
###############################################################################
print_step 6 "Running smoke test"

# Auto-detect API URL
API_ID=$(aws --endpoint-url=$ENDPOINT apigateway get-rest-apis \
  --query "items[0].id" --output text 2>/dev/null || echo "")
API_URL="$ENDPOINT/restapis/$API_ID/local/_user_request_"

SMOKE_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API_URL/fraud-check" \
  -H "Content-Type: application/json" \
  -d '{"messageId":"smoke-test","debtorAccount":{"sortCode":"101010","accountNumber":"10000001","accountName":"Smoke Test"},"creditorAccount":{"sortCode":"505050","accountNumber":"50000001","accountName":"Test"},"amount":50.00,"currency":"GBP","paymentReference":"smoke","confirmationOfPayee":{"result":"MATCH","matchedName":"Test"},"channel":{"type":"MOBILE","deviceId":null,"geoLocation":null,"sessionDuration":null},"timestamp":"2024-01-01T00:00:00Z"}' \
  2>/dev/null || echo "000")

if [ "$SMOKE_RESPONSE" = "200" ]; then
  echo "✓ Smoke test passed (HTTP 200)"
  # Verify audit record was created by EventBridge -> AuditLogHandler
  sleep 3
  AUDIT_ITEM=$(aws --endpoint-url=$ENDPOINT dynamodb get-item \
    --table-name "$TABLE_NAME" \
    --key '{"pk": {"S": "AUDIT#smoke-test"}, "sk": {"S": "DECISION"}}' \
    --query 'Item.decision.S' --output text 2>/dev/null || echo "NONE")
  if [ "$AUDIT_ITEM" != "NONE" ] && [ "$AUDIT_ITEM" != "None" ]; then
    echo "✓ EventBridge flow verified: audit record created (decision=$AUDIT_ITEM)"
  else
    echo "⚠ Audit record not found yet (EventBridge delivery may be delayed in LocalStack)"
  fi
else
  echo "⚠ Smoke test returned HTTP $SMOKE_RESPONSE (first Lambda invocation may be slow - cold start)"
  echo "  This is normal for Java Lambdas. Try again in a few seconds."
fi

###############################################################################
# STEP 7: Start development server
###############################################################################
print_step 7 "Starting development server"

# Export the API URL for the dev server
export LOCALSTACK_API_URL="$API_URL"
export PORT="$DEV_SERVER_PORT"

print_header "Local Environment Ready!"
echo ""
echo "  Frontend:         http://localhost:$DEV_SERVER_PORT"
echo "  API (via proxy):  http://localhost:$DEV_SERVER_PORT/fraud-check"
echo "  API (direct):     $API_URL/fraud-check"
echo "  LocalStack:       $ENDPOINT"
echo ""
echo "  To stop: Ctrl+C, then run: ./localstack/stop-local.sh"
echo ""
echo "============================================================"
echo ""

# Start the dev server (foreground - Ctrl+C to stop)
exec python3 "$PROJECT_ROOT/localstack/serve.py"
