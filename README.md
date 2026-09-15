# FactoryBridge
### Manufacturing Data Integration Middleware

給資深軟體工程師二次面試展示的製造業資料整合 Demo。以設備量測為主題，展示從外部 MES、原始資料留存、驗證與清洗，到標準化、資料倉儲與下游交付的完整流程。

**閱讀重點：來源格式有邊界、失敗有證據、接收有交易、重送有規則。** 這是模擬企業整合的可執行專案；不宣稱為真實工廠系統，也不把「HTTP 接收成功」當成「所有系統已同步」。

## 先執行

只需要可運作的 Docker Engine 與 Docker Compose v2。macOS Apple Silicon 可用 Docker Desktop 或 Colima；映像支援 Linux ARM64，不強制模擬 x86。

```sh
git clone https://github.com/xuping68/FactoryBridge-Manufacturing-Data-Integration-Middleware.git
cd FactoryBridge-Manufacturing-Data-Integration-Middleware
docker compose up
```

第一次啟動會建置 Java 程式並執行不需 Docker daemon 的測試。看到 app healthy 後即可操作。若修改過程式，改用：

```sh
docker compose up --build --wait
python3 demo/smoke-test.py
```

- [Swagger UI](http://localhost:8080/swagger-ui/index.html)｜[OpenAPI JSON](http://localhost:8080/v3/api-docs)
- [Readiness](http://localhost:8080/actuator/health/readiness)｜[模擬下游接收紀錄](http://localhost:8090/admin/deliveries)
- PostgreSQL：localhost:5432，database/user：`factorybridge`，Demo password：`factorybridge_demo`。

Compose 啟動 app、PostgreSQL、MES／下游模擬器。host port 僅綁定 loopback；raw 查詢、故障開關與 replay 是本機展示功能，沒有生產環境的身分驗證。所有範例都是合成資料。

## 五分鐘看懂主要流程

```mermaid
flowchart TD
    MES[External MES Simulator] -->|HTTP pull| Import[ExternalMeasurementImportService]
    Push[POST /api/v1/measurements] --> Ingest[MeasurementIngestionService]
    Import --> Ingest
    Ingest --> Raw[(Raw / Staging — independent transaction)]
    Raw --> Decode[External DTO → MeasurementDraft]
    Decode --> Normalize[Cleansing → Validation → Normalization]
    Normalize --> Canonical[CanonicalMeasurement]
    Normalize -->|invalid| Rejected[REJECTED + errorCode + stagingId]
    Canonical --> Accept[Accept canonical + outbox + staging outcome — one transaction]
    Accept --> Worker[DeliveryDispatcher — claim with lease]
    Worker -->|all quality states| DWH[Warehouse Writer → simplified Star Schema]
    Worker -->|GOOD only| Transform[Downstream DTO v1]
    Transform --> Downstream[Downstream HTTP + durable idempotency key]
    Worker --> Retry[RETRY → bounded backoff → DEAD → manual replay]
```

建議依這個順序閱讀：

| 順序 | 檔案 | 看什麼 |
| --- | --- | --- |
| 1 | [MeasurementIngestionService](src/main/java/io/factorybridge/application/MeasurementIngestionService.java) | 小型流程協調；不自己做 HTTP、SQL、單位轉換 |
| 2 | [MeasurementNormalizer](src/main/java/io/factorybridge/domain/MeasurementNormalizer.java) | 純 Java 的清洗、驗證與 canonical 建立 |
| 3 | [JpaMeasurementStore](src/main/java/io/factorybridge/adapter/persistence/JpaMeasurementStore.java) | 原子去重與 canonical/outbox 交易 |
| 4 | [DeliveryDispatcher](src/main/java/io/factorybridge/application/DeliveryDispatcher.java) | 交付與 acknowledgement 的失敗邊界 |
| 5 | [JdbcDeliveryStore](src/main/java/io/factorybridge/adapter/persistence/JdbcDeliveryStore.java) | SKIP LOCKED、lease token 與安全重跑 |
| 6 | [HTTP adapters](src/main/java/io/factorybridge/adapter/http) | 來源 DTO、下游 DTO、有限超時與錯誤分類 |
| 7 | [測試](src/test/java/io/factorybridge) | 用可驗證的失敗情境支持設計，而非只測 happy path |

## 一筆量測的變化

原始資料以字串表示 value、unit、eventTime；其中來源設備、製程位置、批號與品質都有獨立意義。完整輸入見 [measurement.json](demo/fixtures/measurement.json)。

```sh
curl -i http://localhost:8080/api/v1/measurements \
  -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: interview-001' \
  --data-binary @demo/fixtures/measurement.json
```

首次回傳 `202 ACCEPTED`，附上 `measurementId`、`stagingId` 與 `deliveriesUrl`。依 `Location` 查到的 canonical 內容包含：

```json
{
  "equipmentId": "EQ-CT-003",
  "plantCode": "KH01",
  "lineCode": "CELL-LINE-01",
  "stationCode": "COATING-03",
  "lotNumber": "LOT-20260910-0012",
  "metricType": "TEMPERATURE",
  "numericValue": 35.200000,
  "standardUnit": "C",
  "measuredAt": "2026-09-10T06:30:22Z",
  "qualityStatus": "GOOD",
  "source": "MES_A",
  "sourceRecordId": "MES-A-20260910-000001"
}
```

這是 canonical 欄位節錄；API 另附 id、equipmentType、batchNumber 與 createdAt。計算使用 BigDecimal；`(95.36 − 32) × 5 ÷ 9 = 35.2°C`。

| 原始規則 | Canonical 規則 |
| --- | --- |
| TEMP / TEMPERATURE，C / F | TEMPERATURE，C |
| PRESSURE，Pa / kPa / bar | PRESSURE，kPa；此 Demo 定義為絕對壓力 |
| HUMIDITY，% | HUMIDITY，%，0–100 |
| RPM / ROTATIONAL_SPEED，RPM | ROTATIONAL_SPEED，rpm |
| OK / PASS / GOOD | GOOD，投遞 DWH 與 downstream |
| WARN / WARNING；NG / FAIL / BAD | WARNING / BAD，僅投遞 DWH |

識別碼去除首尾空白；來源、設備、廠區等代碼轉大寫。sourceRecordId、lotNumber、batchNumber 保留大小寫。未知單位、未知品質、無法解析的數值一律拒絕，不猜測、不偷偷修補。

時間接受帶 offset 的 ISO timestamp，或來源約定的 `uuuu/MM/dd HH:mm:ss`：MES_A 使用 Asia/Taipei，MES_B 使用 UTC。不得依賴伺服器預設時區；限制年份 2000–2100，嚴格拒絕不存在的日期。資料庫存 Instant/timestamptz，精度截斷到微秒。

數值統一 scale 6、HALF_UP，對應 PostgreSQL numeric(20,6)；禁止千分位、科學記號與 NaN。溫度不可低於絕對零度；絕對壓力與轉速不可為負。設備實際上下限與製程 alarm threshold 應由後續規格／recipe 管理，這裡不硬編造工廠規則。

## 重送、失敗與復原

| 情境 | 系統行為 |
| --- | --- |
| 相同 source + sourceRecordId、相同 JSON 內容 | 200，回傳原 measurementId；新增本次 staging，沒有新 canonical/outbox |
| 相同 source key、不同內容 | 409 DUPLICATE_SOURCE_RECORD；不覆寫已接受量測 |
| JSON 欄位順序或排版不同 | 相同 fingerprint；識別不受排版影響 |
| 字串值、未知欄位或陣列順序改變 | fingerprint 會改變；清洗後剛好一樣仍不代表原內容一樣 |
| malformed JSON／無效數值／單位 | raw 保留，REJECTED，回傳 stagingId 與語意錯誤 |
| 超過 64 KiB／非法 UTF-8 | transport 邊界拒絕，沒有 staging；防止無限制記憶體或不保真的字元替換 |
| 接收交易失敗 | canonical 與 outbox 一起回滾；已提交的 raw 留存 |
| 下游 503／timeout／429 | durable RETRY，指數退避加 jitter；失敗回報時依本輪認領次數評估 5 次門檻 |
| 下游不可重試的 4xx／程式契約缺陷 | DEAD，人工排查後 replay |
| 送出成功但未記下成功結果 | lease 過期後重送同一 idempotency key；接收端須原子去重 |
| 匯入回傳了另一筆來源識別 | EXTERNAL_RECORD_MISMATCH 隔離；禁止以 raw replay 繞過，需重新 import |
| app 重啟／多 worker | DB 原子 claim + SKIP LOCKED；過期租約可回收，舊 token 不可覆寫新狀態 |

認領後反覆崩潰或 acknowledgement 無法提交時，claim 次數可能超過門檻；詳細邊界見 [重試決策](docs/decisions.md)。

語意是 **at-least-once delivery**。下游模擬器將 idempotency key 與接收紀錄在 SQLite 同一交易寫入；DWH 以 measurementId 唯一鍵與 INSERT ON CONFLICT DO NOTHING 去重，不覆寫既有 fact。這證明在合作接收端的契約下可避免重複副作用，並非跨系統全域 exactly-once。

raw replay 產生新的 staging，不修改歷史。相同 key 已成功接受的資料如果需要更正，來源應發出新識別或另設明確 revision 契約；本版不提供偷偷覆寫的 update API。

MES 匯入的預期 source identity 與 raw 在最初同一交易留存；即使程式在驗證前崩潰、staging 仍為 RECEIVED，重跑也必須沿用原來的來源識別檢查。

## 簡化 Star Schema：每天每台設備有多少異常量測？

Operational Model 保存 canonical 與整合狀態，用來回答「這筆資料是否接受、是否送達」；Analytical Model 把量測 fact 與可共用的設備／日期 dimension 分開，用來回答「哪一天、哪個廠區的哪台設備出現多少異常」。倉儲結構由 [JdbcWarehouseWriter](src/main/java/io/factorybridge/adapter/persistence/JdbcWarehouseWriter.java) 轉換，不改變 domain、API 或 outbox。

```mermaid
flowchart TD
    Operational[Operational DB：Canonical Measurement] --> Writer[Warehouse Writer]
    Writer --> Fact[FactMeasurement]
    Fact -->|equipment_key| Equipment[DimEquipment]
    Fact -->|date_key| Date[DimDate]
```

| 資料表 | 欄位與鍵 |
| --- | --- |
| `warehouse.dim_equipment` | `equipment_key`：bigint surrogate PK；`equipment_id`、`equipment_type`、`plant_code`、`line_code`、`station_code`；business key 為 `(plant_code, equipment_id)` |
| `warehouse.dim_date` | `date_key`：`YYYYMMDD` 整數 PK；`full_date` 唯一；`year`、`quarter`、`month`、`day` |
| `warehouse.fact_measurement` | `measurement_id` PK；`equipment_key` / `date_key` FK；`metric_type`、`numeric_value`、`standard_unit`、`quality_status`、`measured_at`、`source`、`source_record_id`、`loaded_at` |

Fact 粒度是一筆 canonical measurement，不重複保存廠區、產線或站點屬性。日期一律由 `measuredAt` 轉成 **UTC 日期**；例如 `2026-09-11T00:30:00+08:00` 歸入 `20260910`。來源時間解析仍遵守各 MES 契約，不把解析時區與分析日界線混為一談。**正式製造環境通常應依 Plant Business Timezone 建立日期維度。**

設備維度採簡單的 **Type 0（首次成功載入後不覆寫）**：同廠同設備共用一個 key；不同廠區可使用相同 equipmentId。這些屬性不是最新設備主檔，也不能代表每筆歷史量測當時的位置；canonical 仍保留每筆原有資訊。此規則避免延遲到達或重送任意改寫分析分類。

Writer 在同一獨立 warehouse 交易內，依 business key 建立或取得設備、建立或取得 UTC 日期，再寫 fact。Dimension unique constraint 與 `ON CONFLICT DO NOTHING` 處理重複／並發，`measurement_id` PK 讓相同量測重送只保留一筆 fact；不改變既有 at-least-once delivery 與錯誤分類。

執行完整 smoke test 後，可直接查詢 `WARNING` / `BAD`，包含三張表的實際 JOIN：

```sh
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 \
  -U factorybridge -d factorybridge < demo/warehouse-analysis.sql
```

[分析 SQL](demo/warehouse-analysis.sql) 輸出 `date`、`equipment_id`、`equipment_type`、`plant_code`、`abnormal_count`。分組包含設備 surrogate key，避免跨廠同名設備合併。結果只包含已成功送達 warehouse 的異常資料；沒有異常的日期／設備不輸出零值列。smoke test 每次建立新的來源識別，因此多次執行後筆數會累加。

### 從既有 V1 資料庫升級

[V2 migration](src/main/resources/db/migration/V2__create_warehouse_star_schema.sql) 不修改 V1；在同一 Flyway 交易內鎖定原 fact，先完整複製到 `warehouse.fact_measurement_v1_archive`，再建立 dimension、補 FK、加入 constraint / index，最後移除 fact 中的維度與 lot / batch 欄位。既有 fact 的量測 ID、值、品質、時間及 loadedAt 保留；被移出的每筆原屬性與 lot / batch 仍可在 archive 查到。

Backfill 同廠同設備採 `loaded_at` 最早的一筆，時間相同時以 `measurement_id` 固定排序，決定 Type 0 屬性。Archive 是一次性的 V1 快照，不接收新寫入、不參與報表；保留成本是額外一份舊 fact 資料，正式環境應另訂保留期限與容量政策。Fresh DB 依序套用 V1、V2，archive 為空。

舊版 writer 不相容 V2 fact 欄位，因此這次升級需要短暫停止舊 app，不宣稱零停機。保留既有 volume，執行：

```sh
docker compose stop app
docker compose up --build --wait
```

Flyway 失敗會回滾本次 schema／backfill 變更；升級不需清空 volume 或使用 baseline。

## API 與錯誤契約

| Method / path | 責任 |
| --- | --- |
| POST /api/v1/measurements | 接收一筆原始量測 |
| POST /api/v1/imports | 依 sourceSystem、sourceRecordId 從 MES 取得資料 |
| GET /api/v1/measurements?limit=20 | 查最近量測；limit 1–100，依建立時間/id 倒序 |
| GET /api/v1/measurements/{id} | 查 canonical |
| GET /api/v1/measurements/{id}/deliveries | 查各目的地交付狀態 |
| GET /api/v1/staging/{id} | 查 raw 與處理結果 |
| POST /api/v1/staging/{id}/replays | 以同 raw 建立新的處理嘗試 |
| GET /api/v1/deliveries/{id} | 查重試、錯誤與累計嘗試 |
| POST /api/v1/deliveries/{id}/replays | 只允許 DEAD 重新排隊，保留 delivery ID |

```json
{
  "errorCode": "UNSUPPORTED_UNIT",
  "category": "VALIDATION",
  "message": "Measurement unit is not supported for this metric.",
  "correlationId": "interview-001",
  "timestamp": "2026-09-10T06:30:22Z",
  "stagingId": "b65fce5a-84e1-4700-926d-02d6c3fd6aef",
  "violations": []
}
```

訊息可補充情境，呼叫端應依穩定 errorCode 分支。Validation 使用 400（格式／參數）或 422（量測語意）；Business 使用 404/409；Integration 使用 502/503；Infrastructure 使用 503 或意外缺陷的 500。回應不包含 SQL、密碼或 stack trace。完整代碼見 [ErrorCode](src/main/java/io/factorybridge/domain/ErrorCode.java)。

X-Correlation-Id 僅接受 1–64 個安全字元，否則產生新 UUID。API response header、staging、outbox 與交付 log 可串聯；HTTP request log 不記錄 raw 或 operatorId。

## 工程設計

採單一 Maven 專案、以 package 分責任。Domain 與 application 不依賴 Spring、Jackson、HTTP 或資料庫；有實際變動來源才抽 port。

- **Domain**：immutable records、單位／數值／時間政策；純 Java 可直接測試。
- **Application**：接收、匯入、重跑、投遞協調；Clock／retry jitter 可注入。
- **Adapters**：不同來源／下游 DTO、JPA entity、HTTP API response 分離。
- **Persistence**：JPA 做生命週期與讀取；PostgreSQL 原子去重／lease 用明確 SQL。Flyway 管 schema，Hibernate 僅 validate，關閉 Open Session in View。
- **Delivery**：網路 I/O 不持有 DB row lock。DWH 與 downstream 分開追蹤；每次只認領一筆，避免批次後段等待時 lease 已過期。
- **Operations**：有限 timeout、bounded request body、非 root container、readiness 含 DB、優雅停止、CI 與可重跑 smoke checks。

[架構與交易邊界](docs/architecture.md)｜[設計決策與取捨](docs/decisions.md)｜[面試展示步驟](demo/README.md)｜[驗證紀錄](docs/verification.md)｜[面試官 Review](docs/interviewer-review.md)

## 測試與建置

本機開發需要 **Java 21**；Maven Wrapper 固定 3.9.11 並校驗下載檔。完整 verify 另需 Docker daemon。Java 相依固定為 Spring Boot 3.5.16、Springdoc 2.8.17，採 JUnit 5／Mockito／Testcontainers；版本相容性依 [Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html) 與 [Springdoc compatibility](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot) 確認。

```sh
./mvnw test                       # domain/application/API/HTTP contract/architecture
./mvnw verify                     # 再加真正 PostgreSQL 的 Testcontainers IT
./mvnw spotless:check             # 一致的 Java 格式
./mvnw spotless:apply             # 主動整理格式
./mvnw clean verify spotless:check # 完整重建、測試與格式檢查
python3 -m unittest discover -s demo -p 'test_*.py' -v
```

`verify` 在 Docker 不可用時會失敗，不會略過整合測試再印成功。CI 執行 verify、格式檢查、模擬器測試、Compose 啟動與完整 smoke test。報告輸出在 target/surefire-reports、target/failsafe-reports、target/site/jacoco。

若使用 Colima 且 Testcontainers 無法自動找到 socket：

```sh
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
./mvnw verify
```

## Demo 邊界與下一步

本版刻意聚焦可讀、可驗證的單筆整合流程與簡化 Star Schema：沒有批次匯入、訊息 broker、分散式全域交易或依設備排序保證。最近量測查詢是 bounded recent list，尚未提供大量資料的 cursor pagination。

分析模型刻意沒有加入 SCD Type 2、Shift / Product / Recipe Dimension、完整 Enterprise Data Warehouse 或 OLAP Engine；目前只有設備／日期維度與單筆量測 fact，足以展示可執行的異常量測分析。

DWH 與 operational store 共用一個 PostgreSQL cluster、使用不同 schema 與交易；這讓 Demo 可一鍵執行，也代表不是獨立 failure domain。WarehouseWriter 已隔離，換成另一座 DB 或 API 不需改 domain。

正式導入前應依場域需求補上來源身分驗證、replay 權限與操作者稽核、raw 保留／遮罩政策、DB 帳號最小權限、dead/backlog 告警、容量與壓力測試、設備／recipe 規格、mapping 版本遷移與接收端冪等協議。這些是已辨識的交付界線，不是宣稱已完成的功能。
