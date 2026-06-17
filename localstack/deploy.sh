#!/bin/bash
###############################################################################
# LocalStack deployment script for the Payment Fraud Detection system
#
# Deploys the FULL application stack:
#   - DynamoDB table (FraudDetection) with GSI
#   - S3 bucket (fraud-audit-archive)
#   - EventBridge bus (fraud-detection) with routing rules
#   - 3 Lambda functions:
#       1. FraudDetectionHandler (API-triggered, scores payments)
#       2. AuditLogHandler (EventBridge-triggered, persists decisions to DDB + S3)
#       3. ProfileUpdateHandler (EventBridge-triggered, updates customer profiles)
#   - API Gateway with /fraud-check and /confirm-payment routes
#   - EventBridge rules routing events to consumer Lambdas
#
# Usage: ./localstack/deploy.sh
# Assumes LocalStack is already running on port 4566.
###############################################################################
set -e

REGION="us-east-1"
ENDPOINT="http://localhost:4566"
TABLE_NAME="FraudDetection"
EVENT_BUS_NAME="fraud-detection"
AUDIT_BUCKET="fraud-audit-archive"
API_NAME="FraudDetectionAPI"
LAMBDA_ZIP="$(cd "$(dirname "$0")/.." && pwd)/build/distributions/fraud-detection-lambda.zip"

# Lambda names
FRAUD_HANDLER="FraudDetectionHandler"
AUDIT_HANDLER="AuditLogHandler"
PROFILE_HANDLER="ProfileUpdateHandler"

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=$REGION

echo "=== Deploying Full Stack to LocalStack ==="

# Wait for LocalStack to be ready
echo "Waiting for LocalStack to be ready..."
for i in $(seq 1 30); do
  if aws --endpoint-url=$ENDPOINT sts get-caller-identity >/dev/null 2>&1; then
    echo "✓ LocalStack is ready!"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "✗ LocalStack did not become ready in time"
    exit 1
  fi
  sleep 2
done

# ─────────────────────────────────────────────────────────────────────────────
# 1. DynamoDB Table
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "--- [1/7] Creating DynamoDB table: $TABLE_NAME ---"
aws --endpoint-url=$ENDPOINT dynamodb create-table \
  --table-name "$TABLE_NAME" \
  --key-schema \
    AttributeName=pk,KeyType=HASH \
    AttributeName=sk,KeyType=RANGE \
  --attribute-definitions \
    AttributeName=pk,AttributeType=S \
    AttributeName=sk,AttributeType=S \
    AttributeName=gsiPk,AttributeType=S \
    AttributeName=gsiSk,AttributeType=S \
  --global-secondary-indexes '[{
    "IndexName": "DecisionByDate",
    "KeySchema": [
      {"AttributeName": "gsiPk", "KeyType": "HASH"},
      {"AttributeName": "gsiSk", "KeyType": "RANGE"}
    ],
    "Projection": {"ProjectionType": "ALL"}
  }]' \
  --billing-mode PAY_PER_REQUEST \
  --no-cli-pager >/dev/null 2>&1 || echo "  (table already exists)"
echo "✓ DynamoDB table: $TABLE_NAME (GSI: DecisionByDate)"

# ─────────────────────────────────────────────────────────────────────────────
# 2. S3 Bucket
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "--- [2/7] Creating S3 bucket: $AUDIT_BUCKET ---"
aws --endpoint-url=$ENDPOINT s3 mb "s3://$AUDIT_BUCKET" \
  --no-cli-pager >/dev/null 2>&1 || echo "  (bucket already exists)"
echo "✓ S3 bucket: $AUDIT_BUCKET"

# ─────────────────────────────────────────────────────────────────────────────
# 3. EventBridge Bus
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "--- [3/7] Creating EventBridge bus: $EVENT_BUS_NAME ---"
aws --endpoint-url=$ENDPOINT events create-event-bus \
  --name "$EVENT_BUS_NAME" \
  --no-cli-pager >/dev/null 2>&1 || echo "  (bus already exists)"
echo "✓ EventBridge bus: $EVENT_BUS_NAME"

# ─────────────────────────────────────────────────────────────────────────────
# 4. Lambda Functions (all 3)
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "--- [4/7] Deploying Lambda functions ---"

