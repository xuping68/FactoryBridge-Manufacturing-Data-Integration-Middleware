# 驗證紀錄

本紀錄只列實際完成的檢查。驗證日期：2026-09-11。環境為 macOS Apple Silicon、Java 21、Maven 3.9.11、Colima / Linux ARM64 Docker、PostgreSQL 17。原始建置報告可在本機 target 或 GitHub Actions 的 verification-evidence artifact 取得；不將含本機路徑的大型 log 提交到 repository。

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

GitHub CI 的執行狀態請以 repository 的 Actions 頁面為準；本表記錄的是上述本機實測，沒有把尚未執行的遠端 workflow 算成通過。
