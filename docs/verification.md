# 驗證紀錄

本紀錄只列實際完成的檢查。環境為 macOS Apple Silicon、Java 21、Maven 3.9.11、Colima / Linux ARM64 Docker、PostgreSQL 17。原始建置報告可在本機 target 或 GitHub Actions 的 verification-evidence artifact 取得；不將含本機路徑的大型 log 提交到 repository。

## 2026-09-15：Star Schema 增量驗證

實際執行 `./mvnw clean verify spotless:check`，**326 tests/rules 全部通過，0 failures、0 errors、0 skipped**：原有 293 項非容器測試，及 33 項 PostgreSQL 整合測試。相較初版新增 8 個案例；原有測試保留，重啟檢查更新為兩個 Flyway 版本。Python 模擬器 8 項測試也全部通過。

新增案例涵蓋首次建立維度與 fact、同廠共用／跨廠區分設備、共用日期、Type 0 屬性固定、JVM 與 DB session 都非 UTC 時的日期歸屬、並發相同／不同 measurement ID、直接執行展示分析 SQL，以及有資料的 V1 → V2 migration。原有倉儲失敗案例另確認 dimension 與 fact 一起回滾。Migration 測試保留全部舊欄位，核對值與 loaded_at、FK、固定 backfill 順序、非 UTC 日界線及再次 migrate 不變；其資料刻意沒有 operational 對應列。

第一次升級測試發現 PostgreSQL JDBC 會以 JVM 時區覆寫 session，單設 server timezone 無法建立預期測試條件。測試改在每條連線初始化時明確設定非 UTC，再完整重跑；不是放寬 UTC 結果斷言。

實際保留舊 Compose volume，停止舊 app 後執行 `docker compose up --build --wait --wait-timeout 180`，三個服務均 healthy。升級前 15 筆 V1 fact 在升級後仍為 15 筆；archive 與升級前依 measurement_id 排序匯出的全部 JSON 列逐筆一致，量測與來源欄位（含 loaded_at）差異 0，Flyway V1 / V2 均成功。新建空白資料庫的 V1 / V2 路徑由 Testcontainers 的 application 啟動與 warehouse 測試驗證。

完整 `python3 demo/smoke-test.py` 通過，包括 RETRY 自動恢復及 DEAD 人工重送；correlationId 為 `smoke-f543b7f3c1a04aeb`。之後透過既有 API 加入 4 筆合成分析資料（`STAR-DEMO-20260915-1` 至 `-4`），包含兩廠共用設備代碼、UTC 跨日、WARNING / BAD / GOOD；全部交付成功。最終 24 筆 canonical = 24 筆 fact、15 筆 archive、3 筆設備維度、3 筆日期維度、未完成 delivery 0。

實際執行交付的 [分析 SQL](../demo/warehouse-analysis.sql)：

```sh
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 \
  -U factorybridge -d factorybridge < demo/warehouse-analysis.sql
```

| date | equipment_id | equipment_type | plant_code | abnormal_count |
| --- | --- | --- | --- | ---: |
| 2026-09-10 | EQ-CT-003 | COATING_MACHINE | KH01 | 4 |
| 2026-09-14 | EQ-STAR-001 | COATING_MACHINE | KH01 | 2 |
| 2026-09-14 | EQ-STAR-001 | COATING_MACHINE | TN01 | 1 |

第一列包含歷次 smoke 留存資料；後兩列證明不同廠區的同名設備不會合併，9 月 15 日的 GOOD 不計入異常。結果隨 Demo 累積資料增加；報表只統計已成功入倉的 WARNING / BAD。

## 2026-09-11：初版驗證基線

## Build / Test / Review / Validation

