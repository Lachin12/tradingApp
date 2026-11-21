package io.github.lachin12.oms.application;

import io.github.lachin12.oms.domain.*;
import java.math.BigDecimal;
import java.util.UUID;

/** Canonical input, before account/instrument-specific validation. */
public record SubmitOrder(UUID accountId, UUID instrumentId, Side side, OrderType type,
                          BigDecimal quantity, BigDecimal limitPrice) {
    public SubmitOrder {
        try {
            if (accountId == null || instrumentId == null || side == null || type == null) {
                throw new IllegalArgumentException("Account, instrument, side and type are required");
            }
            quantity = DecimalRules.positive(quantity, "quantity");
            if (type == OrderType.LIMIT) limitPrice = DecimalRules.positive(limitPrice, "limit price");
            else if (limitPrice != null) throw new IllegalArgumentException("MARKET order cannot have a limit price");
        } catch (IllegalArgumentException ex) {
            throw new InvalidOrderRequestException(ex.getMessage(), ex);
        }
    }
}
