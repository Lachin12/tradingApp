package io.github.lachin12.oms.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

/** Immutable reference data, including the trading increments used at submission. */
public record Instrument(UUID id, String symbol, Currency currency,
                         BigDecimal tickSize, BigDecimal quantityStep) {
    public Instrument {
        DecimalRules.required(id, "instrument id");
        symbol = DecimalRules.text(symbol, "symbol", 32);
        DecimalRules.required(currency, "currency");
        tickSize = DecimalRules.positive(tickSize, "tick size");
        quantityStep = DecimalRules.positive(quantityStep, "quantity step");
    }

    public BigDecimal validatePrice(BigDecimal price) {
        return DecimalRules.multipleOf(price, tickSize, "price");
    }

    public BigDecimal validateQuantity(BigDecimal quantity) {
        return DecimalRules.multipleOf(quantity, quantityStep, "quantity");
    }
}