| 模組 | Build / Test | Review / Validation |
| --- | --- | --- |
| Domain | 156 tests，全部通過 | 單位換算、物理邊界、精度溢位、無效日期、微秒精度、大小寫與不變性 |
| Application | 80 tests，全部通過 | raw 先留存、durable import intent、crash/audit failure replay、unknown errors、bounded backoff、失敗 acknowledgement |
| HTTP adapters | 31 tests，全部通過 | 真實 localhost HTTP、wire DTO、headers、429/503/4xx、headers 後 body 停滯、UTF-8 與 JSON 深度／大小邊界 |
| Web API | 22 tests，全部通過 | 200/202/400/404/409/413/415/422/500/503、Bean Validation、安全錯誤與 correlation ID、lease token 不外洩 |
| Architecture | 4 rules，全部通過 | domain / application 無框架相依、persistence 與 HTTP 不互相耦合 |
| PostgreSQL persistence | 25 Testcontainers IT，全部通過 | 真正 PostgreSQL、Flyway/JPA validate、併發去重、SQL 與 commit-time 回滾、SKIP LOCKED、fencing、raw NUL 留存、來源條件保存 |
| Simulator | 8 Python tests，全部通過 | SQLite 持久化去重、8 個併發請求、重啟、錯誤 JSON、故障恢復 |
| Docker Compose | ARM64 映像建置成功，3 個服務 healthy | 映像建置內另執行 293 項 Java 非容器測試；app 使用非 root 使用者 |
| End-to-end | 完整 smoke script 通過 | 正常／衝突／驗證／路由／MES pull／RETRY 恢復／DEAD 手動重送 |
| OpenAPI | 實際執行中的 schema / Swagger 驗證通過 | 11 個 external required 欄位、request schema、8 個 resource paths、lease token 不在回應 schema |
| Data Warehouse | 實際 SQL 核對通過 | 6 筆 canonical = 6 筆 fact；數值、單位、事件時間差異 0；未完成 delivery 0 |
| Restart | 修正後實際重啟 app 通過 | readiness 恢復，6 筆既有量測與其交付結果保留 |

Java 合計 **318 tests/rules，0 failures、0 errors、0 skipped**。這不把 Python 與 smoke script 的情境數混入 Java 測試總數。

乾淨建置已通過；重啟修正後再次完整 verify，包含新增的 ApplicationRestartIT，318 項全過。JaCoCo 報告與 Spotless 格式檢查均成功：

```sh
./mvnw clean verify spotless:check
python3 -m unittest discover -s demo -p 'test_*.py' -v
docker compose up --build --wait --wait-timeout 180
python3 demo/smoke-test.py
```

本機第一次整理 Java 格式另執行 spotless:apply。JaCoCo 結果：domain line 99.6%、branch 97.1%；application line 100.0%、branch 91.0%。覆蓋率只作補充證據；交易、並發與故障語意由專門案例驗證，不以百分比代替正確性。

重啟檢查曾發現同名 DB role / schema 導致 Flyway history 改變位置，已固定 public schema；原有 volume 不清除，以實際第二次 app 啟動及新增 Testcontainers 回歸證明修復。細節見 [ADR-011](decisions.md)。

## 實際完整 smoke 輸出

```text
PASS normalization, UTC time, stable source identity, conflict, staging replay, both destinations
PASS semantic validation error, immutable raw audit, failed replay, corrected source resubmission
PASS WARNING quality routes to warehouse only
PASS MES HTTP pull uses the shared ingestion flow
PASS downstream 503 persists RETRY, recovers automatically, and keeps stable delivery identity
PASS retries exhaust to DEAD; manual replay succeeds with the same durable idempotency key
All smoke checks passed. correlationId=smoke-c9bc8dee9ee24490
```

腳本使用唯一 sourceRecordId，允許多次執行。MES pull fixture 固定，因此接受初次 202 或已存在的 200。模擬故障會在 finally 關閉；輪詢逾時或任一斷言失敗會回傳非零狀態。

## 可重現的倉儲核對

```sh
docker compose exec -T postgres psql -U factorybridge -d factorybridge -c "
SELECT
  (SELECT count(*) FROM factorybridge.measurement) AS canonical_count,
  (SELECT count(*) FROM warehouse.fact_measurement) AS warehouse_count,
  (SELECT count(*) FROM factorybridge.delivery_outbox
     WHERE status <> 'DELIVERED') AS unfinished_deliveries;
SELECT count(*) AS mismatches
FROM factorybridge.measurement m
JOIN warehouse.fact_measurement f ON f.measurement_id = m.id
WHERE m.numeric_value <> f.numeric_value
   OR m.standard_unit <> f.standard_unit
   OR m.measured_at <> f.measured_at;"
```

多跑幾次展示後筆數會增加；當所有 delivery 成功，canonical 與 fact 筆數應一致、差異為零。查詢結果不能取代錯誤與併發測試。

## 驗證範圍的限制

未執行大型流量、長時間 soak、真實 MES／正式下游驗收、跨獨立 DWH 網路故障或安全滲透測試；不宣稱已通過真實工廠 production readiness。這些限制與下一輪工作列在 [設計決策](decisions.md) 與 [面試官 Review](interviewer-review.md)。

初次完整 [GitHub CI](https://github.com/xuping68/FactoryBridge-Manufacturing-Data-Integration-Middleware/actions/runs/34598663236) 已通過 Java／PostgreSQL 測試、模擬器測試、容器建置與完整故障恢復展示。該次建置的系統套件步驟等待較久；後續移除重複安裝基底映像已有的 curl，並以 ARM64 Compose 重建確認三個服務 healthy。最新 commit 的 CI 狀態請以 repository 的 Actions 頁面為準。
