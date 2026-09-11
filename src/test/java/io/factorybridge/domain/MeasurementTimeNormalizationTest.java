package io.factorybridge.domain;

import static io.factorybridge.domain.MeasurementDraftFixtures.draftWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MeasurementTimeNormalizationTest {
    private final MeasurementNormalizer normalizer = new MeasurementNormalizer();

    @ParameterizedTest
    @CsvSource({
        "MES_A, 2026/09/10 14:30:22, 2026-09-10T06:30:22Z",
        "MES_B, 2026/09/10 14:30:22, 2026-09-10T14:30:22Z",
        "MES_A, 2026-09-10T14:30:22+08:00, 2026-09-10T06:30:22Z",
        "MES_B, 2026-09-10T14:30:22+08:00, 2026-09-10T06:30:22Z",
        "MES_A, 2026-09-10T14:30:22Z, 2026-09-10T14:30:22Z",
        "MES_A, 2026-09-10T14:30:22-04:00, 2026-09-10T18:30:22Z",
        "MES_A, 2000/02/29 14:30:22, 2000-02-29T06:30:22Z",
        "MES_A, 2100/12/31 14:30:22, 2100-12-31T06:30:22Z",
        "MES_A, 2026-09-10T14:30:22.123456+08:00, 2026-09-10T06:30:22.123456Z",
        "MES_A, 2026-09-10T14:30:22.123456789+08:00, 2026-09-10T06:30:22.123456Z",
        "MES_A, 2026-09-10T14:30:22.999999999+08:00, 2026-09-10T06:30:22.999999Z"
    })
    void normalizesSourceTimeToUtcWithoutUsingMachineTimezone(
            String source, String time, String expected) {
        CanonicalMeasurement result =
                normalizer.normalizeMeasurement(
                        draftWith(Map.of("sourceSystem", source, "eventTime", time)));

        assertEquals(Instant.parse(expected), result.measuredAt());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "2026/02/29 14:30:22",
                "2026/04/31 14:30:22",
                "2026/00/10 14:30:22",
                "2026/09/00 14:30:22",
                "2026/09/10 24:00:00",
                "2026/09/10 14:30:60",
                "2026/9/10 14:30:22",
                "2026-09-10 14:30:22",
                "2026-09-10T14:30:22",
                "2026-02-29T14:30:22Z",
                "2026-09-10T24:00:00Z",
                "2026-09-10T14:30:60Z",
                "2026-09-10T14:30:22+25:00",
                "2026-09-10T14:30:22+08:60",
                "2026-09-10T14:30:22+18:01",
                "2026-09-10T14:30:22Z[Asia/Taipei]",
                "1999/12/31 23:59:59",
                "2101/01/01 00:00:00",
                "2100/02/29 00:00:00",
                "1999-12-31T23:59:59Z",
                "2101-01-01T00:00:00Z",
                "+12026-09-10T14:30:22Z",
                "not-a-time",
                "2026-09-10"
            })
    void rejectsInvalidCalendarValuesAmbiguousLocalTimesAndOutOfRangeYears(String eventTime) {
        FactoryBridgeException exception =
                assertThrows(
                        FactoryBridgeException.class,
                        () -> normalizer.normalizeMeasurement(draftWith("eventTime", eventTime)));

        assertEquals(ErrorCode.INVALID_EVENT_TIME, exception.errorCode());
    }
}