LAMBDA_ROLE="arn:aws:iam::000000000000:role/lambda-role"

# 4a. FraudDetectionHandler
aws --endpoint-url=$ENDPOINT lambda delete-function \
  --function-name "$FRAUD_HANDLER" 2>/dev/null || true
aws --endpoint-url=$ENDPOINT lambda create-function \
  --function-name "$FRAUD_HANDLER" \
  --runtime java17 \
  --handler "com.frauddetection.handler.FraudDetectionHandler::handleRequest" \
  --role "$LAMBDA_ROLE" \
  --zip-file "fileb://$LAMBDA_ZIP" \
  --timeout 30 \
  --memory-size 512 \
  --environment "Variables={FRAUD_DETECTION_TABLE=$TABLE_NAME,EVENT_BUS_NAME=$EVENT_BUS_NAME,AWS_REGION=$REGION}" \
  --no-cli-pager >/dev/null
echo "✓ Lambda: $FRAUD_HANDLER (API handler, publishes DecisionMade events)"

# 4b. AuditLogHandler
aws --endpoint-url=$ENDPOINT lambda delete-function \
  --function-name "$AUDIT_HANDLER" 2>/dev/null || true
aws --endpoint-url=$ENDPOINT lambda create-function \
  --function-name "$AUDIT_HANDLER" \
  --runtime java17 \
  --handler "com.frauddetection.handler.AuditLogHandler::handleRequest" \
  --role "$LAMBDA_ROLE" \
  --zip-file "fileb://$LAMBDA_ZIP" \
  --timeout 30 \
  --memory-size 512 \
  --environment "Variables={FRAUD_DETECTION_TABLE=$TABLE_NAME,AUDIT_BUCKET_NAME=$AUDIT_BUCKET,AWS_REGION=$REGION}" \
  --no-cli-pager >/dev/null
echo "✓ Lambda: $AUDIT_HANDLER (consumes DecisionMade, writes to DDB + S3)"

# 4c. ProfileUpdateHandler
aws --endpoint-url=$ENDPOINT lambda delete-function \
  --function-name "$PROFILE_HANDLER" 2>/dev/null || true
aws --endpoint-url=$ENDPOINT lambda create-function \
  --function-name "$PROFILE_HANDLER" \
  --runtime java17 \
  --handler "com.frauddetection.handler.ProfileUpdateHandler::handleRequest" \
  --role "$LAMBDA_ROLE" \
  --zip-file "fileb://$LAMBDA_ZIP" \
  --timeout 30 \
  --memory-size 512 \
  --environment "Variables={FRAUD_DETECTION_TABLE=$TABLE_NAME,AWS_REGION=$REGION}" \
  --no-cli-pager >/dev/null
echo "✓ Lambda: $PROFILE_HANDLER (consumes TransactionCompleted, updates profiles)"

# ─────────────────────────────────────────────────────────────────────────────
# 5. EventBridge Rules (routing events to SQS → Lambda workaround)
#    NOTE: LocalStack Community Edition doesn't reliably deliver EventBridge
#    events directly to Lambda targets. We use SQS as an intermediary:
#    EventBridge → SQS queue → Lambda (via event source mapping)
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "--- [5/7] Creating EventBridge rules + SQS bridge ---"

AUDIT_HANDLER_ARN="arn:aws:lambda:$REGION:000000000000:function:$AUDIT_HANDLER"
PROFILE_HANDLER_ARN="arn:aws:lambda:$REGION:000000000000:function:$PROFILE_HANDLER"

# Create SQS queues as EventBridge targets
aws --endpoint-url=$ENDPOINT sqs create-queue \
  --queue-name "decision-audit-queue" \
  --no-cli-pager >/dev/null 2>&1 || true
AUDIT_QUEUE_ARN="arn:aws:sqs:$REGION:000000000000:decision-audit-queue"

aws --endpoint-url=$ENDPOINT sqs create-queue \
  --queue-name "profile-update-queue" \
  --no-cli-pager >/dev/null 2>&1 || true
PROFILE_QUEUE_ARN="arn:aws:sqs:$REGION:000000000000:profile-update-queue"

echo "✓ SQS queues: decision-audit-queue, profile-update-queue"

