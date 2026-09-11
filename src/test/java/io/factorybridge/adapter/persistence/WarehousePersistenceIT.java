package io.factorybridge.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import io.factorybridge.domain.QualityStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class WarehousePersistenceIT extends PostgresIntegrationSupport {
    @Test
    void repeatedWarehouseWritesUseMeasurementIdAsIdempotencyKey() {
        UUID measurementId = UUID.randomUUID();
        var canonical = measurement("warehouse-idempotency", QualityStatus.GOOD);
        warehouse.writeMeasurement(measurementId, canonical);
        warehouse.writeMeasurement(measurementId, canonical);

        assertThat(factCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT numeric_value FROM warehouse.fact_measurement",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("35.2");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_record_id FROM warehouse.fact_measurement",
                                String.class))
                .isEqualTo("warehouse-idempotency");
    }

    @Test
    void warehouseCommitSurvivesCallerRollbackSoRedeliveryMustBeIdempotent() {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        transaction -> {
                            warehouse.writeMeasurement(
                                    UUID.randomUUID(),
                                    measurement("independent-warehouse", QualityStatus.GOOD));
                            transaction.setRollbackOnly();
                        });
        assertThat(factCount()).isEqualTo(1);
    }

    @Test
    void translatesWarehouseFailureIntoStableInfrastructureErrorAndRetainsCause() {
        jdbc.execute(
                "ALTER TABLE warehouse.fact_measurement ADD CONSTRAINT test_failure CHECK (numeric_value < 0)");
        try {
            assertThatThrownBy(
                            () ->
                                    warehouse.writeMeasurement(
                                            UUID.randomUUID(),
                                            measurement("warehouse-failure", QualityStatus.GOOD)))
                    .isInstanceOfSatisfying(
                            FactoryBridgeException.class,
                            exception -> {
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.DATA_WAREHOUSE_WRITE_FAILED);
                                assertThat(exception.getCause()).isNotNull();
                            });
            assertThat(factCount()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE warehouse.fact_measurement DROP CONSTRAINT test_failure");
        }
    }

    private long factCount() {
        return jdbc.queryForObject("SELECT count(*) FROM warehouse.fact_measurement", Long.class);
    }
}
