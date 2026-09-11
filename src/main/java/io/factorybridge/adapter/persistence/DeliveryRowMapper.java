package io.factorybridge.adapter.persistence;

import io.factorybridge.application.model.Delivery;
import io.factorybridge.application.model.Destination;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** 所有 outbox 查詢共用同一個明確映射，避免新增狀態欄位時只更新部分查詢。 */
final class DeliveryRowMapper implements RowMapper<Delivery> {
    @Override
    public Delivery mapRow(ResultSet row, int rowNumber) throws SQLException {
        return new Delivery(
                row.getObject("id", UUID.class),
                row.getObject("measurement_id", UUID.class),
                Destination.valueOf(row.getString("destination")),
                row.getString("status"),
                row.getInt("attempt_count"),
                row.getInt("total_attempts"),
                row.getInt("replay_count"),
                row.getTimestamp("next_attempt_at").toInstant(),
                row.getObject("lease_token", UUID.class),
                row.getString("correlation_id"),
                row.getString("last_error_code"),
                row.getString("last_error_message"));
    }
}