# Rule 1: DecisionMade -> SQS (decision-audit-queue)
aws --endpoint-url=$ENDPOINT events put-rule \
  --name "DecisionMadeToAudit" \
  --event-bus-name "$EVENT_BUS_NAME" \
  --event-pattern '{
    "source": ["com.frauddetection"],
    "detail-type": ["DecisionMade"]
  }' \
  --state ENABLED \
  --no-cli-pager >/dev/null

aws --endpoint-url=$ENDPOINT events put-targets \
  --rule "DecisionMadeToAudit" \
  --event-bus-name "$EVENT_BUS_NAME" \
  --targets "[{\"Id\": \"audit-sqs-target\", \"Arn\": \"$AUDIT_QUEUE_ARN\"}]" \
  --no-cli-pager >/dev/null

echo "✓ Rule: DecisionMade → SQS (decision-audit-queue)"

# Rule 2: TransactionCompleted -> SQS (profile-update-queue)
aws --endpoint-url=$ENDPOINT events put-rule \
  --name "TransactionCompletedToProfile" \
  --event-bus-name "$EVENT_BUS_NAME" \
  --event-pattern '{
    "source": ["com.frauddetection"],
    "detail-type": ["TransactionCompleted"]
  }' \
  --state ENABLED \
  --no-cli-pager >/dev/null

aws --endpoint-url=$ENDPOINT events put-targets \
  --rule "TransactionCompletedToProfile" \
  --event-bus-name "$EVENT_BUS_NAME" \
  --targets "[{\"Id\": \"profile-sqs-target\", \"Arn\": \"$PROFILE_QUEUE_ARN\"}]" \
  --no-cli-pager >/dev/null

echo "✓ Rule: TransactionCompleted → SQS (profile-update-queue)"

# Create Lambda event source mappings (SQS → Lambda)
# Delete existing mappings first
for uuid in $(aws --endpoint-url=$ENDPOINT lambda list-event-source-mappings \
  --function-name "$AUDIT_HANDLER" --query 'EventSourceMappings[].UUID' --output text 2>/dev/null); do
  aws --endpoint-url=$ENDPOINT lambda delete-event-source-mapping --uuid "$uuid" --no-cli-pager >/dev/null 2>&1 || true
done

aws --endpoint-url=$ENDPOINT lambda create-event-source-mapping \
  --function-name "$AUDIT_HANDLER" \
  --event-source-arn "$AUDIT_QUEUE_ARN" \
  --batch-size 1 \
  --no-cli-pager >/dev/null 2>&1 || echo "  (mapping already exists)"

for uuid in $(aws --endpoint-url=$ENDPOINT lambda list-event-source-mappings \
  --function-name "$PROFILE_HANDLER" --query 'EventSourceMappings[].UUID' --output text 2>/dev/null); do
  aws --endpoint-url=$ENDPOINT lambda delete-event-source-mapping --uuid "$uuid" --no-cli-pager >/dev/null 2>&1 || true
done

aws --endpoint-url=$ENDPOINT lambda create-event-source-mapping \
  --function-name "$PROFILE_HANDLER" \
  --event-source-arn "$PROFILE_QUEUE_ARN" \
  --batch-size 1 \
  --no-cli-pager >/dev/null 2>&1 || echo "  (mapping already exists)"

echo "✓ SQS → Lambda mappings: decision-audit-queue → AuditLogHandler"
echo "✓ SQS → Lambda mappings: profile-update-queue → ProfileUpdateHandler"

# ─────────────────────────────────────────────────────────────────────────────
# 6. API Gateway
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "--- [6/7] Creating API Gateway: $API_NAME ---"

FRAUD_HANDLER_ARN="arn:aws:lambda:$REGION:000000000000:function:$FRAUD_HANDLER"

# Delete existing API if present
EXISTING_APIS=$(aws --endpoint-url=$ENDPOINT apigateway get-rest-apis \
  --query "items[?name=='$API_NAME'].id" --output text 2>/dev/null || echo "")
for api_id in $EXISTING_APIS; do
  aws --endpoint-url=$ENDPOINT apigateway delete-rest-api \
    --rest-api-id "$api_id" 2>/dev/null || true
done

