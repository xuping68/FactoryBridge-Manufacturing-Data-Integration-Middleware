package io.factorybridge.adapter.http.external;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.factorybridge.application.port.MeasurementPayloadDecoder;
import io.factorybridge.domain.ErrorCode;
import io.factorybridge.domain.FactoryBridgeException;
import io.factorybridge.domain.MeasurementDraft;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public final class JacksonMeasurementPayloadDecoder implements MeasurementPayloadDecoder {
    private final ObjectMapper externalMapper;
    private final ExternalMeasurementMapper measurementMapper = new ExternalMeasurementMapper();

    public JacksonMeasurementPayloadDecoder(ObjectMapper objectMapper) {
        // 局部嚴格設定不影響其餘 API 的 Jackson 行為，也不將數字悄悄轉成來源字串。
        this.externalMapper =
                objectMapper
                        .copy()
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // 未知 extension 可以演進，但仍需防止極深 JSON 消耗不受控的 stack／解析資源。
        externalMapper
                .getFactory()
                .setStreamReadConstraints(
                        StreamReadConstraints.builder()
                                .maxNestingDepth(100)
                                .maxNumberLength(1000)
                                .maxStringLength(65_536)
                                .build());
        externalMapper
                .coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    @Override
    public MeasurementDraft decodeMeasurement(String rawPayload) {
        try {
            ExternalMeasurementDto external =
                    externalMapper.treeToValue(
                            readPayloadObject(rawPayload), ExternalMeasurementDto.class);
            return measurementMapper.toMeasurementDraft(external);
        } catch (JsonProcessingException exception) {
            throw invalidPayload(exception);
        }
    }

    @Override
    public String fingerprintPayload(String rawPayload) {
        try {
            // 僅消除物件鍵值順序和 JSON 空白差異；未知欄位、陣列順序與值仍參與比對。
            byte[] canonicalJson =
                    externalMapper.writeValueAsBytes(sortObjectKeys(readPayloadObject(rawPayload)));
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson));
        } catch (JsonProcessingException exception) {
            throw invalidPayload(exception);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 為 Java 必備演算法，缺失代表執行環境故障，不能誤報為使用者資料錯誤。
            throw new IllegalStateException(
                    "Required SHA-256 algorithm is unavailable.", exception);
        }
    }

    private JsonNode readPayloadObject(String rawPayload) throws JsonProcessingException {
        if (rawPayload == null || rawPayload.isBlank()) {
            throw new FactoryBridgeException(
                    ErrorCode.INVALID_PAYLOAD, "Measurement payload must be a JSON object.");
        }
        JsonNode node = externalMapper.readTree(rawPayload);
        if (node == null || !node.isObject()) {
            throw new FactoryBridgeException(
                    ErrorCode.INVALID_PAYLOAD, "Measurement payload must be a JSON object.");
        }
        return node;
    }

    private JsonNode sortObjectKeys(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = externalMapper.createObjectNode();
            TreeSet<String> fieldNames = new TreeSet<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            fieldNames.forEach(name -> sorted.set(name, sortObjectKeys(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode ordered = externalMapper.createArrayNode();
            node.forEach(element -> ordered.add(sortObjectKeys(element)));
            return ordered;
        }
        return node;
    }

    private FactoryBridgeException invalidPayload(JsonProcessingException exception) {
        return new FactoryBridgeException(
                ErrorCode.INVALID_PAYLOAD,
                "Measurement payload must contain one JSON object with unique keys and string measurement fields.",
                exception);
    }
}
