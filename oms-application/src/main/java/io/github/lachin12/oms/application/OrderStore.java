package io.github.lachin12.oms.application;

import io.github.lachin12.oms.domain.Order;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderStore {
    Optional<StoredOrder> find(UUID id);
    List<StoredOrder> list(UUID accountId, int limit, int offset);
    StoredOrder insert(Order order);
    /** Atomically update state and append new events, or throw ConcurrentOrderUpdateException. */
    StoredOrder update(Order order, long expectedVersion);
}
