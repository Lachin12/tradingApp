package io.github.lachin12.oms.api;

import io.github.lachin12.oms.domain.Execution;
import io.github.lachin12.oms.domain.OrderEvent;
import io.github.lachin12.oms.domain.OrderEventType;
import io.github.lachin12.oms.domain.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderEventResponse(String id, UUID orderId, long sequence, OrderEventType type,
                                 OrderStatus previousStatus, OrderStatus status, Instant occurredAt,
                                 String cumulativeQuantity, ExecutionResponse execution, String reason) {
    static OrderEventResponse from(OrderEvent event) {
        return new OrderEventResponse(event.id(), event.orderId(), event.sequence(), event.type(),
                event.previousStatus(), event.status(), event.occurredAt(),
                event.cumulativeQuantity().toPlainString(),
                event.execution() == null ? null : ExecutionResponse.from(event.execution()), event.reason());
    }

    public record ExecutionResponse(UUID id, String quantity, String price, Instant executedAt) {
        static ExecutionResponse from(Execution execution) {
            return new ExecutionResponse(execution.id(), execution.quantity().toPlainString(),
                    execution.price().toPlainString(), execution.executedAt());
        }
    }
}
