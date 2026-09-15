# FactoryBridge 設計決策

此文件記錄實際採用的設計、代價與下一步的觸發條件。FactoryBridge 是可以展示故障處理與重跑語意的面試 Demo；下面不將尚未實作的正式環境能力視為既有功能。

## ADR-001：以少量 ports 隔離外部變動

**決策：** application 依賴 `StagingStore`、`MeasurementStore`、`DeliveryStore`、`WarehouseWriter`、`DownstreamClient`、`ExternalDataClient`、`MeasurementPayloadDecoder`。domain 的數值換算與時間規則使用具體純 Java 類別。

**理由：** DB、HTTP 契約與 payload decoder 是合理的替換或測試邊界；不需要為每個 mapper、validator 或小型規則再建立 interface。External DTO、domain record、JPA entity、downstream DTO 分開，變更 wire format 不會穿透核心。

**代價與擴充條件：** 同一語意欄位在不同模型間有明確映射成本。若新增第三個 source schema，優先新增 source adapter；只有業務語意新增時才擴充 canonical。

## ADR-002：raw 先提交，接受資料另做原子交易

**決策：** raw 與 import 原請求的 `SourceRecordIdentity` 以同一 `REQUIRES_NEW` 交易先提交；canonical、outbox、staging 成功狀態另用單一交易。拒絕結果也獨立提交，並只更新 `RECEIVED`。

**理由：** 不能因為資料無效、下游意圖無法寫入或 commit 失敗而丟失原始證據。成功資料也不能沒有送件意圖。`PersistenceTransactions` 使用 `TransactionTemplate` 捕捉 commit-time error，將 infrastructure exception 映射為穩定 domain error code。

**可恢復的 import 條件：** staging 的 `expectedSource` 表示取得這份 raw 時原本要求的來源系統與記錄 ID。兩個 DB 欄位有 pair CHECK，必須同時存在或同時為 null；push 為 null。這份條件與 raw 同時提交，不依賴 `EXTERNAL_RECORD_MISMATCH` 最後錯誤才存在，避免在驗證前崩潰、或稽核 DB 寫入失敗時，重跑失去原 import 約束。

**代價：** 正常完成接受或拒絕的一次 ingestion 至少有兩個交易；程序可能在第一個交易之後中斷。資料庫完全不可用時仍可能無法保存 raw；更新 rejection 再次失敗時 staging 會停在 `RECEIVED`，API 保留主要錯誤並記錄 suppressed cause。這個狀態可依已保存的 `expectedSource` 重跑，目前沒有自動掃描、告警與恢復所有滯留 staging 的控制面。

**驗證：** 整合測試分別注入第二筆 outbox 的 CHECK failure 與延遲至 commit 的 deferred FK failure，確認 canonical / outbox 均不存在，raw 仍保留。另驗證 outcome 前 caller rollback、rejection 寫入失敗時 raw 與 `expectedSource` 都保留，並由 DB 拒絕半組來源識別。

## ADR-003：raw 用 bytea，對外仍是原文

**決策：** HTTP 邊界限制 body 大小、確認 UTF-8；staging entity 將原文字串編碼為 UTF-8 `bytea`，查詢時還原字串。

**理由：** PostgreSQL `text` 無法儲存實際 NUL。無效 JSON 可能含這類字元，不能在 JSON 驗證前因存 raw 失敗而失去資料。`bytea` 不要求 JSON 合法，也不要求對外 API 改用 Base64。

**代價與限制：** 非 UTF-8 或超限請求在 transport boundary 拒絕，不留 staging。Demo 將整筆 raw 存於 operational DB，未實作加密欄位、資料遮罩、保留期限、分割表或 archival。若正式環境包含敏感操作員資訊與高流量，應依資料治理政策將原始檔移至受控儲存，並在 staging 留 checksum 與索引。

## ADR-004：來源 ID 冪等，fingerprint 判斷內容衝突

**決策：** `(source, sourceRecordId)` 有 unique constraint。首次用 `INSERT ... ON CONFLICT DO NOTHING` 取得建立權；衝突後比較已存在的 SHA-256 fingerprint（64 個十六進位字元）。

**理由：** 「先 SELECT，沒有才 INSERT」存在競爭條件；捕捉 unique violation 後繼續查詢則會踩到 PostgreSQL aborted transaction。採用 `ON CONFLICT` 可讓並發請求收斂至單一 canonical，並在 READ COMMITTED 下讀取已提交的結果。

