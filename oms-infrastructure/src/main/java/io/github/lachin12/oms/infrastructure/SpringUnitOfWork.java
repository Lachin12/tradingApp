package io.github.lachin12.oms.infrastructure;

import io.github.lachin12.oms.application.UnitOfWork;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Each use case owns one READ COMMITTED transaction, including its durable events. */
public final class SpringUnitOfWork implements UnitOfWork {
    private final TransactionTemplate transaction;

    public SpringUnitOfWork(PlatformTransactionManager manager) {
        transaction = new TransactionTemplate(Objects.requireNonNull(manager, "transaction manager"));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(30);
        // In particular, never inherit REPEATABLE READ from an unrelated caller: claims
        // need a fresh statement snapshot after waiting for a concurrent insert.
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return transaction.execute(status -> work.get());
    }
}
