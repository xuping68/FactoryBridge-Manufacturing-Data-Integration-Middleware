package io.factorybridge.domain;

import static io.factorybridge.domain.MeasurementDraftFixtures.draftWith;
import static io.factorybridge.domain.MeasurementDraftFixtures.validDraft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MeasurementValidationTest {
    private final MeasurementNormalizer normalizer = new MeasurementNormalizer();

    @Test
    void rejectsMissingPayloadWithAStableValidationError() {
        FactoryBridgeException exception =
                assertThrows(
                        FactoryBridgeException.class, () -> normalizer.normalizeMeasurement(null));

        assertEquals(ErrorCode.INVALID_PAYLOAD, exception.errorCode());
        assertEquals(ErrorCategory.VALIDATION, exception.errorCode().category());
    }

    @ParameterizedTest
    @MethodSource("missingRequiredFields")
    void rejectsMissingOrBlankRequiredFields(String field, String value, ErrorCode expectedCode) {
        FactoryBridgeException exception =
                assertThrows(
                        FactoryBridgeException.class,
                        () -> normalizer.normalizeMeasurement(draftWith(field, value)));

        assertEquals(expectedCode, exception.errorCode());
        assertTrue(exception.getMessage().contains(field));
    }

    private static Stream<Arguments> missingRequiredFields() {
        Map<String, ErrorCode> fields =
                Map.ofEntries(
                        Map.entry("sourceSystem", ErrorCode.INVALID_SOURCE_SYSTEM),
                        Map.entry("sourceRecordId", ErrorCode.MISSING_REQUIRED_FIELD),
                        Map.entry("plantCode", ErrorCode.MISSING_REQUIRED_FIELD),
                        Map.entry("productionLine", ErrorCode.MISSING_REQUIRED_FIELD),
                        Map.entry("stationCode", ErrorCode.MISSING_REQUIRED_FIELD),
                        Map.entry("equipmentId", ErrorCode.MISSING_EQUIPMENT_ID),
                        Map.entry("metricCode", ErrorCode.UNSUPPORTED_METRIC),
                        Map.entry("value", ErrorCode.INVALID_MEASUREMENT_VALUE),
                        Map.entry("unit", ErrorCode.UNSUPPORTED_UNIT),
                        Map.entry("eventTime", ErrorCode.INVALID_EVENT_TIME),
                        Map.entry("qualityStatus", ErrorCode.INVALID_QUALITY_STATUS));
        return fields.entrySet().stream()
                .flatMap(
                        field ->
                                Stream.of(
                                        Arguments.of(field.getKey(), null, field.getValue()),
                                        Arguments.of(field.getKey(), " \t\n ", field.getValue())));
    }

    @ParameterizedTest
    @CsvSource({
        "sourceSystem,128",
        "sourceRecordId,128",
        "plantCode,128",
        "productionLine,128",
        "stationCode,128",
        "equipmentId,128",
        "equipmentType,128",
        "lotNumber,128",
        "batchNumber,128",
        "metricCode,128",
        "metricName,128",
        "unit,128",
        "qualityStatus,128",
        "operatorId,128",
        "value,64",
        "eventTime,64",
        "receivedAt,64"
    })
    void rejectsOversizedFieldsBeforePersistence(String field, int maximumLength) {
        FactoryBridgeException exception =
                assertThrows(
                        FactoryBridgeException.class,
                        () ->
                                normalizer.normalizeMeasurement(
                                        draftWith(field, "X".repeat(maximumLength + 1))));

        assertEquals(ErrorCode.FIELD_TOO_LONG, exception.errorCode());
        assertTrue(exception.getMessage().contains(field));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sourceRecordId",
                "plantCode",
                "productionLine",
                "stationCode",
                "equipmentId",
                "equipmentType",
                "lotNumber",
                "batchNumber",
                "metricName",
                "operatorId"
            })
    void acceptsIdentifiersAtTheDocumentedMaximumLength(String field) {
        normalizer.normalizeMeasurement(draftWith(field, "X".repeat(128)));
    }

    @ParameterizedTest
    @CsvSource({
        "sourceSystem, MES_C, INVALID_SOURCE_SYSTEM",
        "metricCode, VOLTAGE, UNSUPPORTED_METRIC",
        "qualityStatus, MAYBE, INVALID_QUALITY_STATUS"
    })
    void rejectsUnconfiguredSourceCodes(String field, String value, ErrorCode expectedCode) {
        FactoryBridgeException exception =
                assertThrows(
                        FactoryBridgeException.class,
                        () -> normalizer.normalizeMeasurement(draftWith(field, value)));

        assertEquals(expectedCode, exception.errorCode());
    }

    @ParameterizedTest
    @CsvSource({
        "OK,GOOD",
        "PASS,GOOD",
        "GOOD,GOOD",
        "WARN,WARNING",
        "WARNING,WARNING",
        "NG,BAD",
        "FAIL,BAD",
        "BAD,BAD",
        "pass,GOOD"
    })
    void mapsSourceQualityCodesToCanonicalMeaning(
            String sourceQuality, QualityStatus expectedStatus) {
        assertEquals(
                expectedStatus,
                normalizer
                        .normalizeMeasurement(draftWith("qualityStatus", sourceQuality))
                        .qualityStatus());
    }

    @Test
    void convertsBlankOptionalMetadataToNull() {
        CanonicalMeasurement result =
                normalizer.normalizeMeasurement(
                        draftWith(
                                Map.of(
                                        "equipmentType",
                                        " ",
                                        "lotNumber",
                                        "\t",
                                        "batchNumber",
                                        " ",
                                        "metricName",
                                        " ",
                                        "operatorId",
                                        " ",
                                        "receivedAt",
                                        " ")));

        assertNull(result.equipmentType());
        assertNull(result.lotNumber());
        assertNull(result.batchNumber());
    }

    @Test
    @ResourceLock("java.util.Locale.default")
    void cleansCodesWithoutChangingCaseSensitiveKeysOrUsingDefaultLocale() {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            CanonicalMeasurement result =
                    normalizer.normalizeMeasurement(
                            draftWith(
                                    Map.of(
                                            "sourceSystem",
                                            " mes_a ",
                                            "sourceRecordId",
                                            " MiXeD-001 ",
                                            "plantCode",
                                            " ki01 ",
                                            "productionLine",
                                            " line-i ",
                                            "stationCode",
                                            " station-i ",
                                            "equipmentId",
                                            " eq-i ",
                                            "lotNumber",
                                            " lot-Mixed ",
                                            "unit",
                                            " f ",
                                            "qualityStatus",
                                            " pass ",
                                            "metricCode",
                                            " temp ")));

            assertEquals("MES_A", result.source());
            assertEquals("MiXeD-001", result.sourceRecordId());
            assertEquals("KI01", result.plantCode());
            assertEquals("LINE-I", result.lineCode());
            assertEquals("STATION-I", result.stationCode());
            assertEquals("EQ-I", result.equipmentId());
            assertEquals("lot-Mixed", result.lotNumber());
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    void retainsSourceEvidenceAndProducesTheSameCanonicalValueOnReplay() {
        MeasurementDraft original = draftWith("equipmentId", " eq-ct-003 ");

        CanonicalMeasurement first = normalizer.normalizeMeasurement(original);
        CanonicalMeasurement replay = normalizer.normalizeMeasurement(original);

        assertEquals(" eq-ct-003 ", original.equipmentId());
        assertEquals("EQ-CT-003", first.equipmentId());
        assertEquals(first, replay);
        assertNotSame(first, replay);
        assertTrue(MeasurementDraft.class.isRecord());
        assertTrue(CanonicalMeasurement.class.isRecord());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EQ\u0000ID", "EQ\nID", "EQ\tID", "EQ\uD800ID"})
    void rejectsTextThatCannotBeSafelyStoredOrLogged(String equipmentId) {
        FactoryBridgeException exception =
                assertThrows(
                        FactoryBridgeException.class,
                        () ->
                                normalizer.normalizeMeasurement(
                                        draftWith("equipmentId", equipmentId)));

        assertEquals(ErrorCode.INVALID_PAYLOAD, exception.errorCode());
    }

    @Test
    void preservesSupplementaryUnicodeInOptionalDescriptions() {
        normalizer.normalizeMeasurement(draftWith("metricName", "溫度感測器 🌡"));
    }

    @Test
    void sourceReceivedAtNeverOverridesMeasurementEventTime() {
        CanonicalMeasurement expected = normalizer.normalizeMeasurement(validDraft());
        CanonicalMeasurement result =
                normalizer.normalizeMeasurement(
                        draftWith("receivedAt", "2026-09-10T15:00:00+08:00"));

        assertEquals(expected.measuredAt(), result.measuredAt());
    }
}
