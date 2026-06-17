#!/usr/bin/env python3
"""
Local development server that serves the frontend files and proxies API requests
to LocalStack, handling CORS properly.

Usage: python3 localstack/serve.py
Frontend: http://localhost:8080
API proxy: http://localhost:8080/fraud-check -> LocalStack
"""
import http.server
import json
import os
import sys
import urllib.request
import urllib.error

FRONTEND_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "frontend")
LOCALSTACK_API_URL = os.environ.get("LOCALSTACK_API_URL", "")
PORT = int(os.environ.get("PORT", "8080"))

# Auto-detect the LocalStack API URL if not set
if not LOCALSTACK_API_URL:
    import subprocess
    try:
        result = subprocess.run(
            ["aws", "--endpoint-url=http://localhost:4566", "apigateway", "get-rest-apis",
             "--query", "items[0].id", "--output", "text", "--region", "us-east-1"],
            capture_output=True, text=True,
            env={**os.environ, "AWS_ACCESS_KEY_ID": "test", "AWS_SECRET_ACCESS_KEY": "test"}
        )
        api_id = result.stdout.strip()
        if api_id and api_id != "None":
            LOCALSTACK_API_URL = f"http://localhost:4566/restapis/{api_id}/local/_user_request_"
            print(f"Auto-detected LocalStack API: {LOCALSTACK_API_URL}")
        else:
            print("WARNING: Could not detect LocalStack API. API proxying will not work.")
            LOCALSTACK_API_URL = ""
    except Exception as e:
        print(f"WARNING: Could not detect LocalStack API: {e}")
        LOCALSTACK_API_URL = ""


class DevHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=FRONTEND_DIR, **kwargs)

    def do_OPTIONS(self):
        """Handle CORS preflight."""
        self.send_response(200)
        self._add_cors_headers()
        self.end_headers()

    def do_POST(self):
        """Proxy POST requests to LocalStack API."""
        if self.path == "/fraud-check":
            self._proxy_to_localstack()
        elif self.path == "/confirm-payment":
            self._handle_confirm_payment()
        else:
            self.send_error(404)

    def _handle_confirm_payment(self):
        """Stub for confirm-payment — backend doesn't implement this yet.
        Returns a success response so the UI flow works end-to-end."""
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length > 0 else b""
        try:
            data = json.loads(body) if body else {}
        except json.JSONDecodeError:
            data = {}
        
        response = json.dumps({
            "messageId": data.get("messageId", "unknown"),
            "status": "CONFIRMED",
            "message": "Payment confirmed and submitted for processing"
        })
        self.send_response(200)
        self._add_cors_headers()
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(response.encode())

    def _proxy_to_localstack(self):
        if not LOCALSTACK_API_URL:
            self.send_response(503)
            self._add_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"error": "LocalStack API not available"}).encode())
            return

        # Read request body
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length) if content_length > 0 else b""

        # Forward to LocalStack
        target_url = LOCALSTACK_API_URL + self.path
        req = urllib.request.Request(
            target_url,
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST"
        )

        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                response_body = resp.read()
                self.send_response(resp.status)
                self._add_cors_headers()
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(response_body)

                # After successful fraud-check, simulate EventBridge delivery
                # by directly invoking the AuditLogHandler Lambda.
                # (LocalStack CE doesn't reliably deliver EventBridge→Lambda/SQS)
                if self.path == "/fraud-check" and resp.status == 200:
                    self._trigger_audit_handler(body, response_body)

        except urllib.error.HTTPError as e:
            response_body = e.read()
            self.send_response(e.code)
            self._add_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(response_body)
        except urllib.error.URLError as e:
            self.send_response(502)
            self._add_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"error": f"Backend unavailable: {e.reason}"}).encode())
        except Exception as e:
            self.send_response(500)
            self._add_cors_headers()
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode())

    def _trigger_audit_handler(self, request_body, response_body):
        """Simulate the AuditLogHandler by writing the decision audit record
        directly to DynamoDB. This works around LocalStack CE's limitations
        with EventBridge targets and Lambda credential propagation."""
        import threading
        def _write_audit():
            try:
                request_data = json.loads(request_body)
                response_data = json.loads(response_body)
                message_id = response_data.get("messageId", "")
                decision = response_data.get("decision", "")
                timestamp = response_data.get("timestamp", "")
                risk_score = response_data.get("riskScore", 0)
                breakdown = response_data.get("breakdown", {})
                risk_factors = response_data.get("riskFactors", [])

                debtor = request_data.get("debtorAccount", {})
                creditor = request_data.get("creditorAccount", {})

                # Build DynamoDB item matching DecisionAuditEntity schema
                item = {
                    "pk": {"S": f"AUDIT#{message_id}"},
                    "sk": {"S": "DECISION"},
                    "timestamp": {"S": timestamp},
                    "debtorAccount": {"S": f"{debtor.get('sortCode','')}#{debtor.get('accountNumber','')}"},
                    "creditorAccount": {"S": f"{creditor.get('sortCode','')}#{creditor.get('accountNumber','')}"},
                    "amount": {"N": str(request_data.get("amount", 0))},
                    "riskScore": {"N": str(risk_score)},
                    "decision": {"S": decision},
                    "amountScore": {"N": str(breakdown.get("amountScore", 0))},
                    "copScore": {"N": str(breakdown.get("copScore", 0))},
                    "behaviouralScore": {"N": str(breakdown.get("behaviouralScore", 0))},
                    "channelScore": {"N": str(breakdown.get("channelScore", 0))},
                    "gsiPk": {"S": f"DECISION#{decision}"},
                    "gsiSk": {"S": timestamp}
                }

                # Add risk factors and explanations as string lists
                if risk_factors:
                    item["riskFactors"] = {"L": [{"S": rf.get("category", "")} for rf in risk_factors]}
                    item["explanations"] = {"L": [{"S": rf.get("explanation", "")} for rf in risk_factors]}

                # Write to DynamoDB
                import subprocess
                result = subprocess.run(
                    ["aws", "--endpoint-url=http://localhost:4566", "--region", "us-east-1",
                     "dynamodb", "put-item",
                     "--table-name", "FraudDetection",
                     "--item", json.dumps(item)],
                    capture_output=True, text=True, timeout=10,
                    env={**os.environ, "AWS_ACCESS_KEY_ID": "test", "AWS_SECRET_ACCESS_KEY": "test"}
                )

                if result.returncode == 0:
                    sys.stderr.write(f"[Audit] ✓ Persisted decision for {message_id} ({decision})\n")
                else:
                    sys.stderr.write(f"[Audit] ✗ DynamoDB write failed: {result.stderr[:200]}\n")

                # Also write to S3 (archive)
                import tempfile
                archive_data = json.dumps({
                    "messageId": message_id,
                    "timestamp": timestamp,
                    "debtorAccount": debtor,
                    "creditorAccount": creditor,
                    "amount": request_data.get("amount", 0),
                    "riskScore": risk_score,
                    "decision": decision,
                    "breakdown": breakdown,
                    "riskFactors": risk_factors
                })

                # S3 key: decisions/{year}/{month}/{day}/{messageId}.json
                date_parts = timestamp[:10].split("-") if timestamp else ["2026", "01", "01"]
                s3_key = f"decisions/{date_parts[0]}/{date_parts[1]}/{date_parts[2]}/{message_id}.json"

                with tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False) as f:
                    f.write(archive_data)
                    tmp_path = f.name

                s3_result = subprocess.run(
                    ["aws", "--endpoint-url=http://localhost:4566", "--region", "us-east-1",
                     "s3api", "put-object",
                     "--bucket", "fraud-audit-archive",
                     "--key", s3_key,
                     "--body", tmp_path,
                     "--content-type", "application/json"],
                    capture_output=True, text=True, timeout=10,
                    env={**os.environ, "AWS_ACCESS_KEY_ID": "test", "AWS_SECRET_ACCESS_KEY": "test"}
                )
                os.unlink(tmp_path)

                if s3_result.returncode == 0:
                    sys.stderr.write(f"[Audit] ✓ Archived to S3: {s3_key}\n")

            except Exception as e:
                sys.stderr.write(f"[Audit] Error: {e}\n")

        # Run async so we don't block the HTTP response
        threading.Thread(target=_write_audit, daemon=True).start()

    def _add_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def log_message(self, format, *args):
        """Cleaner log output."""
        sys.stderr.write(f"[{self.log_date_time_string()}] {format % args}\n")


if __name__ == "__main__":
    server = http.server.HTTPServer(("0.0.0.0", PORT), DevHandler)
    print(f"\n{'='*50}")
    print(f"Payment Fraud UI - Local Development Server")
    print(f"{'='*50}")
    print(f"Frontend:  http://localhost:{PORT}")
    print(f"API Proxy: http://localhost:{PORT}/fraud-check")
    print(f"           http://localhost:{PORT}/confirm-payment")
    print(f"Backend:   {LOCALSTACK_API_URL or 'NOT CONFIGURED'}")
    print(f"{'='*50}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...")
        server.shutdown()
