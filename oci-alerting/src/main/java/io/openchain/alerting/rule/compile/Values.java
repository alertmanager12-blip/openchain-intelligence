package io.openchain.alerting.rule.compile;

import io.openchain.alerting.rule.FieldType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Normalisation of runtime field values to their catalog type. Numbers become BigDecimal (exact); floating point
 * runtime values (Double/Float) are refused on purpose: money is never represented as binary floating point.
 */
public final class Values {
    private Values() {}

    /** Max digits accepted when parsing a decimal from text, to keep comparisons cheap and bounded. */
    static final int MAX_NUMBER_TEXT = 100;

    /** @return the normalised value, or null if absent or not convertible to {@code type} */
    public static Object normalize(Object raw, FieldType type) {
        if (raw == null) return null;
        return switch (type) {
            case STRING -> raw instanceof String s ? s : (raw instanceof Enum<?> en ? en.name() : null);
            case DECIMAL, INTEGER -> toDecimal(raw);
            case BOOLEAN -> raw instanceof Boolean b ? b : null;
            case TIMESTAMP -> {
                if (raw instanceof Instant i) yield i;
                if (raw instanceof String s && s.length() <= 64) {
                    try { yield Instant.parse(s); } catch (DateTimeParseException ex) { yield null; }
                }
                yield null;
            }
        };
    }

    public static BigDecimal toDecimal(Object raw) {
        return switch (raw) {
            case BigDecimal d -> d;
            case BigInteger bi -> new BigDecimal(bi);
            case Long l -> BigDecimal.valueOf(l);
            case Integer i -> BigDecimal.valueOf(i);
            case Short s -> BigDecimal.valueOf(s);
            case String s -> parseDecimal(s);
            default -> null; // Double/Float deliberately rejected
        };
    }

    public static BigDecimal parseDecimal(String s) {
        if (s == null || s.isEmpty() || s.length() > MAX_NUMBER_TEXT) return null;
        try {
            BigDecimal d = new BigDecimal(s.trim());
            return Math.abs(d.scale()) > 1000 ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Stable, bounded textual form used for group keys, distinct counting and evidence. */
    public static String display(Object v) {
        if (v instanceof BigDecimal d) {
            BigDecimal s = d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
            return Math.abs(s.scale()) > 64 ? s.toString() : s.toPlainString();
        }
        String s = String.valueOf(v);
        return s.length() > 256 ? s.substring(0, 256) + "..." : s;
    }
}
