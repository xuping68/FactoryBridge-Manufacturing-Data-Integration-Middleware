-- 以 UTC 量測日期分析「每天每台設備有多少筆異常量測」。
-- 保留 equipment_key 分組，避免不同廠區的同名設備被合併；沒有異常的組合不輸出零值列。
SELECT
    measurement_date.full_date AS date,
    equipment.equipment_id,
    equipment.equipment_type,
    equipment.plant_code,
    COUNT(*) AS abnormal_count
FROM warehouse.fact_measurement AS measurement
JOIN warehouse.dim_equipment AS equipment
    ON equipment.equipment_key = measurement.equipment_key
JOIN warehouse.dim_date AS measurement_date
    ON measurement_date.date_key = measurement.date_key
WHERE measurement.quality_status IN ('WARNING', 'BAD')
GROUP BY
    measurement_date.full_date,
    equipment.equipment_key,
    equipment.equipment_id,
    equipment.equipment_type,
    equipment.plant_code
ORDER BY
    measurement_date.full_date,
    equipment.plant_code,
    equipment.equipment_id;