**fingerprint 契約：** 根據完整來源 JSON tree，而非 canonical 或原始 bytes。物件鍵排序；保留未知欄位、字串與陣列順序；數字依 Jackson 的高精度 tree serialization。來源 value 本來就是字串，所以 `"35.2"` / `"35.20"` 不合併。單位等值轉換、來源字串首尾空白、操作員資訊更新，都不會被 canonical 正規化掩蓋。

**代價：** canonical 沒使用的未知欄位變更，也會產生 HTTP 409。這是偏保守的來源事件不可變契約。將來要允許來源重發時更新 `receivedAt` 等欄位，必須明確版本化 fingerprint 規則並安排既有資料遷移，不能直接修改演算法造成歷史重送全部衝突。

**限制：** SHA-256 用於工程上的內容識別，不是認證或防竄改簽章。既有 canonical 不提供 correction / revision API。

## ADR-005：Transactional outbox，採 at-least-once 投遞

**決策：** 接受資料時建立 delivery；worker 在交易外送出，再以短交易更新結果。warehouse 以 measurement ID 去重，downstream 以 delivery ID 作為 `Idempotency-Key`。

**理由：** operational DB 與 HTTP 目的地不存在共同提交邊界。outbox 解決已接受資料缺少送件意圖的問題，但仍有「目的地成功、acknowledgement 之前中斷」的重送窗口。

**不宣稱 exactly-once：** 相同 idempotency key 本身不構成保證。下游必須把去重鍵與業務副作用一起持久化，並定義保存期限；若下游不支持這個契約，FactoryBridge 仍可能造成重複副作用。兩個目的地也不會一起成功或一起回滾，可能一邊已交付、另一邊尚待恢復。

**擴充條件：** 真實高吞吐量、CDC 或多服務訂閱需求出現時，再評估 outbox relay + broker。增加 broker 仍不能省略 consumer 冪等與版本契約。

## ADR-006：用 lease token 防止舊 worker 覆寫狀態

**決策：** PostgreSQL `FOR UPDATE SKIP LOCKED` 原子認領；每次認領生成新 UUID token，增加 `attemptCount` / `totalAttempts`。過期 `IN_FLIGHT` 可重新認領。完成與失敗更新必須同時符合 ID、`IN_FLIGHT` 與 token。

**理由：** worker 可能停頓或崩潰，不能永久持有工作。只用 status 而沒有 token，舊 worker 在新 worker 認領後仍可能覆寫狀態。token 讓遲到 acknowledgement 成為不匹配的更新。

**代價與限制：** lease 到期不會取消舊 HTTP；它只允許新 worker 回收。因此兩個 worker 可能同時在目的地做相同工作，仍依賴目的地冪等。沒有 lease heartbeat；目前 timeout 與單筆認領配合 30 秒 lease。正式擴展多主機時需要可靠時鐘同步，或將到期判斷改用資料庫時鐘。

**重試門檻的實際邊界：** `attemptCount` / `totalAttempts` 都在 claim 時計數。`maxAttempts` 預設 5，於 dispatcher 處理失敗回報時，比對本輪 `attemptCount`，決定是否要求更新成 `DEAD`；只有該更新成功提交，才會停止自動認領。claim 不因次數達 5 就拒絕回收過期 lease。如果認領後反覆崩潰、或 acknowledgement 無法提交，次數可以超過 5，也可能存在目的地已成功但尚無本地結果的情況。這不是所有故障情境下最多 5 次 HTTP 的保證。

**營運需求：** 需要監控高認領次數、重複 lease 回收、最舊待處理時間、`DEAD` 與 backlog，並設定調查與隔離流程。目前 Demo 有狀態與計數可查，沒有實作這組自動告警或全域 crash recovery 次數上限。

**驗證：** 測試刻意持有一筆 row lock，再要求另一 worker 在鎖釋放前認領其他資料；另驗證過期 lease 回收後，舊 token 既不能標成功，也不能標失敗。

## ADR-007：保留重跑結果與累計次數，明確限制可重跑狀態

**決策：** 可重跑的 staging 建立新的收件紀錄，原紀錄不變；若有 `expectedSource`，以原 raw 與原 import 請求條件重新走 ingestion，並將兩者一併保存到新 staging。delivery 只允許 `DEAD` 手動重跑，沿用原 delivery ID。重跑把本輪認領次數 `attemptCount` 歸零，累計認領次數 `totalAttempts` 不歸零，`replayCount` 加一。

**理由：** 不能靠刪除 delivery、建立新 message ID 達成重跑，否則會繞過下游去重。`DELIVERED` 也不應因操作錯誤任意回到待投遞。

