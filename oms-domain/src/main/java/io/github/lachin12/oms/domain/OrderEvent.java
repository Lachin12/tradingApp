package io.github.lachin12.oms.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable lifecycle fact. The stable identity is (orderId, sequence).
 * Null previousStatus is reserved for SUBMITTED; execution and reason are type-specific.
 */
public record OrderEvent(UUID orderId, long sequence, OrderEventType type,
                         OrderStatus previousStatus, OrderStatus status, Instant occurredAt,
                         BigDecimal cumulativeQuantity, Execution execution, String reason) {
    public OrderEvent {
        DecimalRules.required(orderId, "event order id");
        DecimalRules.required(type, "event type");
        DecimalRules.required(status, "event status");
        DecimalRules.required(occurredAt, "event timestamp");
        DecimalRules.required(cumulativeQuantity, "cumulative quantity");
        if (sequence < 1 || cumulativeQuantity.signum() < 0) {
            throw new IllegalArgumentException("Invalid event sequence or cumulative quantity");
        }
        cumulativeQuantity = cumulativeQuantity.signum() == 0
                ? DecimalRules.ZERO : DecimalRules.positive(cumulativeQuantity, "cumulative quantity");
        boolean validShape = switch (type) {
            case SUBMITTED -> sequence == 1 && previousStatus == null && status == OrderStatus.NEW
                    && execution == null && reason == null && cumulativeQuantity.signum() == 0;
            case ACCEPTED -> previousStatus == OrderStatus.NEW && status == OrderStatus.ACCEPTED
                    && execution == null && reason == null && cumulativeQuantity.signum() == 0;
            case EXECUTED -> previousStatus != null && previousStatus.canTransitionTo(status)
                    && (status == OrderStatus.PARTIALLY_FILLED || status == OrderStatus.FILLED)
                    && execution != null && reason == null
                    && (previousStatus == OrderStatus.ACCEPTED
                        ? cumulativeQuantity.compareTo(execution.quantity()) == 0
                        : cumulativeQuantity.compareTo(execution.quantity()) > 0)
                    && !execution.executedAt().isAfter(occurredAt);
            case CANCELLED -> previousStatus != null && previousStatus.canTransitionTo(status)
                    && status == OrderStatus.CANCELLED && execution == null
                    && (previousStatus == OrderStatus.PARTIALLY_FILLED
                        ? cumulativeQuantity.signum() > 0 : cumulativeQuantity.signum() == 0);
            case REJECTED -> previousStatus == OrderStatus.NEW && status == OrderStatus.REJECTED
                    && execution == null && cumulativeQuantity.signum() == 0;
        };
        if (!validShape || (type != OrderEventType.SUBMITTED && sequence == 1)) {
            throw new IllegalArgumentException("Event fields do not match event type");
        }
        if ((previousStatus == OrderStatus.NEW && sequence != 2)
                || (previousStatus == OrderStatus.ACCEPTED && sequence != 3)
                || (previousStatus == OrderStatus.PARTIALLY_FILLED && sequence < 4)) {
            throw new IllegalArgumentException("Event sequence is inconsistent with previous state");
        }
        if (type == OrderEventType.CANCELLED || type == OrderEventType.REJECTED) {
            reason = DecimalRules.text(reason, "reason", 256);
        }
    }

    public String id() {
        return orderId + ":" + sequence;
    }
}
