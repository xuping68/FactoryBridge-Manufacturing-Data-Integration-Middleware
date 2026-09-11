package io.factorybridge.adapter.persistence;

import io.factorybridge.application.model.Delivery;
import io.factorybridge.application.port.DeliveryStore;
import io.factorybridge.domain.ErrorCode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeliveryStore implements DeliveryStore {
    private static final DeliveryRowMapper DELIVERY_ROW_MAPPER = new DeliveryRowMapper();
    private final JdbcTemplate jdbc;
    private final PersistenceTransactions transactions;

    public JdbcDeliveryStore(JdbcTemplate jdbc, PersistenceTransactions transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public List<Delivery> claimDueDeliveries(Instant now, Duration leaseDuration, int limit) {
        if (leaseDuration.isNegative() || leaseDuration.isZero() || limit < 1) {
            throw new IllegalArgumentException(
                    "A positive lease duration and claim limit are required.");
        }
        // row lock 僅持有於認領交易；昂貴的 I/O 在提交後執行。
        // 每次認領產生新 token，過期 worker 的回報將無法符合更新條件。
        return transactions.write(
                () ->
                        jdbc.query(
                                """
                WITH due AS (
                    SELECT id FROM factorybridge.delivery_outbox
                    WHERE (status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?)
                       OR (status = 'IN_FLIGHT' AND lease_expires_at <= ?)
                    ORDER BY next_attempt_at, id
                    LIMIT ? FOR UPDATE SKIP LOCKED
                ), claimed AS (
                    UPDATE factorybridge.delivery_outbox delivery
                    SET status = 'IN_FLIGHT', lease_token = gen_random_uuid(), lease_expires_at = ?,
                        attempt_count = attempt_count + 1, total_attempts = total_attempts + 1
                    FROM due WHERE delivery.id = due.id
                    RETURNING delivery.*
                )
                SELECT * FROM claimed ORDER BY next_attempt_at, id
                """,
                                DELIVERY_ROW_MAPPER,
                                Timestamp.from(now),
                                Timestamp.from(now),
                                limit,
                                Timestamp.from(now.plus(leaseDuration))));
    }

    @Override
    public boolean markDelivered(UUID deliveryId, UUID leaseToken, Instant deliveredAt) {
        return transactions.write(
                () ->
                        jdbc.update(
                                        """
                UPDATE factorybridge.delivery_outbox
                SET status = 'DELIVERED', delivered_at = ?, lease_token = NULL, lease_expires_at = NULL,
                    last_error_code = NULL, last_error_message = NULL
                WHERE id = ? AND status = 'IN_FLIGHT' AND lease_token = ?
                """,
                                        Timestamp.from(deliveredAt),
                                        deliveryId,
                                        leaseToken)
                                == 1);
    }

    @Override
    public boolean markFailed(
            UUID deliveryId,
            UUID leaseToken,
            ErrorCode errorCode,
            String message,
            Instant nextAttemptAt,
            boolean dead) {
        return transactions.write(
                () ->
                        jdbc.update(
                                        """
                UPDATE factorybridge.delivery_outbox
                SET status = ?, next_attempt_at = ?, last_error_code = ?, last_error_message = LEFT(?, 512),
                    lease_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND status = 'IN_FLIGHT' AND lease_token = ?
                """,
                                        dead ? "DEAD" : "RETRY",
                                        Timestamp.from(nextAttemptAt),
                                        errorCode.name(),
                                        message,
                                        deliveryId,
                                        leaseToken)
                                == 1);
    }

    @Override
    public Optional<Delivery> findDelivery(UUID deliveryId) {
        return transactions.read(
                () ->
                        jdbc
                                .query(
                                        "SELECT * FROM factorybridge.delivery_outbox WHERE id = ?",
                                        DELIVERY_ROW_MAPPER,
                                        deliveryId)
                                .stream()
                                .findFirst());
    }

    @Override
    public List<Delivery> listDeliveries(UUID measurementId) {
        return transactions.read(
                () ->
                        jdbc.query(
                                """
                SELECT * FROM factorybridge.delivery_outbox
                WHERE measurement_id = ? ORDER BY created_at, id
                """,
                                DELIVERY_ROW_MAPPER,
                                measurementId));
    }

    @Override
    public boolean replayDeadDelivery(UUID deliveryId, Instant now) {
        // 保留累計嘗試與最後錯誤供追查；新一輪只有 retry budget 重新計算。
        return transactions.write(
                () ->
                        jdbc.update(
                                        """
                UPDATE factorybridge.delivery_outbox
                SET status = 'PENDING', attempt_count = 0, replay_count = replay_count + 1, next_attempt_at = ?
                WHERE id = ? AND status = 'DEAD'
                """,
                                        Timestamp.from(now),
                                        deliveryId)
                                == 1);
    }
}