**隔離例外：** import 回應的 source / record ID 不符合所請求的識別時，記錄 `EXTERNAL_RECORD_MISMATCH`。這類 staging 的 raw replay 會被 `STAGING_NOT_REPLAYABLE` 拒絕，必須重新 import 並取得來源資料。否則脫離原請求條件重跑同一份 raw，將繞過外部識別的核對。

**崩潰與稽核失敗：** 最後錯誤隔離是已知 mismatch 的快速拒絕，並非唯一防線。staging 若仍為 `RECEIVED`，或帶有其他錯誤，只要有 `expectedSource`，replay 仍先核對原請求的 source / record ID，不會把 import 改當 push。即使前次 mismatch outcome 尚未保存，仍不能讓回錯身分的 raw 通過。一般 replay 不重新取得來源資料；需要 refetch 時使用 import API。

**目前保留的歷史：** 每次 staging 原文、原 import 請求條件與結果；delivery 現況、最後錯誤、本輪及累計認領次數、重跑次數。`attemptCount` / `totalAttempts` 包含尚未實際送出就崩潰的認領，也可能包含已送出但沒有 acknowledgement 的認領，不能解讀為已送出或已失敗的 HTTP 次數。成功後 last error 清除。

**目前未實作：** 每次 attempt 的 append-only history、重跑操作員與原因、staging replay parent ID、修改不可否認性。正式操作台需要這些稽核欄位與授權時，再新增專用事件表與 replay metadata，避免把它們混入 canonical。

## ADR-008：穩定查詢排序，不保證製造事件交付順序

**決策：** 最近量測依 `createdAt DESC, id DESC`；delivery 依 `createdAt, id`；認領依 `nextAttemptAt, id`。同一時間以 UUID 作為穩定 tie-breaker。

**理由：** 即使資料同時到達，重讀查詢仍有固定次序。以 UUID 補排序不代表業務時間順序，延遲到達、重試、多 worker 與不同目的地都可能改變交付順序。

**限制與擴充條件：** 目前 API 是有上限的最近清單，不是完整 cursor pagination。若下游要求每台設備的量測嚴格依序處理，需另定 partition key、來源 sequence、late-event 與 gap policy，再調整 queue / consumer；不能只把 SQL 的 ORDER BY 改成 `measuredAt` 就宣稱有順序保證。

## ADR-009：JPA 讀取模型，JDBC 表達 PostgreSQL 原子操作

**決策：** JPA 管理 staging entity 與 canonical read mapping；JdbcTemplate 負責 `ON CONFLICT`、outbox CTE / locking 與 warehouse insert。兩者在 operational write 共用同一 datasource 與 transaction manager。

**理由：** 這些並發語意用 PostgreSQL SQL 表達最直接，不建立難以閱讀的 ORM workaround。JPA entity 不含跨 schema lazy relation，避免 API 回傳時出現意外 DB 查詢。

**代價：** persistence adapter 明確依賴 PostgreSQL；H2 無法驗證相同的鎖、型別與交易行為。schema 由 Flyway 管理，Hibernate 只 `validate`；測試使用真正 PostgreSQL Testcontainers。

## ADR-010：warehouse 共用 cluster，分析模型維持 adapter 邊界

**決策：** warehouse 使用同一 PostgreSQL cluster 的另一個 schema。V1 為反正規化 fact table；V2 調整為 `dim_equipment`、`dim_date` 與 `fact_measurement` 的簡化 Star Schema，詳見 ADR-012。Writer 用獨立交易；fact 只參照 warehouse 內的 dimension，不建立指向 operational table 的 FK。

**理由：** 讓 macOS Apple Silicon 可透過簡單 Compose 展示整條流程，而不需要另一個大型分析平台。無跨 schema FK 可讓 warehouse 的生命週期與 operational tables 分開。

**代價：** 共享使用者、連線池、CPU、儲存、備份與故障域；這不是獨立 Data Warehouse 系統，也沒有 OLAP optimizer、partition strategy 或大規模 analytical benchmark。資料庫停機時，operational 與 warehouse 一起受影響。

**替換方式：** 新增 `WarehouseWriter` adapter，使用獨立 datasource / transaction manager 或 warehouse API，以 measurement ID 執行原子去重。application、canonical 與 outbox 不需要知道目的地 schema。切換前需驗證數值／時間精度、重複資料行為、錯誤分類、逾時、批次提交與部分成功的語意。


