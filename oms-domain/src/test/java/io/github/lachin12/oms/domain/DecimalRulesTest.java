package io.github.lachin12.oms.domain;

import static io.github.lachin12.oms.domain.DomainFixtures.decimal;
import static io.github.lachin12.oms.domain.DomainFixtures.scaled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DecimalRulesTest {
    @Test
    void rejectsCompactExtremeExponentsBeforeExpandingTheirCoefficients() {
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            assertThrows(IllegalArgumentException.class,
                    () -> DecimalRules.positive(new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE), "price"));
            assertThrows(IllegalArgumentException.class,
                    () -> DecimalRules.positive(new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE), "quantity"));
            assertThrows(IllegalArgumentException.class,
                    () -> DecimalRules.positive(new BigDecimal(BigInteger.ZERO, Integer.MIN_VALUE), "price"));
        });
    }
    @ParameterizedTest
    @ValueSource(strings = {"1", "1.0000000000", "0.00000001", "99999999999999999999.99999999", "1E+3"})
    void preservesExactlyRepresentablePositiveValues(String value) {
        BigDecimal actual = DecimalRules.positive(decimal(value), "quantity");
        assertEquals(8, actual.scale());
        assertEquals(0, actual.compareTo(decimal(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "0.000000001", "100000000000000000000", "1E+100", "1E-100"})
    void rejectsNonpositiveOrUnrepresentableValuesWithoutRounding(String value) {
        assertThrows(IllegalArgumentException.class, () -> DecimalRules.positive(decimal(value), "price"));
    }

    @Test
    void rejectsMissingValue() {
        assertThrows(IllegalArgumentException.class, () -> DecimalRules.positive(null, "quantity"));
    }

    @Test
    void trailingZerosDoNotConsumeAdditionalPrecision() {
        assertEquals(scaled("99999999999999999999.99999999"),
                DecimalRules.positive(decimal("99999999999999999999.99999999000"), "price"));
    }
}
