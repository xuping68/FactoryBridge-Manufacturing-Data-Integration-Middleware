package io.factorybridge.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.factorybridge.FactoryBridgeApplication;
import io.factorybridge.application.port.MeasurementStore;
import io.factorybridge.domain.QualityStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.Environment;

class ApplicationRestartIT extends PostgresIntegrationSupport {
    @Autowired private Environment environment;

    @Test
    void restartsAgainstExistingDataWithTheSameDatabaseUserAndMigrationHistory() {
        var accepted = accept("restart-retains-data", QualityStatus.GOOD);

        // 實際建立第二個 application context，不能只驗證首次空白 DB migration。
        // DB 帳號與 operational schema 同名，重現 PostgreSQL "$user" search_path 的陷阱。
        try (var restarted =
                new SpringApplicationBuilder(FactoryBridgeApplication.class)
                        .web(WebApplicationType.NONE)
                        .run(
                                "--spring.datasource.url="
                                        + environment.getRequiredProperty("spring.datasource.url"),
                                "--spring.datasource.username="
                                        + environment.getRequiredProperty(
                                                "spring.datasource.username"),
                                "--spring.datasource.password="
                                        + environment.getRequiredProperty(
                                                "spring.datasource.password"),
                                "--factorybridge.delivery.enabled=false")) {
            assertThat(restarted.getBean(Flyway.class).getConfiguration().getDefaultSchema())
                    .isEqualTo("public");
            assertThat(
                            restarted
                                    .getBean(MeasurementStore.class)
                                    .findMeasurement(accepted.measurementId()))
                    .isPresent();
            assertThat(restarted.getBean(Flyway.class).info().applied()).hasSize(2);
        }

        assertThat(canonicalCount()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(2);
    }
}
