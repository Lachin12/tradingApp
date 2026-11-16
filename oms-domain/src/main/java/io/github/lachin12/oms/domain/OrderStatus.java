package io.github.lachin12.oms.domain;

public enum OrderStatus {
    NEW, ACCEPTED, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED;

    /** A transition is necessary but not sufficient: the command validates quantities too. */
    public boolean canTransitionTo(OrderStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case NEW -> target == ACCEPTED || target == REJECTED || target == CANCELLED;
            case ACCEPTED, PARTIALLY_FILLED ->
                    target == PARTIALLY_FILLED || target == FILLED || target == CANCELLED;
            case FILLED, CANCELLED, REJECTED -> false;
        };
    }
}
