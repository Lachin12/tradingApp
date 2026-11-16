package io.github.lachin12.oms.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable aggregate: every successful command returns a new snapshot and appends one fact.
 * There is no generic status setter, hidden clock, random ID, framework or persistence annotation.
 */
public final class Order {
    private final UUID id;
    private final Account account;
    private final Instrument instrument;
    private final Side side;
    private final OrderType type;
    private final BigDecimal quantity;
    private final BigDecimal limitPrice;
    private final OrderStatus status;
    private final BigDecimal filledQuantity;
    private final List<OrderEvent> events;

    private Order(UUID id, Account account, Instrument instrument, Side side, OrderType type,
                  BigDecimal quantity, BigDecimal limitPrice, OrderStatus status,
                  BigDecimal filledQuantity, List<OrderEvent> events) {
        this.id = id;
        this.account = account;
        this.instrument = instrument;
        this.side = side;
        this.type = type;
        this.quantity = quantity;
        this.limitPrice = limitPrice;
        this.status = status;
        this.filledQuantity = filledQuantity;
        this.events = List.copyOf(events);
    }

    public static Order submit(UUID id, Account account, Instrument instrument, Side side,
                               OrderType type, BigDecimal quantity, BigDecimal limitPrice, Instant at) {
        DecimalRules.required(id, "order id");
        DecimalRules.required(account, "account");
        DecimalRules.required(instrument, "instrument");
        DecimalRules.required(side, "side");
        DecimalRules.required(type, "order type");
        DecimalRules.required(at, "timestamp");
        if (!account.active()) {
            throw new IllegalArgumentException("Account must be active");
        }
        BigDecimal normalizedQuantity = instrument.validateQuantity(quantity);
        BigDecimal normalizedPrice = null;
        if (type == OrderType.LIMIT) {
            normalizedPrice = instrument.validatePrice(limitPrice);
        } else if (limitPrice != null) {
            throw new IllegalArgumentException("MARKET orders cannot specify a limit price");
        }
        OrderEvent submitted = new OrderEvent(id, 1, OrderEventType.SUBMITTED, null,
                OrderStatus.NEW, at, DecimalRules.ZERO, null, null);
        return new Order(id, account, instrument, side, type, normalizedQuantity, normalizedPrice,
                OrderStatus.NEW, DecimalRules.ZERO, List.of(submitted));
    }

    public Order accept(Instant at) {
        return append(OrderEventType.ACCEPTED, OrderStatus.ACCEPTED, filledQuantity, null, null, at);
    }

    public Order reject(String reason, Instant at) {
        return append(OrderEventType.REJECTED, OrderStatus.REJECTED, filledQuantity, null, reason, at);
    }

    /** Local cancellation is final. Venue cancel acknowledgements require a future protocol. */
    public Order cancel(String reason, Instant at) {
        return append(OrderEventType.CANCELLED, OrderStatus.CANCELLED, filledQuantity, null, reason, at);
    }

    public Order fill(Execution execution, Instant recordedAt) {
        DecimalRules.required(execution, "execution");
        if (events.stream().anyMatch(event -> event.execution() != null
                && event.execution().id().equals(execution.id()))) {
            throw new IllegalArgumentException("Duplicate execution id: " + execution.id());
        }
        if (status != OrderStatus.ACCEPTED && status != OrderStatus.PARTIALLY_FILLED) {
            throw new IllegalStateException("Cannot execute an order in state " + status);
        }
        instrument.validateQuantity(execution.quantity());
        instrument.validatePrice(execution.price());
        validateTimestamp(recordedAt);
        if (execution.executedAt().isAfter(recordedAt)) {
            throw new IllegalArgumentException("Execution timestamp cannot be after recording timestamp");
        }
        if (type == OrderType.LIMIT) {
            int comparison = execution.price().compareTo(limitPrice);
            if ((side == Side.BUY && comparison > 0) || (side == Side.SELL && comparison < 0)) {
                throw new IllegalArgumentException("Execution price violates order limit");
            }
        }
        BigDecimal cumulative = filledQuantity.add(execution.quantity());
        if (cumulative.compareTo(quantity) > 0) {
            throw new IllegalArgumentException("Execution would overfill order");
        }
        OrderStatus next = cumulative.compareTo(quantity) == 0
                ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
        return append(OrderEventType.EXECUTED, next, cumulative, execution, null, recordedAt);
    }

    private Order append(OrderEventType eventType, OrderStatus next, BigDecimal cumulative,
                         Execution execution, String reason, Instant at) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Illegal order transition: " + status + " -> " + next);
        }
        validateTimestamp(at);
        OrderEvent event = new OrderEvent(id, events.size() + 1L, eventType, status,
                next, at, cumulative, execution, reason);
        List<OrderEvent> updated = new ArrayList<>(events);
        updated.add(event);
        return new Order(id, account, instrument, side, type, quantity, limitPrice, next, cumulative, updated);
    }

    private void validateTimestamp(Instant at) {
        DecimalRules.required(at, "timestamp");
        if (at.isBefore(events.getLast().occurredAt())) {
            throw new IllegalArgumentException("Lifecycle timestamp cannot move backwards");
        }
    }

    public UUID id() { return id; }
    public Account account() { return account; }
    public Instrument instrument() { return instrument; }
    public Side side() { return side; }
    public OrderType type() { return type; }
    public BigDecimal quantity() { return quantity; }
    public Optional<BigDecimal> limitPrice() { return Optional.ofNullable(limitPrice); }
    public OrderStatus status() { return status; }
    public BigDecimal filledQuantity() { return filledQuantity; }
    public BigDecimal remainingQuantity() { return quantity.subtract(filledQuantity); }
    public List<OrderEvent> events() { return events; }
    /** Domain revision; a future persistence adapter must separately check its stored version. */
    public long revision() { return events.size() - 1L; }
}
