# FactoryBridge 面試展示操作

這個目錄提供可重現的製造場域資料、外部 MES／下游模擬器，以及對實際 API 執行的驗證腳本。重點是展示錯誤可追查、資料可重跑、來源與下游格式分離。

## 啟動與完整驗證

在專案根目錄執行，macOS Apple Silicon 使用 Docker Desktop 或其他可執行 Linux ARM64 container 的 Docker 環境：

```sh
docker compose up --build --wait
python3 demo/smoke-test.py
```

首次 image build 會下載 Maven 依賴並執行 unit／HTTP contract tests。完整 smoke test 約 1 分鐘，包含重試耗盡與人工重跑。腳本使用 Python 3 標準函式庫，不需要另外安裝套件；每次產生唯一 sourceRecordId，可保留資料重複執行。

| 服務 | 位址 | 用途 |
| --- | --- | --- |
| FactoryBridge | `http://localhost:8080` | 量測接收、MES pull、狀態查詢與重跑 |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` | API 契約與互動操作 |
| 模擬器 | `http://localhost:8090` | MES fixture、下游接收、故障開關 |
| PostgreSQL | `localhost:5432` | DB `factorybridge`，帳號 `factorybridge`，Demo 密碼 `factorybridge_demo` |

Compose 的 host port 僅綁定 loopback；Demo 密碼與未驗證身分的管理介面供本機展示。正式上線需改用秘密管理、身分驗證與操作授權。

## 腳本實際驗證哪些事情

1. `95.36°F → 35.2°C`，MES_A 的台灣時間 `2026/09/10 14:30:22 → 2026-09-10T06:30:22Z`。
2. 相同來源內容重送，即使 JSON 欄位順序不同，也取得同一 canonical ID；相同 source key 修改內容回覆 `409 DUPLICATE_SOURCE_RECORD`。
3. 錯誤單位回覆 `422 UNSUPPORTED_UNIT`、提供 correlationId 與 stagingId；原始 payload 保留在 REJECTED staging。
4. Staging replay 產生新的 audit attempt，仍需驗證；有效資料重跑不會新增 canonical。因單位錯誤而尚未產生 canonical 的來源資料，修正後可以用同一 source key 再提交。
5. GOOD 量測的 warehouse／downstream 兩條 delivery 最終為 DELIVERED；WARNING 只進 warehouse。
6. `POST /api/v1/imports` 真正向 MES 模擬器取得來源 JSON，再走相同驗證與標準化流程。
7. 下游回覆 503 時，資料保留為 RETRY，錯誤代碼與 attempts 可查；恢復後自動成功，delivery ID 不變。
8. 故障持續時，有限重試進入 DEAD；人工 replay 保留 idempotency key、累計 attempts 與 replayCount。
9. 透過下游 ledger 查到實際接收的 v1 JSON，以及每筆量測唯一的業務副作用。

只想快速驗證，可略過 retry 耗盡展示：

```sh
python3 demo/smoke-test.py --skip-dead-replay
```

不同位址使用 `--api-url`／`--simulator-url`；每個輪詢有預設 90 秒上限，可用 `--timeout` 調整。Assertion 失敗時回傳非零 exit code，不會印出假成功。

## 逐步展示

### 1. 正常接收與資料標準化

```sh
curl -i http://localhost:8080/api/v1/measurements \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: interview-normal' \
  --data-binary @demo/fixtures/measurement.json
```

`202` 表示 canonical 與待投遞事件已持久化，不表示下游已完成。再次提交同內容會是 `200 DUPLICATE`。將下列變數替換為回傳的 measurementId 後查詢：

```sh
measurement_id='REPLACE_WITH_MEASUREMENT_ID'
curl "http://localhost:8080/api/v1/measurements/${measurement_id}"
curl "http://localhost:8080/api/v1/measurements/${measurement_id}/deliveries"
curl http://localhost:8090/admin/deliveries
```

### 2. 錯誤不會讓 raw 消失

```sh
curl -i http://localhost:8080/api/v1/measurements \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: interview-invalid-unit' \
  --data-binary @demo/fixtures/invalid-unit.json
staging_id='REPLACE_WITH_ERROR_RESPONSE_STAGING_ID'
curl "http://localhost:8080/api/v1/staging/${staging_id}"
```

上列 staging_id 要替換為錯誤回應的 UUID。一般驗證失敗可重新處理 raw；若外部 MES 回傳另一筆識別而被隔離為 `EXTERNAL_RECORD_MISMATCH`，必須修復 MES 後重新 import，不能用 raw replay 繞過來源識別檢查。

### 3. 主動從 MES 取得資料

```sh
curl -i http://localhost:8080/api/v1/imports \
  -H 'Content-Type: application/json' \
  -d '{"sourceSystem":"MES_A","sourceRecordId":"MES-A-20260910-000001"}'
```

模擬器僅回傳 `fixtures/` 中已存在的 source identity，未知來源紀錄為 404。固定 fixture 若已接收過，這裡會得到正常的冪等回覆。

### 4. 故障、Retry、人工重跑

```sh
curl -X PUT http://localhost:8090/admin/failure-mode \
  -H 'Content-Type: application/json' -d '{"enabled":true}'
```

用新的 sourceRecordId 提交正常量測，查詢 deliveries，可以看見 warehouse 獨立成功，downstream 出現 `DOWNSTREAM_UNAVAILABLE` 與 RETRY。若持續故障至重試次數上限，downstream 進入 DEAD。

```sh
curl -X PUT http://localhost:8090/admin/failure-mode \
  -H 'Content-Type: application/json' -d '{"enabled":false}'
dead_delivery_id='REPLACE_WITH_DEAD_DELIVERY_ID'
curl -i -X POST "http://localhost:8080/api/v1/deliveries/${dead_delivery_id}/replays"
```

將 dead_delivery_id 替換為實際 DEAD delivery UUID。仍在 RETRY 的紀錄會自動恢復，不需要人工 replay；已成功的 delivery 不允許重跑。完整腳本會以 `finally` 恢復故障開關。

## 接收端冪等性與資料保存

下游 JSON 使用獨立的 `schemaVersion / messageId / measurementId / asset / measurement / traceability` 結構。`Idempotency-Key` header 等於持久化 delivery ID，messageId 也使用此 ID。

模擬器以 SQLite transaction 一起寫入冪等鍵和實際接收資料，主鍵防止併發請求造成兩次副作用。相同 key、完全相同 wire payload 回覆成功並標記 duplicate；相同 key、不同 wire bytes 回覆 409。這是接收端的明確契約；FactoryBridge ingestion 端則以 JSON 結構內容判斷相同來源資料，兩個邊界的去重規則不同。

PostgreSQL 與模擬器 SQLite 都使用 named volume，`docker compose down` 後重開仍保留資料。`docker compose down --volumes` **會永久刪除這份 Demo 的資料**，只在刻意重設展示環境時使用。

模擬器的獨立契約測試涵蓋真實 HTTP、重啟後去重、8 個併發 delivery、同 key 不同 payload 衝突，以及故障後恢復：

```sh
python3 -m unittest discover -s demo -p 'test_*.py' -v
```

模擬器是刻意縮小的測試替身：沒有實作完整 MES 商務規則、身份驗證或正式 DWH 維度設計。面試展示時應把這些界線說清楚。
