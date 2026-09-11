"""可重啟的 MES／下游模擬器，僅用 Python 標準函式庫。

SQLite transaction 將冪等鍵與模擬業務副作用一起提交，展示「下游已成功但回覆遺失」
時需要的接收端責任。它是 localhost demo fixture，不是正式 MES 或公開管理 API。
"""

import hashlib
import json
import os
import sqlite3
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit
from uuid import UUID


MAX_PAYLOAD_BYTES = 64 * 1024
FIXTURE_DIRECTORY = Path(__file__).parent / "fixtures"


class DeliveryLedger:
    """每次操作使用獨立連線，以資料庫唯一鍵處理平行請求與服務重啟。"""

    def __init__(self, database_path):
        self.database_path = str(database_path)
        Path(self.database_path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("""
                CREATE TABLE IF NOT EXISTS deliveries (
                    idempotency_key TEXT PRIMARY KEY,
                    payload_hash TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    correlation_id TEXT NOT NULL,
                    received_at TEXT NOT NULL
                )
            """)
            connection.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    name TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """)
            connection.execute("INSERT OR IGNORE INTO settings VALUES ('failure_mode', 'false')")

    def connect(self):
        connection = sqlite3.connect(self.database_path, timeout=5)
        connection.row_factory = sqlite3.Row
        return connection

    def accept_delivery(self, idempotency_key, payload_bytes, correlation_id):
        # 對完整 wire bytes 算摘要，不讓未知欄位或高精度數值變動被忽略。
        payload_hash = hashlib.sha256(payload_bytes).hexdigest()
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            failure_mode = connection.execute(
                "SELECT value FROM settings WHERE name = 'failure_mode'"
            ).fetchone()["value"]
            if failure_mode == "true":
                return 503, {"errorCode": "SIMULATED_DOWNSTREAM_OUTAGE"}

            existing = connection.execute(
                "SELECT payload_hash FROM deliveries WHERE idempotency_key = ?", (idempotency_key,)
            ).fetchone()
            if existing:
                if existing["payload_hash"] != payload_hash:
                    return 409, {"errorCode": "IDEMPOTENCY_KEY_CONFLICT"}
                return 200, {"accepted": True, "duplicate": True, "messageId": idempotency_key}

            connection.execute(
                "INSERT INTO deliveries VALUES (?, ?, ?, ?, ?)",
                (idempotency_key, payload_hash, payload_bytes.decode("utf-8"), correlation_id,
                 datetime.now(timezone.utc).isoformat()),
            )
        return 201, {"accepted": True, "duplicate": False, "messageId": idempotency_key}

    def list_deliveries(self):
        with self.connect() as connection:
            count = connection.execute("SELECT COUNT(*) FROM deliveries").fetchone()[0]
            rows = connection.execute(
                "SELECT * FROM deliveries ORDER BY received_at DESC LIMIT 100"
            ).fetchall()
        return {"count": count, "deliveries": [
            {"idempotencyKey": row["idempotency_key"], "correlationId": row["correlation_id"],
             "receivedAt": row["received_at"], "payload": json.loads(row["payload"])} for row in rows
        ]}

    def set_failure_mode(self, enabled):
        with self.connect() as connection:
            connection.execute(
                "UPDATE settings SET value = ? WHERE name = 'failure_mode'",
                ("true" if enabled else "false",),
            )


def load_source_records():
    records = {}
    for fixture_path in sorted(FIXTURE_DIRECTORY.glob("*.json")):
        fixture_bytes = fixture_path.read_bytes()
        payload = json.loads(fixture_bytes)
        records[(payload["sourceSystem"], payload["sourceRecordId"])] = fixture_bytes
    return records


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON keys are not supported.")
        result[key] = value
    return result


def reject_non_finite_number(value):
    raise ValueError("Non-finite number is not a JSON measurement value.")


class SimulatorServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, ledger):
        self.ledger = ledger
        self.source_records = load_source_records()
        super().__init__(address, SimulatorRequestHandler)


class SimulatorRequestHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/health":
            self.send_json(200, {"status": "UP"})
            return
        if path == "/admin/deliveries":
            self.send_json(200, self.server.ledger.list_deliveries())
            return

        segments = path.split("/")
        if len(segments) == 4 and segments[1] == "measurements":
            key = (unquote(segments[2]), unquote(segments[3]))
            fixture = self.server.source_records.get(key)
            if fixture is not None:
                self.send_bytes(200, fixture)
                return
        self.send_json(404, {"errorCode": "RECORD_NOT_FOUND"})

    def do_POST(self):
        if urlsplit(self.path).path != "/api/measurements":
            self.send_json(404, {"errorCode": "RECORD_NOT_FOUND"})
            return
        request = self.read_json_body()
        if request is None:
            return
        payload_bytes, payload = request
        idempotency_key = self.headers.get("Idempotency-Key", "")
        correlation_id = self.headers.get("X-Correlation-Id", "")
        try:
            UUID(idempotency_key)
            UUID(payload.get("measurementId", ""))
            if type(payload.get("schemaVersion")) is not int or payload["schemaVersion"] != 1:
                raise ValueError("Only integer schema version 1 is supported.")
            if payload.get("messageId") != idempotency_key:
                raise ValueError("Version or message identifier is inconsistent.")
            if not all(isinstance(payload.get(field), dict) for field in ("asset", "measurement", "traceability")):
                raise ValueError("Required contract objects are missing.")
            if not correlation_id or len(correlation_id) > 128:
                raise ValueError("Correlation identifier is missing or too long.")
        except (ValueError, TypeError, AttributeError):
            self.send_json(422, {"errorCode": "INVALID_DOWNSTREAM_CONTRACT"})
            return

        status, response = self.server.ledger.accept_delivery(idempotency_key, payload_bytes, correlation_id)
        self.send_json(status, response)

    def do_PUT(self):
        if urlsplit(self.path).path != "/admin/failure-mode":
            self.send_json(404, {"errorCode": "RECORD_NOT_FOUND"})
            return
        request = self.read_json_body()
        if request is None:
            return
        _, payload = request
        if set(payload) != {"enabled"} or not isinstance(payload["enabled"], bool):
            self.send_json(400, {"errorCode": "INVALID_FAILURE_MODE"})
            return
        self.server.ledger.set_failure_mode(payload["enabled"])
        self.send_json(200, payload)

    def read_json_body(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > MAX_PAYLOAD_BYTES:
                self.send_json(413, {"errorCode": "INVALID_PAYLOAD_SIZE"})
                return None
            payload_bytes = self.rfile.read(length)
            payload = json.loads(payload_bytes.decode("utf-8"), object_pairs_hook=reject_duplicate_keys,
                                 parse_constant=reject_non_finite_number)
            if not isinstance(payload, dict):
                raise ValueError("A JSON object is required.")
            return payload_bytes, payload
        except (ValueError, UnicodeDecodeError):
            self.send_json(400, {"errorCode": "INVALID_PAYLOAD"})
            return None

    def send_json(self, status, payload):
        self.send_bytes(status, json.dumps(payload, ensure_ascii=False).encode("utf-8"))

    def send_bytes(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, message, *arguments):
        # 預設存取日誌只記路徑與狀態，不把量測內容或 operatorId 寫入 log。
        print("simulator " + message % arguments, flush=True)


if __name__ == "__main__":
    ledger = DeliveryLedger(os.environ.get("SIMULATOR_DATABASE_PATH", "/data/deliveries.sqlite3"))
    port = int(os.environ.get("SIMULATOR_PORT", "8080"))
    server = SimulatorServer(("0.0.0.0", port), ledger)
    print(f"FactoryBridge simulator listening on {port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
