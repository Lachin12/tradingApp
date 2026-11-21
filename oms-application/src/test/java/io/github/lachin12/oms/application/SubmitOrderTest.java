package io.github.lachin12.oms.application;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubmitOrderTest {
    private static final UUID ACCOUNT_ID = new UUID(0, 1);
    private static final UUID INSTRUMENT_ID = new UUID(0, 2);

    @Test
    void equivalentNumericFormsHaveSameFingerprint() {
        SubmitOrder first = request(Side.BUY, OrderType.LIMIT, "1E+1", "100.00");
        SubmitOrder second = request(Side.BUY, OrderType.LIMIT, "10.000000000", "100.000000000");

        assertEquals(first, second);
        assertEquals(RequestFingerprint.of(first), RequestFingerprint.of(second));
        assertTrue(RequestFingerprint.of(first).matches("[0-9a-f]{64}"));
    }

    @Test
    void everyMaterialRequestFieldAffectsFingerprint() {
        SubmitOrder original = request(Side.BUY, OrderType.LIMIT, "10", "100");
        String fingerprint = RequestFingerprint.of(original);
        assertNotEquals(fingerprint, RequestFingerprint.of(request(Side.SELL, OrderType.LIMIT, "10", "100")));
        assertNotEquals(fingerprint, RequestFingerprint.of(request(Side.BUY, OrderType.LIMIT, "11", "100")));
        assertNotEquals(fingerprint, RequestFingerprint.of(request(Side.BUY, OrderType.LIMIT, "10", "101")));
        assertNotEquals(fingerprint, RequestFingerprint.of(request(Side.BUY, OrderType.MARKET, "10", null)));
        assertNotEquals(fingerprint, RequestFingerprint.of(new SubmitOrder(new UUID(0, 3), INSTRUMENT_ID,
                Side.BUY, OrderType.LIMIT, new BigDecimal("10"), new BigDecimal("100"))));
        assertNotEquals(fingerprint, RequestFingerprint.of(new SubmitOrder(ACCOUNT_ID, new UUID(0, 3),
                Side.BUY, OrderType.LIMIT, new BigDecimal("10"), new BigDecimal("100"))));
    }

    @Test
    void canonicalInputRejectsInvalidTermsBeforeIdempotencyClaim() {
        assertThrows(InvalidOrderRequestException.class, () -> request(Side.BUY, OrderType.LIMIT, "0", "100"));
        assertThrows(InvalidOrderRequestException.class, () -> request(Side.BUY, OrderType.LIMIT, "10", null));
        assertThrows(InvalidOrderRequestException.class, () -> request(Side.BUY, OrderType.MARKET, "10", "100"));
        assertThrows(InvalidOrderRequestException.class, () -> request(Side.BUY, OrderType.LIMIT, "0.000000001", "100"));
        assertThrows(InvalidOrderRequestException.class, () -> request(null, OrderType.LIMIT, "10", "100"));
        assertThrows(InvalidOrderRequestException.class, () -> request(Side.BUY, null, "10", "100"));
        assertThrows(InvalidOrderRequestException.class, () -> new SubmitOrder(null, INSTRUMENT_ID,
                Side.BUY, OrderType.MARKET, BigDecimal.ONE, null));
        assertThrows(InvalidOrderRequestException.class, () -> new SubmitOrder(ACCOUNT_ID, null,
                Side.BUY, OrderType.MARKET, BigDecimal.ONE, null));
    }

    private static SubmitOrder request(Side side, OrderType type, String quantity, String price) {
        return new SubmitOrder(ACCOUNT_ID, INSTRUMENT_ID, side, type, new BigDecimal(quantity),
                price == null ? null : new BigDecimal(price));
    }
}
