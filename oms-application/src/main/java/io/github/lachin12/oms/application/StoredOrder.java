package io.github.lachin12.oms.application;

import io.github.lachin12.oms.domain.Order;
import java.util.Objects;

/** Stored version is distinct from domain revision (submission saves two events at once). */
public record StoredOrder(Order order, long version) {
    public StoredOrder {
        Objects.requireNonNull(order, "order");
        if (version < 0) throw new IllegalArgumentException("Version cannot be negative");
    }
}
