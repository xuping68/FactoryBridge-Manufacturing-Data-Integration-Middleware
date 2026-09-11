CREATE SCHEMA IF NOT EXISTS factorybridge;
CREATE SCHEMA IF NOT EXISTS warehouse;

CREATE TABLE factorybridge.measurement (
    id uuid PRIMARY KEY,
    equipment_id varchar(128) NOT NULL,
    equipment_type varchar(128),
    plant_code varchar(128) NOT NULL,
    line_code varchar(128) NOT NULL,
    station_code varchar(128) NOT NULL,
    lot_number varchar(128),
    batch_number varchar(128),
    metric_type varchar(32) NOT NULL CHECK (metric_type IN ('TEMPERATURE', 'PRESSURE', 'HUMIDITY', 'ROTATIONAL_SPEED')),
    numeric_value numeric(20,6) NOT NULL CHECK (numeric_value <> 'NaN'::numeric),
    standard_unit varchar(16) NOT NULL,
    measured_at timestamptz NOT NULL,
    quality_status varchar(16) NOT NULL CHECK (quality_status IN ('GOOD', 'WARNING', 'BAD')),
    source varchar(128) NOT NULL,
    source_record_id varchar(128) NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_measurement_source_record UNIQUE (source, source_record_id),
    CONSTRAINT ck_measurement_standard_unit CHECK (
        (metric_type = 'TEMPERATURE' AND standard_unit = 'C')
        OR (metric_type = 'PRESSURE' AND standard_unit = 'kPa')
        OR (metric_type = 'HUMIDITY' AND standard_unit = '%')
        OR (metric_type = 'ROTATIONAL_SPEED' AND standard_unit = 'rpm')
    )
);
CREATE INDEX ix_measurement_created ON factorybridge.measurement (created_at DESC, id DESC);
CREATE INDEX ix_measurement_equipment_time ON factorybridge.measurement (equipment_id, measured_at DESC);

-- bytea 保存 UTF-8 原文，包含 PostgreSQL text 無法儲存的 NUL。
-- JSON 格式是否有效留給後續驗證，不能因為驗證失敗而遺失原始資料。
CREATE TABLE factorybridge.staging_record (
    id uuid PRIMARY KEY,
    raw_payload bytea NOT NULL,
    correlation_id varchar(128) NOT NULL,
    received_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('RECEIVED', 'ACCEPTED', 'DUPLICATE', 'REJECTED')),
    measurement_id uuid REFERENCES factorybridge.measurement(id),
    error_code varchar(64),
    error_message varchar(512),
    expected_source varchar(128),
    expected_source_record_id varchar(128),
    -- raw 先提交後即使程式中斷，仍有足夠資訊核對 import 的來源身分。
    CONSTRAINT ck_staging_expected_identity CHECK (
        (expected_source IS NULL) = (expected_source_record_id IS NULL)
    ),
    CONSTRAINT ck_staging_outcome CHECK (
        (status IN ('ACCEPTED', 'DUPLICATE') AND measurement_id IS NOT NULL AND error_code IS NULL)
        OR (status = 'RECEIVED' AND measurement_id IS NULL AND error_code IS NULL)
        OR (status = 'REJECTED' AND measurement_id IS NULL AND error_code IS NOT NULL)
    )
);
CREATE INDEX ix_staging_received ON factorybridge.staging_record (received_at DESC, id DESC);
CREATE INDEX ix_staging_correlation ON factorybridge.staging_record (correlation_id);

CREATE TABLE factorybridge.delivery_outbox (
    id uuid PRIMARY KEY,
    measurement_id uuid NOT NULL REFERENCES factorybridge.measurement(id),
    destination varchar(32) NOT NULL CHECK (destination IN ('DATA_WAREHOUSE', 'DOWNSTREAM')),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'RETRY', 'DELIVERED', 'DEAD')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    total_attempts integer NOT NULL DEFAULT 0 CHECK (total_attempts >= attempt_count),
    replay_count integer NOT NULL DEFAULT 0 CHECK (replay_count >= 0),
    next_attempt_at timestamptz NOT NULL,
    lease_token uuid,
    lease_expires_at timestamptz,
    correlation_id varchar(128) NOT NULL,
    last_error_code varchar(64),
    last_error_message varchar(512),
    created_at timestamptz NOT NULL,
    delivered_at timestamptz,
    CONSTRAINT uq_delivery_destination UNIQUE (measurement_id, destination),
    CONSTRAINT ck_delivery_lease CHECK (
        (status = 'IN_FLIGHT' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_token IS NULL AND lease_expires_at IS NULL)
    ),
    CONSTRAINT ck_delivery_completion CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL))
);
CREATE INDEX ix_outbox_due ON factorybridge.delivery_outbox (next_attempt_at, id)
    WHERE status IN ('PENDING', 'RETRY');
CREATE INDEX ix_outbox_expired_lease ON factorybridge.delivery_outbox (lease_expires_at, id)
    WHERE status = 'IN_FLIGHT';
CREATE INDEX ix_outbox_measurement ON factorybridge.delivery_outbox (measurement_id, created_at, id);

-- Demo 共用 PostgreSQL cluster，以獨立 schema / transaction 展示倉儲邊界。
-- 刻意不設跨 schema FK，倉儲讀取不應依賴 operational table 的生命週期。
CREATE TABLE warehouse.fact_measurement (
    measurement_id uuid PRIMARY KEY,
    equipment_id varchar(128) NOT NULL,
    equipment_type varchar(128),
    plant_code varchar(128) NOT NULL,
    line_code varchar(128) NOT NULL,
    station_code varchar(128) NOT NULL,
    lot_number varchar(128),
    batch_number varchar(128),
    metric_type varchar(32) NOT NULL CHECK (metric_type IN ('TEMPERATURE', 'PRESSURE', 'HUMIDITY', 'ROTATIONAL_SPEED')),
    numeric_value numeric(20,6) NOT NULL CHECK (numeric_value <> 'NaN'::numeric),
    standard_unit varchar(16) NOT NULL,
    measured_at timestamptz NOT NULL,
    quality_status varchar(16) NOT NULL CHECK (quality_status IN ('GOOD', 'WARNING', 'BAD')),
    source varchar(128) NOT NULL,
    source_record_id varchar(128) NOT NULL,
    loaded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_fact_standard_unit CHECK (
        (metric_type = 'TEMPERATURE' AND standard_unit = 'C')
        OR (metric_type = 'PRESSURE' AND standard_unit = 'kPa')
        OR (metric_type = 'HUMIDITY' AND standard_unit = '%')
        OR (metric_type = 'ROTATIONAL_SPEED' AND standard_unit = 'rpm')
    )
);
CREATE INDEX ix_fact_plant_time ON warehouse.fact_measurement (plant_code, measured_at DESC);
