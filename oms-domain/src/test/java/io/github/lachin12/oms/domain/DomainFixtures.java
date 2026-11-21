package io.github.lachin12.oms.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

final class DomainFixtures {
    static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID INSTRUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    static final Account ACCOUNT = new Account(ACCOUNT_ID, "Portfolio simulation", true);
    static final Instrument INSTRUMENT = new Instrument(INSTRUMENT_ID, "ACME", Currency.getInstance("USD"),
            new BigDecimal("0.01"), new BigDecimal("0.1"));

    private DomainFixtures() {
    }

    static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    static BigDecimal scaled(String value) {
        return decimal(value).setScale(8);
    }

    static Order limit() {
        return limit(Side.BUY);
    }

    static Order limit(Side side) {
        return Order.submit(ORDER_ID, ACCOUNT, INSTRUMENT, side, OrderType.LIMIT,
                decimal("10.0"), decimal("100.00"), T0);
    }

    static Order market() {
        return Order.submit(ORDER_ID, ACCOUNT, INSTRUMENT, Side.BUY, OrderType.MARKET,
                decimal("10.0"), null, T0);
    }

    static Execution execution(int id, String quantity, String price, Instant executedAt) {
        return new Execution(new UUID(0L, id), decimal(quantity), decimal(price), executedAt);
    }
}
