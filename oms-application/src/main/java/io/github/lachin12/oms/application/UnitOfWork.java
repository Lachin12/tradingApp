package io.github.lachin12.oms.application;

import java.util.function.Supplier;

/** All ports used inside one callback must participate in the same transaction. */
public interface UnitOfWork {
    <T> T execute(Supplier<T> work);
}
