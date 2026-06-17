#!/bin/bash
###############################################################################
# Seed DynamoDB with 100 test customer profiles and beneficiary records
#
# Creates a realistic test dataset with:
#   - 80 customer profiles (debtors) with varying transaction histories
#   - 20 beneficiary registry entries (some flagged HIGH_RISK or MULE_LINKED)
#
# Usage: ./localstack/seed-data.sh
###############################################################################
set -e

REGION="us-east-1"
ENDPOINT="http://localhost:4566"
TABLE_NAME="FraudDetection"

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=$REGION

echo "=== Seeding DynamoDB with test data ==="
echo ""

# Helper function to put a customer profile item
put_profile() {
  local sort_code=$1
  local account_number=$2
  local mean_amount=$3
  local std_dev=$4
  local tx_count=$5
  local avg_session=$6

  aws --endpoint-url=$ENDPOINT dynamodb put-item \
    --table-name "$TABLE_NAME" \
    --item "{
      \"pk\": {\"S\": \"CUST#${sort_code}#${account_number}\"},
      \"sk\": {\"S\": \"PROFILE\"},
      \"meanAmount\": {\"N\": \"${mean_amount}\"},
      \"stdDevAmount\": {\"N\": \"${std_dev}\"},
      \"transactionCount90d\": {\"N\": \"${tx_count}\"},
      \"devices\": {\"L\": [{\"S\": \"device-001\"}, {\"S\": \"device-002\"}]},
      \"locations\": {\"L\": [{\"S\": \"51.5074,-0.1278\"}, {\"S\": \"51.4545,-2.5879\"}]},
      \"avgSessionDuration\": {\"N\": \"${avg_session}\"},
      \"lastUpdated\": {\"S\": \"2026-06-01T10:00:00Z\"}
    }" --no-cli-pager 2>/dev/null
}

# Helper function to put a beneficiary registry item
put_beneficiary() {
  local sort_code=$1
  local account_number=$2
  local flag=$3
  local reason=$4

  aws --endpoint-url=$ENDPOINT dynamodb put-item \
    --table-name "$TABLE_NAME" \
    --item "{
      \"pk\": {\"S\": \"BENE#${sort_code}#${account_number}\"},
      \"sk\": {\"S\": \"STATUS\"},
      \"flag\": {\"S\": \"${flag}\"},
      \"lastUpdated\": {\"S\": \"2026-05-15T08:00:00Z\"},
      \"reason\": {\"S\": \"${reason}\"}
    }" --no-cli-pager 2>/dev/null
}

echo "--- Creating 80 customer profiles ---"

# Low-risk customers: regular spending patterns, high transaction count
# Sort codes 10-10-10 through 10-10-20 (accounts 10000001-10000020)
for i in $(seq 1 20); do
  acct=$(printf "%08d" $((10000000 + i)))
  mean=$((200 + RANDOM % 300))          # £200-£500 mean
  stddev=$((50 + RANDOM % 100))          # £50-£150 std dev
  txcount=$((80 + RANDOM % 120))         # 80-200 transactions
  session=$((120000 + RANDOM % 60000))   # 120-180s avg session
  put_profile "101010" "$acct" "$mean" "$stddev" "$txcount" "$session"
  echo -n "."
done
echo ""

# Medium-risk customers: moderate spending, fewer transactions
# Sort codes 20-20-20 (accounts 20000001-20000020)
for i in $(seq 1 20); do
  acct=$(printf "%08d" $((20000000 + i)))
  mean=$((500 + RANDOM % 1000))          # £500-£1500 mean
  stddev=$((200 + RANDOM % 300))         # £200-£500 std dev
  txcount=$((20 + RANDOM % 40))          # 20-60 transactions
  session=$((60000 + RANDOM % 60000))    # 60-120s avg session
  put_profile "202020" "$acct" "$mean" "$stddev" "$txcount" "$session"
  echo -n "."
done
echo ""

# High-value customers: large regular payments
# Sort codes 30-30-30 (accounts 30000001-30000020)
for i in $(seq 1 20); do
  acct=$(printf "%08d" $((30000000 + i)))
  mean=$((5000 + RANDOM % 10000))        # £5000-£15000 mean
  stddev=$((2000 + RANDOM % 3000))       # £2000-£5000 std dev
  txcount=$((50 + RANDOM % 100))         # 50-150 transactions
  session=$((180000 + RANDOM % 120000))  # 180-300s avg session
  put_profile "303030" "$acct" "$mean" "$stddev" "$txcount" "$session"
  echo -n "."
done
echo ""

# New/low-history customers: very few transactions
# Sort codes 40-40-40 (accounts 40000001-40000020)
for i in $(seq 1 20); do
  acct=$(printf "%08d" $((40000000 + i)))
  mean=$((100 + RANDOM % 200))           # £100-£300 mean
  stddev=$((30 + RANDOM % 70))           # £30-£100 std dev
  txcount=$((1 + RANDOM % 5))            # 1-5 transactions
  session=$((30000 + RANDOM % 30000))    # 30-60s avg session
  put_profile "404040" "$acct" "$mean" "$stddev" "$txcount" "$session"
  echo -n "."
done
echo ""

echo "✓ 80 customer profiles created"
echo ""
echo "--- Creating 20 beneficiary registry entries ---"

# Clean beneficiaries (NONE flag) — sort code 50-50-50
for i in $(seq 1 10); do
  acct=$(printf "%08d" $((50000000 + i)))
  put_beneficiary "505050" "$acct" "NONE" "No adverse information"
  echo -n "."
done
echo ""

# High-risk beneficiaries — sort code 60-60-60
for i in $(seq 1 6); do
  acct=$(printf "%08d" $((60000000 + i)))
  put_beneficiary "606060" "$acct" "HIGH_RISK" "Multiple fraud reports received"
  echo -n "."
done
echo ""

# Mule-linked beneficiaries — sort code 70-70-70
for i in $(seq 1 4); do
  acct=$(printf "%08d" $((70000000 + i)))
  put_beneficiary "707070" "$acct" "MULE_LINKED" "Linked to known money mule network"
  echo -n "."
done
echo ""

echo "✓ 20 beneficiary records created (10 NONE, 6 HIGH_RISK, 4 MULE_LINKED)"
echo ""

# Verify
ITEM_COUNT=$(aws --endpoint-url=$ENDPOINT dynamodb scan \
  --table-name "$TABLE_NAME" \
  --select COUNT \
  --query 'Count' --output text)

echo "=== Seed Complete: $ITEM_COUNT items in $TABLE_NAME ==="
echo ""
echo "Test accounts you can use in the UI:"
echo ""
echo "  DEBTOR (low-risk):     Sort Code: 10-10-10  Account: 10000001"
echo "  DEBTOR (medium-risk):  Sort Code: 20-20-20  Account: 20000001"
echo "  DEBTOR (high-value):   Sort Code: 30-30-30  Account: 30000001"
echo "  DEBTOR (new customer): Sort Code: 40-40-40  Account: 40000001"
echo ""
echo "  CREDITOR (clean):      Sort Code: 50-50-50  Account: 50000001"
echo "  CREDITOR (high-risk):  Sort Code: 60-60-60  Account: 60000001"
echo "  CREDITOR (mule):       Sort Code: 70-70-70  Account: 70000001"
echo ""
echo "Try combinations to see different fraud decisions:"
echo "  • Low-risk debtor + clean creditor + small amount → ALLOW"
echo "  • New customer + high-risk creditor + large amount → BLOCK"
echo "  • Medium-risk debtor + large amount → REVIEW"
