package io.factorybridge.adapter.http.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JacksonMeasurementPayloadDecoderTest {
    private final JacksonMeasurementPayloadDecoder decoder =
            new JacksonMeasurementPayloadDecoder(new ObjectMapper());

    @Test
    void mapsSourceFieldsExplicitlyWithoutCleaningAwayRawMeaning() {
        var draft =
                decoder.decodeMeasurement(
                        """
                {"sourceSystem":"MES_A","sourceRecordId":"r1","plantCode":"KH01",
                 "productionLine":"CELL-LINE-01","stationCode":"COATING-03","equipmentId":" EQ-1 ",
                 "equipmentType":"COATING_MACHINE","lotNumber":"LOT-1","batchNumber":"BATCH-1",
                 "metricCode":"TEMP","metricName":"Chamber Temperature","value":"95.36","unit":"F",
                 "eventTime":"2026/09/10 14:30:22","qualityStatus":"OK","operatorId":"OP1",
                 "receivedAt":"2026-09-10T06:30:25Z","vendorExtension":{"version":2}}
                """);

        assertThat(draft.equipmentId()).isEqualTo(" EQ-1 ");
        assertThat(draft.productionLine()).isEqualTo("CELL-LINE-01");
        assertThat(draft.value()).isEqualTo("95.36");
        assertThat(draft.operatorId()).isEqualTo("OP1");
        assertThat(draft.receivedAt()).isEqualTo("2026-09-10T06:30:25Z");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "null",
                "42",
                "{}{}",
                "{broken",
                "{\"value\":12}",
                "{\"value\":12.5}",
                "{\"equipmentId\":true}",
                "{\"value\":[\"12\"]}",
                "{\"unit\":\"F\",\"unit\":\"C\"}",
                "{\"unknown\":{\"value\":1,\"value\":2}}"
            })
    void rejectsAmbiguousOrUnexpectedExternalContracts(String payload) {
        assertThatThrownBy(() -> decoder.decodeMeasurement(payload))
                .isInstanceOfSatisfying(
                        FactoryBridgeException.class,
                        exception ->
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.INVALID_PAYLOAD));
    }

    @Test
    void canonicalFingerprintIgnoresWhitespaceAndRecursivelySortsObjectKeys() {
        String first = "{\"value\":\"35.2\",\"extension\":{\"b\":2,\"a\":[{\"d\":4,\"c\":3}]}}";
        String reordered =
                "{ \"extension\": {\"a\":[{\"c\":3,\"d\":4}],\"b\":2}, \"value\":\"35.2\" }";

        assertThat(decoder.fingerprintPayload(first))
                .hasSize(64)
                .isEqualTo(decoder.fingerprintPayload(reordered));
    }

    @Test
    void fingerprintKeepsUnknownFieldsStringTypesAndArrayOrder() {
        String original = decoder.fingerprintPayload("{\"value\":\"35.2\",\"extra\":[1,2]}");

        assertThat(original)
                .isNotEqualTo(decoder.fingerprintPayload("{\"value\":35.2,\"extra\":[1,2]}"));
        assertThat(original)
                .isNotEqualTo(decoder.fingerprintPayload("{\"value\":\"35.2\",\"extra\":[2,1]}"));
        assertThat(original)
                .isNotEqualTo(decoder.fingerprintPayload("{\"value\":\"35.2\",\"extra\":[1,3]}"));
        assertThat(original).isNotEqualTo(decoder.fingerprintPayload("{\"value\":\"35.2\"}"));
    }

    @Test
    void fingerprintDoesNotCollapseHighPrecisionUnknownNumbers() {
        assertThat(decoder.fingerprintPayload("{\"extra\":0.123456789012345678901}"))
                .isNotEqualTo(decoder.fingerprintPayload("{\"extra\":0.123456789012345678902}"));
    }

    @Test
    void boundsUnknownExtensionNestingBeforeRecursiveFingerprinting() {
        String nested = "{\"extension\":".repeat(101) + "null" + "}".repeat(101);

        assertThatThrownBy(() -> decoder.fingerprintPayload(nested))
                .isInstanceOfSatisfying(
                        FactoryBridgeException.class,
                        exception ->
                                assertThat(exception.errorCode())
                                        .isEqualTo(ErrorCode.INVALID_PAYLOAD));
    }
}
