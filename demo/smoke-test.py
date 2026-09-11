#!/usr/bin/env python3
"""對執行中的 FactoryBridge 驗證完整業務契約；不重設資料庫，可重複執行。

每筆 POST 使用本次唯一 sourceRecordId；唯獨 MES pull 使用固定 fixture，接受首次
202 或後續冪等 200。故障模式是此本機模擬器的全域開關，結束時一定嘗試關閉。
"""

import argparse
import json
import sys
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4


class SmokeChecks:
    def __init__(self, api_url, simulator_url, timeout):
        self.api_url = api_url.rstrip("/")
        self.simulator_url = simulator_url.rstrip("/")
        self.timeout = timeout
        self.run_id = uuid4().hex[:16]
        self.correlation_id = "smoke-" + self.run_id
        self.fixture = json.loads((Path(__file__).parent / "fixtures" / "measurement.json").read_text())

    def request_json(self, method, path, payload=None, expected=(200,), simulator=False):
        base_url = self.simulator_url if simulator else self.api_url
        headers = {"Content-Type": "application/json", "X-Correlation-Id": self.correlation_id}
        data = json.dumps(payload).encode("utf-8") if payload is not None else None
        request = Request(base_url + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=10) as response:
                status, raw = response.status, response.read()
        except HTTPError as response:
            status, raw = response.code, response.read()
        body = json.loads(raw) if raw else None
        self.require(status in expected, f"{method} {path}: expected {expected}, received HTTP {status}: {body}")
        return body

    @staticmethod
    def require(condition, message):
        if not condition:
            raise AssertionError(message)

    def wait_until(self, description, observation):
        deadline = time.monotonic() + self.timeout
        last_observation = None
        while time.monotonic() < deadline:
            passed, last_observation = observation()
            if passed:
                return last_observation
            time.sleep(0.25)
        raise AssertionError(f"Timed out waiting for {description}. Last observation: {last_observation}")

    def unique_measurement(self, scenario, **changes):
        return dict(self.fixture, sourceRecordId=f"SMOKE-{self.run_id}-{scenario}", **changes)

    def ingest(self, payload, expected=(202,)):
        return self.request_json("POST", "/api/v1/measurements", payload, expected)

    def deliveries(self, measurement_id):
        return self.request_json("GET", f"/api/v1/measurements/{measurement_id}/deliveries")

    def wait_for_delivered(self, measurement_id, expected_destinations):
        def observation():
            deliveries = self.deliveries(measurement_id)
            destinations = {delivery["destination"] for delivery in deliveries}
            passed = destinations == set(expected_destinations) and all(
                delivery["status"] == "DELIVERED" for delivery in deliveries)
            return passed, deliveries

        return self.wait_until("durable deliveries for " + measurement_id, observation)

    def wait_for_downstream_state(self, measurement_id, expected_status):
        def observation():
            deliveries = self.deliveries(measurement_id)
            downstream = next((item for item in deliveries if item["destination"] == "DOWNSTREAM"), None)
            return downstream is not None and downstream["status"] == expected_status, downstream

        return self.wait_until("downstream " + expected_status, observation)

    def set_outage(self, enabled):
        self.request_json("PUT", "/admin/failure-mode", {"enabled": enabled}, simulator=True)

    def verify_normalization_and_idempotency(self):
        payload = self.unique_measurement("normal")
        accepted = self.ingest(payload)
        measurement_id = accepted["measurementId"]
        self.require(accepted["status"] == "ACCEPTED", "New source key must be accepted.")
        self.require(accepted["correlationId"] == self.correlation_id, "Correlation ID must survive ingestion.")
        canonical = self.request_json("GET", f"/api/v1/measurements/{measurement_id}")
        self.require(canonical["numericValue"] == 35.2, "95.36°F must become 35.2°C.")
        self.require(canonical["standardUnit"] == "C", "Canonical temperature unit must be C.")
        self.require(canonical["measuredAt"] == "2026-09-10T06:30:22Z", "MES_A local time must become UTC.")
        self.require(canonical["qualityStatus"] == "GOOD", "External OK must become canonical GOOD.")

        duplicate = self.ingest(dict(reversed(list(payload.items()))), expected=(200,))
        self.require(duplicate["measurementId"] == measurement_id, "Reordered JSON must reuse canonical identity.")
        self.require(duplicate["status"] == "DUPLICATE", "Replay must be explicit in response.")
        conflict = self.ingest(dict(payload, value="100"), expected=(409,))
        self.require(conflict["errorCode"] == "DUPLICATE_SOURCE_RECORD", "Conflicting source key must be rejected.")
        replayed = self.request_json("POST", f"/api/v1/staging/{accepted['stagingId']}/replays", expected=(200,))
        self.require(replayed["measurementId"] == measurement_id, "Staging replay must not duplicate canonical data.")
        self.require(replayed["stagingId"] != accepted["stagingId"], "Replay must preserve the original staging audit.")

        deliveries = self.wait_for_delivered(measurement_id, {"DATA_WAREHOUSE", "DOWNSTREAM"})
        downstream = next(item for item in deliveries if item["destination"] == "DOWNSTREAM")
        self.verify_downstream_effect(measurement_id, downstream["id"])
        forbidden = self.request_json("POST", f"/api/v1/deliveries/{downstream['id']}/replays", expected=(409,))
        self.require(forbidden["errorCode"] == "DELIVERY_NOT_REPLAYABLE", "Successful delivery must not be replayed.")
        print("PASS normalization, UTC time, stable source identity, conflict, staging replay, both destinations")

    def verify_downstream_effect(self, measurement_id, delivery_id):
        ledger = self.request_json("GET", "/admin/deliveries", simulator=True)
        matching = [entry for entry in ledger["deliveries"] if entry["payload"]["measurementId"] == measurement_id]
        self.require(len(matching) == 1, "Downstream must contain exactly one visible effect for the measurement.")
        entry = matching[0]
        self.require(entry["idempotencyKey"] == delivery_id, "Downstream key must equal the persistent delivery ID.")
        self.require(entry["payload"]["messageId"] == delivery_id, "Versioned message identity must match the header.")
        self.require(entry["payload"]["schemaVersion"] == 1, "Downstream schema must have an explicit version.")
        self.require(entry["payload"]["measurement"]["value"] == 35.2, "Downstream must receive canonical temperature.")

    def verify_rejected_data_remains_auditable(self):
        invalid = self.unique_measurement("invalid-unit", unit="psi")
        error = self.ingest(invalid, expected=(422,))
        self.require(error["errorCode"] == "UNSUPPORTED_UNIT", "Wrong metric unit must have a semantic error code.")
        self.require(error["category"] == "VALIDATION", "Wrong metric unit must be classified as validation.")
        self.require(error["correlationId"] == self.correlation_id, "Error must preserve correlation identity.")
        staging = self.request_json("GET", f"/api/v1/staging/{error['stagingId']}")
        self.require(staging["status"] == "REJECTED", "Invalid source payload must remain as rejected staging.")
        self.require(json.loads(staging["rawPayload"]) == invalid, "Rejected raw payload must remain unchanged.")
        replay = self.request_json("POST", f"/api/v1/staging/{error['stagingId']}/replays", expected=(422,))
        self.require(replay["stagingId"] != error["stagingId"], "Failed replay must create a new audit record.")
        self.require(replay["errorCode"] == "UNSUPPORTED_UNIT", "Replay must not bypass validation.")
        corrected = self.ingest(dict(invalid, unit="F"))
        self.wait_for_delivered(corrected["measurementId"], {"DATA_WAREHOUSE", "DOWNSTREAM"})
        print("PASS semantic validation error, immutable raw audit, failed replay, corrected source resubmission")

    def verify_quality_routing(self):
        accepted = self.ingest(self.unique_measurement("warning", qualityStatus="WARN"))
        self.wait_for_delivered(accepted["measurementId"], {"DATA_WAREHOUSE"})
        canonical = self.request_json("GET", f"/api/v1/measurements/{accepted['measurementId']}")
        self.require(canonical["qualityStatus"] == "WARNING", "WARN must map to WARNING.")
        print("PASS WARNING quality routes to warehouse only")

    def verify_mes_pull(self):
        imported = self.request_json("POST", "/api/v1/imports",
                {"sourceSystem": "MES_A", "sourceRecordId": self.fixture["sourceRecordId"]}, expected=(200, 202))
        canonical = self.request_json("GET", f"/api/v1/measurements/{imported['measurementId']}")
        self.require(canonical["sourceRecordId"] == self.fixture["sourceRecordId"], "Fetched source identity must match.")
        self.require(canonical["numericValue"] == 35.2, "MES pull must use the same normalization rules.")
        self.wait_for_delivered(imported["measurementId"], {"DATA_WAREHOUSE", "DOWNSTREAM"})
        print("PASS MES HTTP pull uses the shared ingestion flow")

    def verify_retry_and_recovery(self):
        self.set_outage(True)
        try:
            accepted = self.ingest(self.unique_measurement("retry"))
            retry = self.wait_for_downstream_state(accepted["measurementId"], "RETRY")
            self.require(retry["lastErrorCode"] == "DOWNSTREAM_UNAVAILABLE", "503 must be classified as retryable.")
            self.require(retry["totalAttempts"] >= 1, "Failure must persist attempt count.")
        finally:
            self.set_outage(False)
        delivered = self.wait_for_delivered(accepted["measurementId"], {"DATA_WAREHOUSE", "DOWNSTREAM"})
        downstream = next(item for item in delivered if item["destination"] == "DOWNSTREAM")
        self.require(downstream["id"] == retry["id"], "Retry must retain the same delivery ID.")
        self.require(downstream["totalAttempts"] >= 2, "Recovery must use a persisted retry attempt.")
        self.verify_downstream_effect(accepted["measurementId"], downstream["id"])
        print("PASS downstream 503 persists RETRY, recovers automatically, and keeps stable delivery identity")

    def verify_dead_delivery_replay(self):
        self.set_outage(True)
        try:
            accepted = self.ingest(self.unique_measurement("dead-replay"))
            dead = self.wait_for_downstream_state(accepted["measurementId"], "DEAD")
            self.require(dead["lastErrorCode"] == "DOWNSTREAM_UNAVAILABLE", "Exhausted delivery must preserve root error.")
        finally:
            self.set_outage(False)
        replayed = self.request_json("POST", f"/api/v1/deliveries/{dead['id']}/replays", expected=(202,))
        self.require(replayed["id"] == dead["id"], "Manual replay must retain the idempotency key.")
        self.require(replayed["replayCount"] == dead["replayCount"] + 1, "Replay must remain auditable.")
        delivered = self.wait_for_delivered(accepted["measurementId"], {"DATA_WAREHOUSE", "DOWNSTREAM"})
        downstream = next(item for item in delivered if item["destination"] == "DOWNSTREAM")
        self.require(downstream["totalAttempts"] > dead["totalAttempts"], "Manual replay must preserve lifetime attempts.")
        self.verify_downstream_effect(accepted["measurementId"], downstream["id"])
        print("PASS retries exhaust to DEAD; manual replay succeeds with the same durable idempotency key")

    def run(self, skip_dead_replay):
        self.request_json("GET", "/actuator/health/readiness")
        self.request_json("GET", "/health", simulator=True)
        self.set_outage(False)
        try:
            self.verify_normalization_and_idempotency()
            self.verify_rejected_data_remains_auditable()
            self.verify_quality_routing()
            self.verify_mes_pull()
            self.verify_retry_and_recovery()
            if not skip_dead_replay:
                self.verify_dead_delivery_replay()
        finally:
            self.set_outage(False)
        print(f"All smoke checks passed. correlationId={self.correlation_id}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-url", default="http://localhost:8080")
    parser.add_argument("--simulator-url", default="http://localhost:8090")
    parser.add_argument("--timeout", type=float, default=90, help="每次輪詢上限秒數，預設含完整重試週期")
    parser.add_argument("--skip-dead-replay", action="store_true", help="略過約 40 秒的 retry 耗盡與人工重跑展示")
    arguments = parser.parse_args()
    if arguments.timeout <= 0:
        parser.error("--timeout must be positive")
    try:
        SmokeChecks(arguments.api_url, arguments.simulator_url, arguments.timeout).run(arguments.skip_dead_replay)
    except (AssertionError, URLError, OSError, ValueError, KeyError) as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
