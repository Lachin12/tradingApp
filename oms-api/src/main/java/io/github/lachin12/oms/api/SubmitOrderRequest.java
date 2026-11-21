package io.github.lachin12.oms.api;

import io.github.lachin12.oms.application.SubmitOrder;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** HTTP input is deliberately separate from the domain aggregate. */
public record SubmitOrderRequest(
        @NotNull UUID accountId,
        @NotNull UUID instrumentId,
        @NotNull Side side,
        @NotNull OrderType type,
        @NotNull @Schema(type = "string", example = "10.00000000",
                description = "Positive exact decimal, at most 20 integer and 8 fractional digits; instrument quantity step applies")
        BigDecimal quantity,
        @Schema(type = "string", example = "100.25000000",
                description = "Required for LIMIT, absent for MARKET; exact decimal and instrument tick rules apply")
        BigDecimal limitPrice) {

    SubmitOrder toCommand() {
        return new SubmitOrder(accountId, instrumentId, side, type, quantity, limitPrice);
    }
}
