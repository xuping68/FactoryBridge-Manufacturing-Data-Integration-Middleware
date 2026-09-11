"""真實 HTTP + 檔案 SQLite 測試；驗證重新啟動、併發和不同 payload 的去重行為。"""

import json
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from simulator import DeliveryLedger, SimulatorServer


class SimulatorContractTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.database_path = Path(self.directory.name) / "deliveries.sqlite3"
        self.start_server()

    def tearDown(self):
        self.stop_server()
        self.directory.cleanup()

    def start_server(self):
        self.server = SimulatorServer(("127.0.0.1", 0), DeliveryLedger(self.database_path))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = "http://127.0.0.1:" + str(self.server.server_port)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def request(self, method, path, payload=None, key=None):
        headers = {"Content-Type": "application/json", "X-Correlation-Id": "simulator-test"}
        if key:
            headers["Idempotency-Key"] = key
        body = json.dumps(payload).encode("utf-8") if payload is not None else None
        request = Request(self.base_url + path, data=body, headers=headers, method=method)
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except HTTPError as response:
            return response.code, json.load(response)

    def payload(self, key):
        return {"schemaVersion": 1, "messageId": key, "measurementId": str(uuid4()),
                "asset": {"equipmentId": "EQ-1"}, "measurement": {"value": 35.2, "unit": "C"},
                "traceability": {"sourceRecordId": "r1"}}

    def test_source_fixture_preserves_external_contract(self):
        status, fixture = self.request("GET", "/measurements/MES_A/MES-A-20260910-000001")
        self.assertEqual(200, status)
        self.assertEqual("95.36", fixture["value"])
        self.assertEqual("F", fixture["unit"])
        self.assertEqual("2026/09/10 14:30:22", fixture["eventTime"])
        self.assertEqual(404, self.request("GET", "/measurements/MES_A/missing")[0])

    def test_idempotency_survives_server_restart(self):
        key = str(uuid4())
        payload = self.payload(key)
        self.assertEqual(201, self.request("POST", "/api/measurements", payload, key)[0])
        self.stop_server()
        self.start_server()

        status, response = self.request("POST", "/api/measurements", payload, key)
        self.assertEqual(200, status)
        self.assertTrue(response["duplicate"])
        self.assertEqual(1, self.request("GET", "/admin/deliveries")[1]["count"])

    def test_same_key_with_changed_payload_is_rejected(self):
        key = str(uuid4())
        payload = self.payload(key)
        self.request("POST", "/api/measurements", payload, key)
        payload["measurement"]["value"] = 36.0

        status, response = self.request("POST", "/api/measurements", payload, key)
        self.assertEqual(409, status)
        self.assertEqual("IDEMPOTENCY_KEY_CONFLICT", response["errorCode"])
        self.assertEqual(1, self.request("GET", "/admin/deliveries")[1]["count"])

    def test_parallel_deliveries_commit_one_business_effect(self):
        key = str(uuid4())
        payload = self.payload(key)
        with ThreadPoolExecutor(max_workers=8) as executor:
            responses = list(executor.map(
                lambda _: self.request("POST", "/api/measurements", payload, key), range(8)))

        self.assertEqual(1, sum(status == 201 for status, _ in responses))
        self.assertEqual(7, sum(status == 200 for status, _ in responses))
        self.assertEqual(1, self.request("GET", "/admin/deliveries")[1]["count"])

    def test_outage_recovery_does_not_record_failed_delivery(self):
        key = str(uuid4())
        payload = self.payload(key)
        self.assertEqual(200, self.request("PUT", "/admin/failure-mode", {"enabled": True})[0])
        self.assertEqual(503, self.request("POST", "/api/measurements", payload, key)[0])
        self.assertEqual(0, self.request("GET", "/admin/deliveries")[1]["count"])
        self.request("PUT", "/admin/failure-mode", {"enabled": False})
        self.assertEqual(201, self.request("POST", "/api/measurements", payload, key)[0])

    def test_message_identifier_must_match_idempotency_key(self):
        key = str(uuid4())
        payload = self.payload(str(uuid4()))
        self.assertEqual(422, self.request("POST", "/api/measurements", payload, key)[0])
        self.assertEqual(0, self.request("GET", "/admin/deliveries")[1]["count"])

    def test_schema_version_must_not_coerce_boolean_to_integer(self):
        key = str(uuid4())
        payload = self.payload(key)
        payload["schemaVersion"] = True
        self.assertEqual(422, self.request("POST", "/api/measurements", payload, key)[0])

    def test_non_finite_measurement_is_not_valid_json(self):
        key = str(uuid4())
        payload = self.payload(key)
        payload["measurement"]["value"] = float("nan")
        self.assertEqual(400, self.request("POST", "/api/measurements", payload, key)[0])


if __name__ == "__main__":
    unittest.main()
