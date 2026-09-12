# 用人主管／Senior Software Engineer 模擬 Review

這份紀錄以製造業軟體整合面試的審查角度檢查程式；不是任何公司或真實面試官的背書。審查重點是失敗情境、資料一致性、維護成本，以及工程判斷是否有測試證據。

## Review 後已直接修正

| 發現的問題 | 修正 | 可閱讀的證據 |
| --- | --- | --- |
| PostgreSQL 帳號與 schema 同名，第二次啟動改到另一個 schema 找 Flyway history | 固定 spring.flyway.default-schema=public；以相同角色與既有資料實際啟動第二個 context | ApplicationRestartIT、實際 Compose restart |
| 只用 PostgreSQL text 存 raw，實際 NUL 字元會讓稽核寫入先失敗 | raw 改 bytea；API 先嚴格驗 UTF-8、限制 64 KiB，合法 UTF-8 malformed JSON 仍能原樣留存 | MeasurementPersistenceIT：NUL／中文 round-trip |
| 匯入預期識別只在當次記憶體中，程式中斷後 replay 可能變成一般 push | 預期 source identity 與 raw 在同一交易保存；replay 沿用，明確 mismatch 須重新 import | MeasurementPersistenceIT、MeasurementIngestionServiceTest、StagingReplayServiceTest |
| 驗其他欄位後才檢查識別，無效單位可能掩蓋「拿錯筆」 | decode 後先核對識別，再做量測標準化；加入組合失敗測試 | MeasurementIngestionServiceTest |
| unexpected runtime error 失去 stagingId，不利追查 | application 邊界統一 INTERNAL_ERROR，保留 cause、stagingId 與稽核失敗的 suppressed exception | MeasurementIngestionServiceTest |
| unique violation 會讓 PostgreSQL 交易無法繼續 | INSERT ON CONFLICT DO NOTHING，再在 READ COMMITTED 中查 hash；canonical/outbox/staging outcome 原子提交 | MeasurementPersistenceIT：併發、衝突、第二筆 outbox 失敗、commit-time rollback |
| 下游已收到但本機寫成功狀態失敗，容易誤作「下游沒收到」 | acknowledgement 留在交付 try/catch 外，等 lease 回收後以同 key 重送 | DeliveryDispatcherTest、DeliveryPersistenceIT |
| 批次先 claim 可能在排隊等待時讓後段 lease 過期 | 一次認領一筆；HTTP 不持有 row lock；舊 leaseToken 更新不生效 | DeliveryDispatcher、SKIP LOCKED／fencing IT |
| stale worker 的失敗 log 可能聲稱已進 DEAD | log 區分 requestedStatus 與 leaseAccepted | DeliveryDispatcherTest |
| HTTP headers 到了但 body 停滯，request timeout 的假設不夠完整 | 整段 exchange deadline、取消未完成 future、有限回應大小 | HttpExternalDataClientTest、HttpDownstreamClientTest |
| 日期奈秒與 DB 微秒精度不一致；極大重試 duration 可能溢位 | 明確截斷到微秒；retry 設定範圍與飽和計算 | MeasurementTimeNormalizationTest、DeliveryRetryPolicyTest |
| Swagger 必填／範例與真實驗證不一致 | 外部 schema 註明 required、長度、大小寫、單位與時間契約；不把 Bean Validation 移到 raw 留存之前 | ExternalMeasurementDto |
| 容器 readiness 預設只表示生命週期，未反映 DB | readiness group 包含 db；JPA schema 使用 validate；Flyway 管理 migration | application.yml、Compose smoke |
| CI 建置重複安裝基底映像已有的 curl，多了一個 Ubuntu 套件來源等待點 | 直接驗證 curl 存在，保留非 root 使用者與 readiness；減少無必要的建置網路相依 | Dockerfile、原始 Temurin 映像檢查、ARM64 Compose 重建 |

## 工程判斷

此 Demo 最有說服力的地方不是 class 數量，而是區分了四種事實：

1. 收到資料，不等於資料有效。
2. 資料有效，不等於接受交易已提交。
3. 交易已提交，不等於每個目的地已收到。
4. 本機沒有收到 acknowledgement，不等於遠端沒有產生副作用。

核心 domain 無框架相依；application 透過實際有變動理由的 ports 連接來源、儲存與下游。JPA 不被硬套在 PostgreSQL 需要原子化的去重／claim SQL 上。測試有真實競爭鎖、交易中途與提交時故障，避免僅以順序測試宣稱支援併發。

## 面試時應主動說清楚

- 交付保證是 at-least-once；避免重複副作用仰賴接收端原子去重，不宣稱全域 exactly-once。
- 來源 key 的內容已接受後不可覆寫；correction／revision 需要獨立契約。
- 相同 JSON 結構 fingerprint 與下游 wire bytes 去重是兩個不同邊界。
- 品質 WARNING/BAD 留存 DWH，GOOD 才送作業下游；這是 Demo 路由政策，正式場域應與品質／製程部門確認。
- DWH 共用 cluster 是啟動便利的取捨；沒有獨立 failure domain、完整維度模型或企業數據治理。
- 不保證同設備交付順序；需要 ordering 時應加入 partition key／序號與明確重送政策。
- 保留 staging 歷史、累計 attempts、replayCount 與最後錯誤；尚未建立每次 delivery attempt 的完整事件稽核表。
- source mapping／downstream contract 改版時，已排隊工作需要版本遷移策略；目前只提供 v1，不能直接換 wire schema 後把舊 key 視為新訊息。

## 正式導入的下一輪工作

增加認證與 replay 權限、操作者稽核、原始資料保留期限／遮罩、dead/backlog 告警、metric observability、壓力測試與故障注入，以及獨立 DWH 儲存。這些尚未實作，應依實際設備數、流量、延遲目標與企業控管要求決定優先順序。

面試展示建議：用 2 分鐘說明交易與格式邊界，再用 5 分鐘展示正常量測、重送、衝突、無效資料與下游故障恢復，最後挑選 2 個真正失敗案例的測試講解。
