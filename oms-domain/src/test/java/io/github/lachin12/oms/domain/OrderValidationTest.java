package io.github.lachin12.oms.domain;

import static io.github.lachin12.oms.domain.DomainFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderValidationTest {
    @Test
    void limitRequiresPriceAndMarketForbidsPrice() {
        assertThrows(IllegalArgumentException.class, () -> submit(OrderType.LIMIT, decimal("1"), null));
        assertThrows(IllegalArgumentException.class, () -> submit(OrderType.MARKET, decimal("1"), decimal("100")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.000000001", "100000000000000000000", "0.15"})
    void rejectsInvalidOrderQuantitiesIncludingInstrumentStepViolations(String quantity) {
        assertThrows(IllegalArgumentException.class,
                () -> submit(OrderType.LIMIT, decimal(quantity), decimal("100")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.000000001", "100000000000000000000", "100.001"})
    void rejectsInvalidLimitPricesIncludingInstrumentTickViolations(String price) {
        assertThrows(IllegalArgumentException.class,
                () -> submit(OrderType.LIMIT, decimal("1"), decimal(price)));
    }

    @Test
    void rejectsInactiveAccount() {
        Account inactive = new Account(ACCOUNT_ID, "Inactive account", false);
        assertThrows(IllegalArgumentException.class, () -> Order.submit(ORDER_ID, inactive, INSTRUMENT,
                Side.BUY, OrderType.MARKET, decimal("1"), null, T0));
    }

    @Test
    void rejectsMissingOrderInputs() {
        assertThrows(IllegalArgumentException.class, () -> Order.submit(null, ACCOUNT, INSTRUMENT,
                Side.BUY, OrderType.MARKET, decimal("1"), null, T0));
        assertThrows(IllegalArgumentException.class, () -> Order.submit(ORDER_ID, null, INSTRUMENT,
                Side.BUY, OrderType.MARKET, decimal("1"), null, T0));
        assertThrows(IllegalArgumentException.class, () -> Order.submit(ORDER_ID, ACCOUNT, null,
                Side.BUY, OrderType.MARKET, decimal("1"), null, T0));
        assertThrows(IllegalArgumentException.class, () -> Order.submit(ORDER_ID, ACCOUNT, INSTRUMENT,
                null, OrderType.MARKET, decimal("1"), null, T0));
        assertThrows(IllegalArgumentException.class, () -> Order.submit(ORDER_ID, ACCOUNT, INSTRUMENT,
                Side.BUY, null, decimal("1"), null, T0));
        assertThrows(IllegalArgumentException.class, () -> Order.submit(ORDER_ID, ACCOUNT, INSTRUMENT,
                Side.BUY, OrderType.MARKET, null, null, T0));
        assertThrows(IllegalArgumentException.class, () -> Order.submit(ORDER_ID, ACCOUNT, INSTRUMENT,
                Side.BUY, OrderType.MARKET, decimal("1"), null, null));
    }

    @Test
    void normalizesNumericallyEquivalentOrderValues() {
        Order first = submit(OrderType.LIMIT, decimal("1.0"), decimal("100.0"));
        Order second = submit(OrderType.LIMIT, decimal("1.0000000000"), decimal("100.0000000000"));
        assertEquals(first.quantity(), second.quantity());
        assertEquals(first.limitPrice(), second.limitPrice());
        assertEquals(scaled("1"), first.quantity());
        assertEquals(scaled("100"), first.limitPrice().orElseThrow());
    }

    @Test
    void validatesAccountAndInstrumentIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new Account(null, "Name", true));
        assertThrows(IllegalArgumentException.class, () -> new Account(ACCOUNT_ID, " ", true));
        assertThrows(IllegalArgumentException.class, () -> new Instrument(null, "ACME", Currency.getInstance("USD"),
                decimal("0.01"), decimal("0.1")));
        assertThrows(IllegalArgumentException.class, () -> new Instrument(INSTRUMENT_ID, " ", Currency.getInstance("USD"),
                decimal("0.01"), decimal("0.1")));
        assertThrows(IllegalArgumentException.class, () -> new Instrument(INSTRUMENT_ID, "ACME", null,
                decimal("0.01"), decimal("0.1")));
        assertThrows(IllegalArgumentException.class, () -> new Instrument(INSTRUMENT_ID, "ACME", Currency.getInstance("USD"),
                decimal("0"), decimal("0.1")));
        assertThrows(IllegalArgumentException.class, () -> new Instrument(INSTRUMENT_ID, "ACME", Currency.getInstance("USD"),
                decimal("0.01"), decimal("-0.1")));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void cancellationAndRejectionRequireReason(String reason) {
        Order order = limit();
        assertThrows(IllegalArgumentException.class, () -> order.cancel(reason, T0));
        assertThrows(IllegalArgumentException.class, () -> order.reject(reason, T0));
    }

    @Test
    void boundsReasonLength() {
        assertThrows(IllegalArgumentException.class, () -> limit().cancel("x".repeat(257), T0));
        assertThrows(IllegalArgumentException.class, () -> limit().reject("x".repeat(257), T0));
    }

    private static Order submit(OrderType type, BigDecimal quantity, BigDecimal price) {
        return Order.submit(ORDER_ID, ACCOUNT, INSTRUMENT, Side.BUY, type, quantity, price, T0);
    }
}
