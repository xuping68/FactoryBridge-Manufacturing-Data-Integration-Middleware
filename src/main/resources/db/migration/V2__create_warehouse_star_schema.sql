-- Flyway 以同一個 transaction 執行此 migration；鎖住舊 Fact，避免快照後仍有舊版寫入。
LOCK TABLE warehouse.fact_measurement IN ACCESS EXCLUSIVE MODE;

-- 保留 V1 每筆原始維度屬性與 lot / batch，避免縮減分析模型時不可逆地丟失歷史資訊。
-- 這是一次性的升級封存表；新版 Writer 與分析查詢都不再寫入或讀取它。
CREATE TABLE warehouse.fact_measurement_v1_archive AS
SELECT * FROM warehouse.fact_measurement;

CREATE TABLE warehouse.dim_equipment (
    equipment_key bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    equipment_id varchar(128) NOT NULL,
    equipment_type varchar(128),
    plant_code varchar(128) NOT NULL,
    line_code varchar(128) NOT NULL,
    station_code varchar(128) NOT NULL,
    CONSTRAINT uq_dim_equipment_business_key UNIQUE (plant_code, equipment_id)
);

CREATE TABLE warehouse.dim_date (
    date_key integer PRIMARY KEY,
    full_date date NOT NULL UNIQUE,
    year integer NOT NULL,
    quarter integer NOT NULL,
    month integer NOT NULL,
    day integer NOT NULL,
    CONSTRAINT ck_dim_date_key CHECK (
        date_key = year * 10000 + month * 100 + day
    ),
    CONSTRAINT ck_dim_date_attributes CHECK (
        year = EXTRACT(YEAR FROM full_date)
        AND quarter = EXTRACT(QUARTER FROM full_date)
        AND month = EXTRACT(MONTH FROM full_date)
        AND day = EXTRACT(DAY FROM full_date)
    )
);

-- Type 0：同廠區同設備保留首次入倉屬性，不把後來的移線資訊套回歷史量測。
-- loaded_at 同時時以 measurement_id 固定順序，確保每次升級得到相同結果。
INSERT INTO warehouse.dim_equipment
    (equipment_id, equipment_type, plant_code, line_code, station_code)
SELECT DISTINCT ON (plant_code, equipment_id)
    equipment_id, equipment_type, plant_code, line_code, station_code
FROM warehouse.fact_measurement
ORDER BY plant_code, equipment_id, loaded_at, measurement_id;

-- 明確採 UTC 日期，不能讓執行 migration 的 session / server timezone 改變歸屬日。
INSERT INTO warehouse.dim_date (date_key, full_date, year, quarter, month, day)
SELECT
    EXTRACT(YEAR FROM full_date)::integer * 10000
        + EXTRACT(MONTH FROM full_date)::integer * 100
        + EXTRACT(DAY FROM full_date)::integer,
    full_date,
    EXTRACT(YEAR FROM full_date)::integer,
    EXTRACT(QUARTER FROM full_date)::integer,
    EXTRACT(MONTH FROM full_date)::integer,
    EXTRACT(DAY FROM full_date)::integer
FROM (
    SELECT DISTINCT (measured_at AT TIME ZONE 'UTC')::date AS full_date
    FROM warehouse.fact_measurement
) AS measurement_dates;

ALTER TABLE warehouse.fact_measurement
    ADD COLUMN equipment_key bigint,
    ADD COLUMN date_key integer;

UPDATE warehouse.fact_measurement AS fact
SET equipment_key = equipment.equipment_key,
    date_key = calendar.date_key
FROM warehouse.dim_equipment AS equipment, warehouse.dim_date AS calendar
WHERE equipment.plant_code = fact.plant_code
    AND equipment.equipment_id = fact.equipment_id
    AND calendar.full_date = (fact.measured_at AT TIME ZONE 'UTC')::date;

-- 回填完成才收緊 constraint；任一步失敗會 rollback 整次升級，不留下半套結構。
ALTER TABLE warehouse.fact_measurement
    ALTER COLUMN equipment_key SET NOT NULL,
    ALTER COLUMN date_key SET NOT NULL,
    ADD CONSTRAINT fk_fact_equipment FOREIGN KEY (equipment_key)
        REFERENCES warehouse.dim_equipment (equipment_key),
    ADD CONSTRAINT fk_fact_date FOREIGN KEY (date_key)
        REFERENCES warehouse.dim_date (date_key);

DROP INDEX warehouse.ix_fact_plant_time;

ALTER TABLE warehouse.fact_measurement
    DROP COLUMN equipment_id,
    DROP COLUMN equipment_type,
    DROP COLUMN plant_code,
    DROP COLUMN line_code,
    DROP COLUMN station_code,
    DROP COLUMN lot_number,
    DROP COLUMN batch_number;

CREATE INDEX ix_fact_date_equipment ON warehouse.fact_measurement (date_key, equipment_key);
CREATE INDEX ix_fact_equipment_time ON warehouse.fact_measurement (equipment_key, measured_at DESC);
