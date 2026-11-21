package io.github.lachin12.oms.api;

import io.github.lachin12.oms.application.StoredOrder;
import io.github.lachin12.oms.domain.Order;
import io.github.lachin12.oms.domain.OrderStatus;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.time.Instant;
import java.util.UUID;

/** Decimal strings preserve exact values for clients whose JSON numbers use floating point. */
public record OrderResponse(UUID id, UUID accountId, UUID instrumentId, Side side, OrderType type,
                            String quantity, String limitPrice, OrderStatus status,
                            String filledQuantity, String remainingQuantity, long version,
                            Instant createdAt, Instant updatedAt) {
    static OrderResponse from(StoredOrder stored) {
        Order order = stored.order();
        return new OrderResponse(order.id(), order.account().id(), order.instrument().id(),
                order.side(), order.type(), order.quantity().toPlainString(),
                order.limitPrice().map(value -> value.toPlainString()).orElse(null), order.status(),
                order.filledQuantity().toPlainString(), order.remainingQuantity().toPlainString(),
                stored.version(), order.events().getFirst().occurredAt(), order.events().getLast().occurredAt());
    }
}