## ADR-011：固定 migration history schema 並測試第二次啟動

PostgreSQL 預設 search_path 為 `$user, public`。Demo 帳號為 factorybridge；首次 migration 前同名 schema 尚未存在，Flyway history 會在 public；建立 factorybridge schema 後，第二次啟動的預設 schema 卻可能改變。

明確設定 `spring.flyway.default-schema=public`，使空白 DB 與既有 DB 都到同一位置查 history。Operational／warehouse 表仍用 schema-qualified SQL。測試以同名 DB 角色啟動，保存資料後真正建立第二個 application context；不採 baselineOnMigrate 或清空資料避開錯誤。

## ADR-012：簡化 Star Schema、固定維度政策與保留 V1 資料

**決策：** Operational Model 保留不可變 canonical 與整合狀態；Analytical Model 將一筆 measurement 作為 fact 粒度，以設備 surrogate key 與日期 key 連結 dimension。這次改動只影響 warehouse adapter、migration、分析 SQL 與其測試，不把分析 key 加進 domain 或既有 API。

**設備識別：** `dim_equipment.equipment_key` 使用 bigint surrogate PK；`(plant_code, equipment_id)` 使用 unique business key，避免把不同廠區的同名設備合併。equipment_type、line_code、station_code 採 Type 0，首次成功載入後不更新。這不是最新 master data，也不描述每筆量測發生時的設備位置；canonical 仍保存每筆原屬性。

**選擇 Type 0 的理由：** Demo 沒有設備主檔版本與搬站生效時間；以最後收到的 measurement 覆寫 dimension 會被延遲事件與 replay 影響。固定首次屬性能清楚展示維度共用，也能讓重送不改寫既有分析分類。若要回答搬站前後歷史，應先定義有效期間與來源主檔，再評估 SCD Type 2，而非以 ingestion 順序猜測。

**日期：** `date_key = YYYYMMDD`，`full_date` 唯一，另保存 year、quarter、month、day。Writer 使用 `ZoneOffset.UTC`；migration 使用 `AT TIME ZONE 'UTC'`，兩者以 measuredAt 判斷日期，不受 server default / DB session timezone 影響。這與來源字串的解析時區是不同責任。正式製造環境通常應依 Plant Business Timezone 建立日期維度；本版沒有 shift calendar。

**寫入與冪等：** 在同一獨立 warehouse 交易依序建立或取得 equipment、date，再寫 fact。Dimension 使用 unique constraint + `ON CONFLICT DO NOTHING`，接著取得 key；使用 READ COMMITTED 讀取 concurrent insert 提交後的結果。Fact 仍以 measurement ID PK / `ON CONFLICT DO NOTHING` 去重，已寫入的 fact 不覆寫；整筆交易失敗仍回報 `DATA_WAREHOUSE_WRITE_FAILED`。這保留既有 outbox 的 at-least-once 契約。

**V2 升級：** 不改 V1 checksum。Migration 鎖住既有 fact，在同一 Flyway 交易完整複製為 `warehouse.fact_measurement_v1_archive`，建立及 backfill dimensions，補 fact FK 後加 constraint / index，再移除原維度與 lot / batch 欄位。同設備以 `loaded_at, measurement_id` 最早的一筆決定 Type 0 屬性，讓 backfill 有固定結果。既有 fact 的 ID、量測與來源欄位保留；被移出的每筆資訊仍在 archive，無須假定 operational DB 一定還有對應資料。

**代價與部署邊界：** Archive 是一次性 V1 快照，新增量測不寫入，也不參與分析；成本是額外保留一份舊 fact。Fresh DB 依序執行 V1 / V2，archive 為空。DDL 與 backfill 失敗一起回滾；舊 writer 不相容新 schema，因此需要先停止舊 app 再升級。本版不提供零停機部署、向下相容 view 或自動 downgrade；正式遷移需另訂容量、備份與保留政策。

**分析契約：** [warehouse-analysis.sql](../demo/warehouse-analysis.sql) 實際 JOIN 三張表，依 UTC 日期與設備統計已載入的 `WARNING` / `BAD`。分組包含設備 key；沒有異常的組合不輸出零值列。這是事件筆數，不是故障次數、異常率或 OEE；這些指標還需要明確分母及事件合併規則。

**刻意不加入：** SCD Type 2、Shift / Product / Recipe Dimension、完整 Enterprise Data Warehouse、OLAP Engine。先用一個實際問題驗證簡化 Star Schema，避免為展示模型而加入沒有來源契約的維度。
