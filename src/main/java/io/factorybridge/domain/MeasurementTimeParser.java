package io.factorybridge.domain;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

final class MeasurementTimeParser {
    private static final int MINIMUM_YEAR = 2000;
    private static final int MAXIMUM_YEAR = 2100;
    private static final DateTimeFormatter LEGACY_FORMAT =
            DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm:ss", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);
    private static final Map<String, ZoneId> LEGACY_SOURCE_ZONES =
            Map.of("MES_A", ZoneId.of("Asia/Taipei"), "MES_B", ZoneOffset.UTC);

    Instant parseMeasuredAt(String sourceSystem, String eventTime) {
        Instant measuredAt;
        try {
            measuredAt = parseOffsetTime(eventTime);
        } catch (DateTimeParseException ignored) {
            // 只有 ISO 語法不符時才嘗試約定的舊格式；絕不使用伺服器預設時區。
            measuredAt = parseLegacySourceTime(sourceSystem, eventTime);
        }
        // PostgreSQL timestamp 精度為微秒；在邊界先截斷，初次回應與讀回資料才會一致。
        return measuredAt.truncatedTo(ChronoUnit.MICROS);
    }

    private Instant parseOffsetTime(String eventTime) {
        OffsetDateTime parsed =
                OffsetDateTime.parse(
                        eventTime,
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withResolverStyle(
                                ResolverStyle.STRICT));
        validateYear(parsed.getYear());
        return parsed.toInstant();
    }

    private Instant parseLegacySourceTime(String sourceSystem, String eventTime) {
        try {
            LocalDateTime localTime = LocalDateTime.parse(eventTime, LEGACY_FORMAT);
            validateYear(localTime.getYear());
            ZoneId sourceZone = LEGACY_SOURCE_ZONES.get(sourceSystem);
            if (sourceZone == null
                    || sourceZone.getRules().getValidOffsets(localTime).size() != 1) {
                throw invalidEventTime();
            }
            return localTime.atZone(sourceZone).toInstant();
        } catch (DateTimeException exception) {
            throw new FactoryBridgeException(
                    ErrorCode.INVALID_EVENT_TIME, timeFormatMessage(), exception);
        }
    }

    private void validateYear(int year) {
        if (year < MINIMUM_YEAR || year > MAXIMUM_YEAR) {
            throw invalidEventTime();
        }
    }

    private FactoryBridgeException invalidEventTime() {
        return new FactoryBridgeException(ErrorCode.INVALID_EVENT_TIME, timeFormatMessage());
    }

    private String timeFormatMessage() {
        return "eventTime must be an ISO timestamp with an explicit offset or a source-specific "
                + "uuuu/MM/dd HH:mm:ss timestamp, with year between 2000 and 2100.";
    }
}
