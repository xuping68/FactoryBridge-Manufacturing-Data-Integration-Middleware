package io.factorybridge.domain;

import static io.factorybridge.domain.MeasurementDraftFixtures.draftWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MetricNormalizationTest {
    private final MeasurementNormalizer normalizer = new MeasurementNormalizer();

    @ParameterizedTest(name = "{0} {1} {2} becomes {3} {4}")
    @CsvSource({
        "TEMP, 95.36, F, 35.200000, C, TEMPERATURE",
        "TEMP, 32, F, 0.000000, C, TEMPERATURE",
        "TEMP, 212, F, 100.000000, C, TEMPERATURE",
        "TEMP, -40, F, -40.000000, C, TEMPERATURE",
        "TEMPERATURE, 35.2, C, 35.200000, C, TEMPERATURE",
        "TEMP, -273.15, C, -273.150000, C, TEMPERATURE",
        "TEMP, -459.67, F, -273.150000, C, TEMPERATURE",
        "TEMP, 33, F, 0.555556, C, TEMPERATURE",
        "TEMP, 31, F, -0.555556, C, TEMPERATURE",
        "TEMP, 35.1234565, C, 35.123457, C, TEMPERATURE",
        "TEMP, -35.1234565, C, -35.123457, C, TEMPERATURE",
        "PRESSURE, 101325, PA, 101.325000, kPa, PRESSURE",
        "PRESSURE, 101.325, KPA, 101.325000, kPa, PRESSURE",
        "PRESSURE, 1.01325, BAR, 101.325000, kPa, PRESSURE",
        "PRESSURE, 0, PA, 0.000000, kPa, PRESSURE",
        "HUMIDITY, 45.2, %, 45.200000, %, HUMIDITY",
        "HUMIDITY, 0, %, 0.000000, %, HUMIDITY",
        "HUMIDITY, 100, %, 100.000000, %, HUMIDITY",
        "RPM, 1800, rpm, 1800.000000, rpm, ROTATIONAL_SPEED",
        "ROTATIONAL_SPEED, 0, RPM, 0.000000, rpm, ROTATIONAL_SPEED",
        "RPM, +00012.25, RPM, 12.250000, rpm, ROTATIONAL_SPEED",
        "RPM, 99999999999999.999999, RPM, 99999999999999.999999, rpm, ROTATIONAL_SPEED"
    })
    void convertsKnownUnitsWithExactDecimalPrecision(
            String metric,
            String value,
            String unit,
            String expectedValue,
            String expectedUnit,
            MetricType expectedMetricType) {
        CanonicalMeasurement result =
                normalizer.normalizeMeasurement(
                        draftWith(Map.of("metricCode", metric, "value", value, "unit", unit)));

        assertEquals(new BigDecimal(expectedValue), result.numericValue());
        assertEquals(expectedUnit, result.standardUnit());
        assertEquals(expectedMetricType, result.metricType());
    }

    @ParameterizedTest
    @CsvSource({
        "TEMP, -273.150001, C",
        "TEMP, -459.670001, F",
        "PRESSURE, -0.0000001, PA",
        "HUMIDITY, -0.0000001, %",
        "HUMIDITY, 100.0000001, %",
        "RPM, -0.0000001, rpm",
        "RPM, 100000000000000, rpm",
        "RPM, 99999999999999.9999995, rpm",
        "PRESSURE, 1000000000000, BAR"
    })
    void rejectsImpossibleOrUnstorableMeasurementsBeforeTheyReachPersistence(
            String metric, String value, String unit) {
        FactoryBridgeException exception =
                assertThrows(
                        FactoryBridgeException.class,
                        () ->
                                normalizer.normalizeMeasurement(
                                        draftWith(
                                                Map.of(
                                                        "metricCode",
                                                        metric,
                                                        "value",
                                                        value,
                                                        "unit",
                                                        unit))));

        assertEquals(ErrorCode.INVALID_MEASUREMENT_VALUE, exception.errorCode());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "NaN",
                "Infinity",
                "-Infinity",
                "1e3",
                "1E+3",
                "1,234",
                "35,2",
                ".5",
                "5.",
                "0xFF",
                "1_000",
                "12 34",
                "--1",
                "１２.５",
                "+"
            })
    void rejectsAmbiguousNumericRepresentations(String value) {
        FactoryBridgeException exception =
                assertThrows(
                        FactoryBridgeException.class,
                        () -> normalizer.normalizeMeasurement(draftWith("value", value)));

        assertEquals(ErrorCode.INVALID_MEASUREMENT_VALUE, exception.errorCode());
    }

    @ParameterizedTest
    @CsvSource({"TEMP, K", "PRESSURE, PSI", "HUMIDITY, C", "RPM, HZ"})
    void rejectsUnitsThatDoNotBelongToTheMetric(String metric, String unit) {
        FactoryBridgeException exception =
                assertThrows(
                        FactoryBridgeException.class,
                        () ->
                                normalizer.normalizeMeasurement(
                                        draftWith(Map.of("metricCode", metric, "unit", unit))));

        assertEquals(ErrorCode.UNSUPPORTED_UNIT, exception.errorCode());
    }
}
