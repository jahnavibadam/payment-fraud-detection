#!/bin/bash
###############################################################################
# Payment Fraud Detection - Stop Local Environment
#
# Stops the LocalStack container and any running dev server.
###############################################################################

CONTAINER_NAME="localstack"
DEV_SERVER_PORT=8080

echo "Stopping local environment..."

# Stop the dev server (kill process on port 8080)
DEV_PID=$(lsof -t -i :$DEV_SERVER_PORT 2>/dev/null || true)
if [ -n "$DEV_PID" ]; then
  kill "$DEV_PID" 2>/dev/null || true
  echo "✓ Dev server stopped (PID: $DEV_PID)"
else
  echo "  Dev server not running"
fi

# Stop LocalStack container
if sudo docker ps -q --filter "name=$CONTAINER_NAME" | grep -q . 2>/dev/null; then
  sudo docker stop "$CONTAINER_NAME" >/dev/null
  echo "✓ LocalStack container stopped"
else
  echo "  LocalStack container not running"
fi

echo ""
echo "Local environment stopped."
