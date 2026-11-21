package io.github.lachin12.oms.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A future execution adapter supplies stable IDs; this model never generates fills. */
public record Execution(UUID id, BigDecimal quantity, BigDecimal price, Instant executedAt) {
    public Execution {
        DecimalRules.required(id, "execution id");
        quantity = DecimalRules.positive(quantity, "execution quantity");
        price = DecimalRules.positive(price, "execution price");
        DecimalRules.required(executedAt, "execution timestamp");
    }
}
