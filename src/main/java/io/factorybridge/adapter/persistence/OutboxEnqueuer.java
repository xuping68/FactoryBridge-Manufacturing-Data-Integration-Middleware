package io.factorybridge.adapter.persistence;

import io.factorybridge.application.model.Destination;
import io.factorybridge.domain.QualityStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 同一個 operational transaction 建立送件意圖，避免 DB 成功但送件意圖遺失。 */
@Component
class OutboxEnqueuer {
    private final JdbcTemplate jdbc;

    OutboxEnqueuer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void enqueueDestinations(
            UUID measurementId, QualityStatus quality, String correlationId, Instant now) {
        enqueueDestination(measurementId, Destination.DATA_WAREHOUSE, correlationId, now);
        if (quality == QualityStatus.GOOD) {
            enqueueDestination(measurementId, Destination.DOWNSTREAM, correlationId, now);
        }
    }

    private void enqueueDestination(
            UUID measurementId, Destination destination, String correlationId, Instant now) {
        jdbc.update(
                """
                INSERT INTO factorybridge.delivery_outbox
                  (id, measurement_id, destination, status, next_attempt_at, correlation_id, created_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?)
                """,
                UUID.randomUUID(),
                measurementId,
                destination.name(),
                Timestamp.from(now),
                correlationId,
                Timestamp.from(now));
    }
}
