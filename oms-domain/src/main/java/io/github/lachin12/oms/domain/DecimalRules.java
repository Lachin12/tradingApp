package io.github.lachin12.oms.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact fixed-scale values compatible with PostgreSQL NUMERIC(28,8). */
public final class DecimalRules {
    public static final int PRECISION = 28;
    public static final int SCALE = 8;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE);

    private DecimalRules() { }

    public static BigDecimal positive(BigDecimal value, String field) {
        required(value, field);
        // Reject compact extreme exponents before setScale can expand their coefficient.
        long integerDigits = (long) value.precision() - value.scale();
        if (value.signum() <= 0 || integerDigits > PRECISION - SCALE) {
            throw new IllegalArgumentException(field + " must be positive and fit NUMERIC(28,8)");
        }
        if (value.stripTrailingZeros().scale() > SCALE) {
            throw new IllegalArgumentException(field + " must be exactly representable with 8 decimal places");
        }
        final BigDecimal normalized;
        try {
            normalized = value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException(field + " must be exactly representable with 8 decimal places", ex);
        }
        if (normalized.signum() <= 0 || normalized.precision() > PRECISION) {
            throw new IllegalArgumentException(field + " must be positive and fit NUMERIC(28,8)");
        }
        return normalized;
    }

    static BigDecimal multipleOf(BigDecimal value, BigDecimal increment, String field) {
        BigDecimal normalized = positive(value, field);
        if (normalized.remainder(increment).signum() != 0) {
            throw new IllegalArgumentException(field + " must be a multiple of " + increment.toPlainString());
        }
        return normalized;
    }

    static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    static String text(String value, String field, int maxLength) {
        required(value, field);
        String normalized = value.strip();
        if (normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must contain 1 to " + maxLength + " characters");
        }
        return normalized;
    }
}
