package io.factorybridge.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

final class MetricValueNormalizer {
    private static final int CANONICAL_SCALE = 6;
    private static final BigDecimal MAXIMUM_ABSOLUTE_VALUE = new BigDecimal("100000000000000");
    private static final BigDecimal ABSOLUTE_ZERO_CELSIUS = new BigDecimal("-273.15");
    private static final BigDecimal ABSOLUTE_ZERO_FAHRENHEIT = new BigDecimal("-459.67");
    private static final BigDecimal FAHRENHEIT_FREEZING_POINT = new BigDecimal("32");
    private static final BigDecimal FAHRENHEIT_NUMERATOR = new BigDecimal("5");
    private static final BigDecimal FAHRENHEIT_DENOMINATOR = new BigDecimal("9");
    private static final BigDecimal PASCALS_PER_KILOPASCAL = new BigDecimal("1000");
    private static final BigDecimal KILOPASCALS_PER_BAR = new BigDecimal("100");
    private static final BigDecimal MAXIMUM_HUMIDITY = new BigDecimal("100");
    private static final Pattern DECIMAL_VALUE = Pattern.compile("[+-]?[0-9]+(?:\\.[0-9]+)?");

    NormalizedMetric normalizeMetricValue(String metricCode, String value, String unit) {
        MetricType metricType = identifyMetricType(metricCode);
        BigDecimal sourceValue = parseDecimalValue(value);
        BigDecimal convertedValue =
                switch (metricType) {
                    case TEMPERATURE -> normalizeTemperature(sourceValue, unit);
                    case PRESSURE -> normalizePressure(sourceValue, unit);
                    case HUMIDITY -> normalizeHumidity(sourceValue, unit);
                    case ROTATIONAL_SPEED -> normalizeRotationalSpeed(sourceValue, unit);
                };
        BigDecimal canonicalValue = convertedValue.setScale(CANONICAL_SCALE, RoundingMode.HALF_UP);
        // 檢查四捨五入後的值，因為接近上界的合法輸入仍可能進位而超出 numeric(20,6)。
        if (canonicalValue.abs().compareTo(MAXIMUM_ABSOLUTE_VALUE) >= 0) {
            throw invalidValue("Normalized measurement value exceeds numeric(20,6) capacity.");
        }
        return new NormalizedMetric(metricType, canonicalValue, standardUnit(metricType));
    }

    private MetricType identifyMetricType(String metricCode) {
        return switch (metricCode) {
            case "TEMP", "TEMPERATURE" -> MetricType.TEMPERATURE;
            case "PRESSURE" -> MetricType.PRESSURE;
            case "HUMIDITY" -> MetricType.HUMIDITY;
            case "RPM", "ROTATIONAL_SPEED" -> MetricType.ROTATIONAL_SPEED;
            default ->
                    throw new FactoryBridgeException(
                            ErrorCode.UNSUPPORTED_METRIC,
                            ErrorCode.UNSUPPORTED_METRIC.defaultMessage());
        };
    }

    private BigDecimal parseDecimalValue(String value) {
        if (!DECIMAL_VALUE.matcher(value).matches()) {
            throw invalidValue(
                    "Measurement value must be a plain decimal without grouping or exponent notation.");
        }
        return new BigDecimal(value);
    }

    private BigDecimal normalizeTemperature(BigDecimal value, String unit) {
        return switch (unit) {
            case "C" -> {
                requireMinimum(
                        value, ABSOLUTE_ZERO_CELSIUS, "Temperature cannot be below absolute zero.");
                yield value;
            }
            case "F" -> {
                requireMinimum(
                        value,
                        ABSOLUTE_ZERO_FAHRENHEIT,
                        "Temperature cannot be below absolute zero.");
                yield value.subtract(FAHRENHEIT_FREEZING_POINT)
                        .multiply(FAHRENHEIT_NUMERATOR)
                        .divide(FAHRENHEIT_DENOMINATOR, CANONICAL_SCALE, RoundingMode.HALF_UP);
            }
            default -> throw unsupportedUnit();
        };
    }

    private BigDecimal normalizePressure(BigDecimal value, String unit) {
        BigDecimal pressure =
                switch (unit) {
                    case "PA" -> value.divide(PASCALS_PER_KILOPASCAL);
                    case "KPA" -> value;
                    case "BAR" -> value.multiply(KILOPASCALS_PER_BAR);
                    default -> throw unsupportedUnit();
                };
        requireMinimum(pressure, BigDecimal.ZERO, "Absolute pressure cannot be negative.");
        return pressure;
    }

    private BigDecimal normalizeHumidity(BigDecimal value, String unit) {
        requireUnit(unit, "%");
        if (value.signum() < 0 || value.compareTo(MAXIMUM_HUMIDITY) > 0) {
            throw invalidValue("Relative humidity must be between 0 and 100 percent.");
        }
        return value;
    }

    private BigDecimal normalizeRotationalSpeed(BigDecimal value, String unit) {
        requireUnit(unit, "RPM");
        requireMinimum(value, BigDecimal.ZERO, "Rotational speed cannot be negative.");
        return value;
    }

    private String standardUnit(MetricType metricType) {
        return switch (metricType) {
            case TEMPERATURE -> "C";
            case PRESSURE -> "kPa";
            case HUMIDITY -> "%";
            case ROTATIONAL_SPEED -> "rpm";
        };
    }

    private void requireMinimum(BigDecimal value, BigDecimal minimum, String message) {
        if (value.compareTo(minimum) < 0) {
            throw invalidValue(message);
        }
    }

    private void requireUnit(String actualUnit, String expectedUnit) {
        if (!expectedUnit.equals(actualUnit)) {
            throw unsupportedUnit();
        }
    }

    private FactoryBridgeException unsupportedUnit() {
        return new FactoryBridgeException(
                ErrorCode.UNSUPPORTED_UNIT, ErrorCode.UNSUPPORTED_UNIT.defaultMessage());
    }

    private FactoryBridgeException invalidValue(String message) {
        return new FactoryBridgeException(ErrorCode.INVALID_MEASUREMENT_VALUE, message);
    }

    record NormalizedMetric(MetricType metricType, BigDecimal value, String standardUnit) {}
}