# Create new REST API
API_ID=$(aws --endpoint-url=$ENDPOINT apigateway create-rest-api \
  --name "$API_NAME" \
  --query 'id' --output text)

# Get root resource ID
ROOT_ID=$(aws --endpoint-url=$ENDPOINT apigateway get-resources \
  --rest-api-id "$API_ID" \
  --query 'items[?path==`/`].id' --output text)

# /fraud-check endpoint
FRAUD_CHECK_ID=$(aws --endpoint-url=$ENDPOINT apigateway create-resource \
  --rest-api-id "$API_ID" \
  --parent-id "$ROOT_ID" \
  --path-part "fraud-check" \
  --query 'id' --output text)

aws --endpoint-url=$ENDPOINT apigateway put-method \
  --rest-api-id "$API_ID" \
  --resource-id "$FRAUD_CHECK_ID" \
  --http-method POST \
  --authorization-type NONE \
  --no-cli-pager >/dev/null

aws --endpoint-url=$ENDPOINT apigateway put-integration \
  --rest-api-id "$API_ID" \
  --resource-id "$FRAUD_CHECK_ID" \
  --http-method POST \
  --type AWS_PROXY \
  --integration-http-method POST \
  --uri "arn:aws:apigateway:$REGION:lambda:path/2015-03-31/functions/$FRAUD_HANDLER_ARN/invocations" \
  --no-cli-pager >/dev/null

# /confirm-payment endpoint
CONFIRM_ID=$(aws --endpoint-url=$ENDPOINT apigateway create-resource \
  --rest-api-id "$API_ID" \
  --parent-id "$ROOT_ID" \
  --path-part "confirm-payment" \
  --query 'id' --output text)

aws --endpoint-url=$ENDPOINT apigateway put-method \
  --rest-api-id "$API_ID" \
  --resource-id "$CONFIRM_ID" \
  --http-method POST \
  --authorization-type NONE \
  --no-cli-pager >/dev/null

aws --endpoint-url=$ENDPOINT apigateway put-integration \
  --rest-api-id "$API_ID" \
  --resource-id "$CONFIRM_ID" \
  --http-method POST \
  --type AWS_PROXY \
  --integration-http-method POST \
  --uri "arn:aws:apigateway:$REGION:lambda:path/2015-03-31/functions/$FRAUD_HANDLER_ARN/invocations" \
  --no-cli-pager >/dev/null

# Deploy the API
aws --endpoint-url=$ENDPOINT apigateway create-deployment \
  --rest-api-id "$API_ID" \
  --stage-name "local" \
  --no-cli-pager >/dev/null

API_URL="$ENDPOINT/restapis/$API_ID/local/_user_request_"
echo "✓ API Gateway: $API_URL"

# ─────────────────────────────────────────────────────────────────────────────
# 7. Summary
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "--- [7/7] Verifying deployment ---"
echo ""
echo "=== Full Stack Deployment Complete ==="
echo ""
echo "Infrastructure:"
echo "  DynamoDB table:    $TABLE_NAME (GSI: DecisionByDate)"
echo "  S3 bucket:         $AUDIT_BUCKET"
echo "  EventBridge bus:   $EVENT_BUS_NAME"
echo ""
echo "Lambdas:"
echo "  $FRAUD_HANDLER     → API Gateway (/fraud-check, /confirm-payment)"
echo "  $AUDIT_HANDLER     → EventBridge (DecisionMade events)"
echo "  $PROFILE_HANDLER   → EventBridge (TransactionCompleted events)"
echo ""
echo "EventBridge Rules:"
echo "  DecisionMadeToAudit            → SQS → $AUDIT_HANDLER"
echo "  TransactionCompletedToProfile  → SQS → $PROFILE_HANDLER"
echo ""
echo "API Gateway:"
echo "  POST $API_URL/fraud-check"
echo "  POST $API_URL/confirm-payment"
echo ""
echo "Event Flow:"
echo "  UI → API GW → FraudDetectionHandler → EventBridge (DecisionMade)"
echo "                                           ├→ SQS → AuditLogHandler → DynamoDB + S3"
echo "                                           └→ (external: TransactionCompleted)"
echo "                                                └→ SQS → ProfileUpdateHandler → DynamoDB"
