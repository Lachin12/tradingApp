package io.github.lachin12.oms.infrastructure;

import io.github.lachin12.oms.application.OmsService;
import io.github.lachin12.oms.application.StoredOrder;
import io.github.lachin12.oms.application.SubmitOrder;
import io.github.lachin12.oms.application.outbox.DeliveryClaim;
import io.github.lachin12.oms.application.outbox.DispatchResult;
import io.github.lachin12.oms.application.outbox.EventPublisher;
import io.github.lachin12.oms.application.outbox.OutboxDispatcher;
import io.github.lachin12.oms.application.outbox.OutboxMessage;
import io.github.lachin12.oms.application.outbox.RetryPolicy;
import io.github.lachin12.oms.domain.OrderEvent;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** Lease ownership and ordering are verified with PostgreSQL and separate connections. */
@Testcontainers
@Timeout(30)
class JdbcOutboxStoreIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.12-bookworm");

    private static final UUID ACCOUNT = uuid(1);
    private static final UUID INSTRUMENT = uuid(2);
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final Duration RETRY = Duration.ofMinutes(1);
    private static final Instant NOW = Instant.parse("2026-01-01T09:00:00.123456Z");
    private static final AtomicLong IDS = new AtomicLong(1000);
    private static JdbcTemplate jdbc;
    private static JdbcOmsStore orders;
    private static JdbcOutboxStore outbox;
    private static SpringUnitOfWork transactions;
    private OmsService service;
    private ExecutorService workers;

    @BeforeAll
    static void migrateDatabase() {
        DriverManagerDataSource dataSource = dataSource(POSTGRES.getJdbcUrl());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        orders = new JdbcOmsStore(jdbc);
        outbox = new JdbcOutboxStore(jdbc);
        transactions = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE outbox_delivery, outbox_events, order_events, submission_keys, orders, accounts, instruments CASCADE");
        seed(jdbc);
        service = newService(orders, transactions);
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Transaction workers must terminate");
    }

    @Test
    void claimCommitsOneImmutableHeadPerOrderWithAttemptAndDatabaseLease() {
        StoredOrder order = submit("head");
        Instant before = databaseNow();
        UUID token = uuid(100);
        List<DeliveryClaim> claims = claim(100, token);
        Instant after = databaseNow();

        assertEquals(1, claims.size(), "The accepted event must wait behind submitted");
        DeliveryClaim delivery = claims.getFirst();
        assertEquals(order.order().events().getFirst(), delivery.message().event());
        assertEquals(delivery.message().event().id(), delivery.message().eventId());
        assertEquals(1, delivery.message().schemaVersion());
        assertEquals(token, delivery.token());
        assertEquals(1, delivery.attempt());
        assertFalse(delivery.leaseUntil().isBefore(before.plus(LEASE)));
        assertFalse(delivery.leaseUntil().isAfter(after.plus(LEASE)));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox_delivery WHERE event_id = ?", Integer.class,
                delivery.message().eventId()));
        assertEquals(2, count("outbox_events"));
        assertTrue(claim(100, uuid(101)).isEmpty(), "An active lease must block the order's whole sequence");
    }

    @Test
    void simultaneousWorkersClaimDisjointOrdersWithoutDuplicatingLeases() throws Exception {
        submit("a");
        submit("b");
        CyclicBarrier ready = new CyclicBarrier(2);
        Future<DeliveryClaim> first = workers.submit(() -> {
            await(ready);
            return claim(1, uuid(110)).getFirst();
        });
        Future<DeliveryClaim> second = workers.submit(() -> {
            await(ready);
            return claim(1, uuid(111)).getFirst();
        });
        DeliveryClaim a = first.get(10, TimeUnit.SECONDS);
        DeliveryClaim b = second.get(10, TimeUnit.SECONDS);
        assertNotEquals(a.message().eventId(), b.message().eventId());
        assertNotEquals(a.message().event().orderId(), b.message().event().orderId());
        assertEquals(1, a.message().event().sequence());
        assertEquals(1, b.message().event().sequence());
        assertTrue(claim(100, uuid(112)).isEmpty());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox_delivery WHERE claim_token IS NOT NULL", Integer.class));
    }

    @Test
    void lockedHeadIsSkippedSoAnotherOrderCanProgress() throws Exception {
        StoredOrder a = submit("locked-head-a");
        StoredOrder b = submit("locked-head-b");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> locker = workers.submit(() -> transactions.execute(() -> {
            jdbc.queryForObject("SELECT event_id FROM outbox_delivery WHERE event_id = ? FOR UPDATE", String.class,
                    a.order().events().getFirst().id());
            locked.countDown();
            await(release);
            return null;
        }));
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            List<DeliveryClaim> candidates = claim(100, uuid(120));
            assertEquals(1, candidates.size());
            assertEquals(b.order().id(), candidates.getFirst().message().event().orderId());
        } finally {
            release.countDown();
        }
        locker.get(10, TimeUnit.SECONDS);
    }

    @Test
    void expiredClaimCanBeReclaimedButOldTokenCannotCompleteOrRescheduleIt() {
        submit("expired");
        DeliveryClaim old = claim(1, uuid(130)).getFirst();
        expire(old);
        assertFalse(acknowledge(old), "Expiry invalidates acknowledgement even before reclaim");
        assertFalse(reschedule(old, RETRY));
        DeliveryClaim replacement = claim(1, uuid(131)).getFirst();

        assertEquals(old.message(), replacement.message());
        assertEquals(2, replacement.attempt());
        assertNotEquals(old.token(), replacement.token());
        assertFalse(acknowledge(old));
        assertFalse(reschedule(old, RETRY));
        assertTrue(acknowledge(replacement));
        assertFalse(acknowledge(replacement), "An already published event cannot be acknowledged twice");
        assertFalse(reschedule(replacement, RETRY));
    }

    @Test
    void attemptNumberFencesAStaleClaimEvenWhenTheCallerReusesItsToken() {
        submit("same-token");
        UUID token = uuid(140);
        DeliveryClaim first = claim(1, token).getFirst();
        expire(first);
        DeliveryClaim second = claim(1, token).getFirst();

        assertEquals(first.token(), second.token());
        assertEquals(first.attempt() + 1, second.attempt());
        assertFalse(acknowledge(first));
        assertFalse(reschedule(first, RETRY));
        assertTrue(acknowledge(second));
    }

    @Test
    void retryPersistsItsDelayAndBlocksLaterOrderEventsUntilTheHeadSucceeds() {
        StoredOrder a = submit("retry-a");
        DeliveryClaim first = claim(1, uuid(150)).getFirst();
        Instant before = databaseNow();
        assertTrue(reschedule(first, RETRY));
        Instant after = databaseNow();
        Instant due = jdbc.queryForObject("SELECT next_attempt_at FROM outbox_delivery WHERE event_id = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), first.message().eventId());
        assertFalse(due.isBefore(before.plus(RETRY)));
        assertFalse(due.isAfter(after.plus(RETRY)));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox_delivery WHERE event_id = ?", Integer.class,
                first.message().eventId()));
        assertNull(jdbc.queryForObject("SELECT claim_token FROM outbox_delivery WHERE event_id = ?", UUID.class,
                first.message().eventId()));
        assertTrue(claim(100, uuid(151)).isEmpty());

        StoredOrder b = submit("retry-b");
        DeliveryClaim independent = claim(100, uuid(152)).getFirst();
        assertEquals(b.order().id(), independent.message().event().orderId());
        assertNotEquals(a.order().id(), independent.message().event().orderId());

        forceDue(first);
        DeliveryClaim retried = claim(100, uuid(153)).getFirst();
        assertEquals(first.message(), retried.message());
        assertEquals(2, retried.attempt());
        assertTrue(acknowledge(retried));
        DeliveryClaim next = claim(100, uuid(154)).getFirst();
        assertEquals(a.order().id(), next.message().event().orderId());
        assertEquals(2, next.message().event().sequence());
        assertEquals(a.order().events().get(1), next.message().event());
    }

    @Test
    void acknowledgementReleasesExactlyTheNextEventAndPreservesEveryBusinessFact() {
        StoredOrder a = submit("ordered-a");
        service.cancel(a.order().id(), 0, "Three lifecycle facts");
        for (int sequence = 1; sequence <= 3; sequence++) {
            List<DeliveryClaim> claims = claim(100, uuid(160 + sequence));
            assertEquals(1, claims.size());
            assertEquals(sequence, claims.getFirst().message().event().sequence());
            assertTrue(acknowledge(claims.getFirst()));
        }
        assertTrue(claim(100, uuid(170)).isEmpty());
        assertEquals(3, count("order_events"));
        assertEquals(3, count("outbox_events"));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM outbox_delivery WHERE published_at IS NOT NULL", Integer.class));
        assertEquals(3, service.get(a.order().id()).order().events().size());
    }

    @Test
    void rollingBackTheClaimTransactionLeavesItImmediatelyAvailable() {
        submit("claim-rollback");
        assertThrows(DeliberateRollback.class, () -> transactions.execute(() -> {
            assertEquals(1, outbox.claim(1, LEASE, uuid(180)).size());
            throw new DeliberateRollback();
        }));
        DeliveryClaim actual = claim(1, uuid(181)).getFirst();
        assertEquals(1, actual.attempt());
        assertEquals(uuid(181), actual.token());
    }

    @Test
    void failedAcknowledgementTransactionDoesNotMarkTheEventPublished() {
        submit("ack-rollback");
        DeliveryClaim delivery = claim(1, uuid(190)).getFirst();
        assertThrows(DeliberateRollback.class, () -> transactions.execute(() -> {
            assertTrue(outbox.acknowledge(delivery));
            throw new DeliberateRollback();
        }));
        assertNull(jdbc.queryForObject("SELECT published_at FROM outbox_delivery WHERE event_id = ?",
                Object.class, delivery.message().eventId()));
        assertTrue(acknowledge(delivery));
    }

    enum Completion { ACKNOWLEDGE, RESCHEDULE }

    @ParameterizedTest
    @EnumSource(Completion.class)
    void completionWaitingForARowLockCannotUseLeaseStateFromBeforeThatLock(Completion completion) throws Exception {
        submit("blocked-completion");
        DeliveryClaim delivery = claim(1, uuid(200)).getFirst();
        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch commitExpiry = new CountDownLatch(1);
        Future<?> holder = workers.submit(() -> transactions.execute(() -> {
            jdbc.queryForObject("SELECT event_id FROM outbox_delivery WHERE event_id = ? FOR UPDATE", String.class,
                    delivery.message().eventId());
            rowLocked.countDown();
            await(commitExpiry);
            // This expires AFTER the completion statement began. A timestamp frozen
            // before its lock wait would incorrectly consider the lease valid.
            jdbc.update("UPDATE outbox_delivery SET lease_until = clock_timestamp() WHERE event_id = ?",
                    delivery.message().eventId());
            return null;
        }));
        Future<Boolean> completionResult;
        try {
            assertTrue(rowLocked.await(5, TimeUnit.SECONDS));
            completionResult = workers.submit(() -> transactions.execute(() -> {
                jdbc.queryForObject("SELECT set_config('application_name', 'outbox-waiting-completion', true)", String.class);
                return completion == Completion.ACKNOWLEDGE
                        ? outbox.acknowledge(delivery) : outbox.reschedule(delivery, RETRY);
            }));
            awaitDatabaseWaiter("outbox-waiting-completion");
        } finally {
            commitExpiry.countDown();
        }
        holder.get(10, TimeUnit.SECONDS);
        assertFalse(completionResult.get(10, TimeUnit.SECONDS));
        assertNull(jdbc.queryForObject("SELECT published_at FROM outbox_delivery WHERE event_id = ?",
                Object.class, delivery.message().eventId()));
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM outbox_delivery WHERE event_id = ?",
                Integer.class, delivery.message().eventId()));
        assertEquals(2, claim(1, uuid(201)).getFirst().attempt());
    }

    @Test
    void dispatcherPublishesOutsideTransactionsAndOtherWorkersCanClaimWhilePublisherIsBlocked() throws Exception {
        submit("network-a");
        submit("network-b");
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch releasePublisher = new CountDownLatch(1);
        List<OutboxMessage> sent = new ArrayList<>();
        OutboxDispatcher dispatcher = dispatcher(message -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            sent.add(message);
            publishing.countDown();
            await(releasePublisher);
        });
        Future<DispatchResult> dispatch = workers.submit(() -> dispatcher.dispatch(1));
        try {
            assertTrue(publishing.await(5, TimeUnit.SECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM outbox_delivery WHERE claim_token IS NOT NULL", Integer.class),
                    "The claim must already be committed before the external call");
            DeliveryClaim independent = claim(1, uuid(250)).getFirst();
            assertNotEquals(sent.getFirst().event().orderId(), independent.message().event().orderId());
            assertTrue(acknowledge(independent));
        } finally {
            releasePublisher.countDown();
        }
        DispatchResult result = dispatch.get(10, TimeUnit.SECONDS);
        assertEquals(1, result.claimed());
        assertEquals(1, result.published());
        assertEquals(0, result.rescheduled());
        assertEquals(0, result.leaseLost());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM outbox_delivery WHERE published_at IS NOT NULL", Integer.class));
    }

    @Test
    void dispatcherRejectsAnAmbientTransactionBeforeClaimingOrPublishing() {
        submit("ambient-transaction");
        AtomicInteger sends = new AtomicInteger();
        OutboxDispatcher dispatcher = dispatcher(message -> sends.incrementAndGet());
        assertThrows(IllegalStateException.class,
                () -> transactions.execute(() -> dispatcher.dispatch(1)));
        assertEquals(0, sends.get());
        assertEquals(0, jdbc.queryForObject("SELECT sum(attempts) FROM outbox_delivery", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM outbox_delivery WHERE claim_token IS NOT NULL", Integer.class));
    }

    @Test
    void publisherFailureReschedulesTheSameImmutableMessageForLaterRetry() {
        submit("publisher-failure");
        List<OutboxMessage> observed = new ArrayList<>();
        DispatchResult failed = dispatcher(message -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            observed.add(message);
            throw new java.io.IOException("Simulated external transport failure");
        }).dispatch(1);
        assertEquals(1, failed.claimed());
        assertEquals(0, failed.published());
        assertEquals(1, failed.rescheduled());
        assertEquals(0, failed.leaseLost());
        assertTrue(claim(100, uuid(260)).isEmpty());
        jdbc.update("UPDATE outbox_delivery SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE event_id = ?",
                observed.getFirst().eventId());
        DispatchResult retried = dispatcher(observed::add).dispatch(1);
        assertEquals(1, retried.published());
        assertEquals(observed.getFirst(), observed.getLast());
        assertEquals(2, jdbc.queryForObject("SELECT attempts FROM outbox_delivery WHERE event_id = ?", Integer.class,
                observed.getFirst().eventId()));
        assertEquals(2, count("order_events"));
    }

    @Test
    void publishBeforeAcknowledgementCrashWindowRedeliversTheSameEventIdentityAndPayload() {
        submit("published-before-crash");
        List<OutboxMessage> externallyObserved = new ArrayList<>();
        DeliveryClaim first = claim(1, uuid(210)).getFirst();
        externallyObserved.add(first.message()); // External send succeeded; process dies before ACK.
        expire(first);
        DeliveryClaim recovered = claim(1, uuid(211)).getFirst();
        externallyObserved.add(recovered.message());
        assertTrue(acknowledge(recovered));

        assertEquals(2, externallyObserved.size());
        assertEquals(externallyObserved.getFirst(), externallyObserved.getLast());
        assertEquals(first.message().eventId(), recovered.message().eventId());
        assertEquals(first.message().event(), recovered.message().event());
        assertEquals(2, recovered.attempt());
        assertFalse(acknowledge(first));
    }

    @Test
    void pendingEnvelopeCannotLoseItsDeliveryMetadataAtCommit() {
        StoredOrder a = submit("delivery-required");
        String eventId = a.order().events().getFirst().id();
        assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM outbox_delivery WHERE event_id = ?", eventId));
        assertEquals(2, count("outbox_delivery"));
        assertEquals(1, claim(100, uuid(220)).size());
    }

    @Test
    void invalidClaimAndRetryBoundsDoNotChangePersistedState() {
        submit("invalid-bounds");
        for (Duration lease : List.of(Duration.ZERO, Duration.ofMillis(999), Duration.ofMinutes(5).plusMillis(1),
                Duration.ofSeconds(1).plusNanos(1))) {
            assertThrows(IllegalArgumentException.class,
                    () -> transactions.execute(() -> outbox.claim(1, lease, uuid(230))));
        }
        for (int limit : List.of(0, 101)) {
            assertThrows(IllegalArgumentException.class,
                    () -> transactions.execute(() -> outbox.claim(limit, LEASE, uuid(230))));
        }
        DeliveryClaim delivery = claim(1, uuid(231)).getFirst();
        for (Duration retry : List.of(Duration.ZERO, Duration.ofHours(1).plusMillis(1), Duration.ofNanos(1))) {
            assertThrows(IllegalArgumentException.class,
                    () -> transactions.execute(() -> outbox.reschedule(delivery, retry)));
        }
        assertEquals(1, delivery.attempt());
        assertTrue(acknowledge(delivery));
    }

    @Test
    void migrationUpgradesExistingPublishedAndPendingRowsWithoutRewritingTheirFacts() {
        jdbc.execute("CREATE SCHEMA outbox_upgrade");
        DriverManagerDataSource oldDataSource = dataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=outbox_upgrade");
        try {
            Flyway.configure().dataSource(oldDataSource).defaultSchema("outbox_upgrade").target("2").load().migrate();
            JdbcTemplate upgradeJdbc = new JdbcTemplate(oldDataSource);
            // V2 has seeded reference data with the same IDs.
            JdbcOmsStore oldStore = new JdbcOmsStore(upgradeJdbc);
            SpringUnitOfWork oldTransactions = new SpringUnitOfWork(new DataSourceTransactionManager(oldDataSource));
            StoredOrder order = newService(oldStore, oldTransactions).submit(command(), "legacy").storedOrder();
            String firstId = order.order().events().getFirst().id();
            String secondId = order.order().events().getLast().id();
            upgradeJdbc.update("UPDATE outbox_delivery SET published_at = clock_timestamp(), attempts = 3 WHERE event_id = ?", firstId);
            upgradeJdbc.update("UPDATE outbox_delivery SET attempts = 2, last_error = 'legacy failure' WHERE event_id = ?", secondId);
            StoredOrder missingMetadata = newService(oldStore, oldTransactions).submit(command(), "legacy-missing").storedOrder();
            upgradeJdbc.update("DELETE FROM outbox_delivery WHERE event_id = ?", missingMetadata.order().events().getFirst().id());
            Flyway.configure().dataSource(oldDataSource).defaultSchema("outbox_upgrade").load().migrate();

            JdbcOutboxStore upgraded = new JdbcOutboxStore(upgradeJdbc);
            List<DeliveryClaim> pending = oldTransactions.execute(() -> upgraded.claim(100, LEASE, uuid(240)));
            assertEquals(2, pending.size());
            DeliveryClaim previousFailure = pending.stream().filter(claim -> claim.message().eventId().equals(secondId)).findFirst().orElseThrow();
            assertEquals(3, previousFailure.attempt());
            assertEquals(order.order().events().getLast(), previousFailure.message().event());
            DeliveryClaim repaired = pending.stream().filter(claim -> claim.message().event().orderId().equals(missingMetadata.order().id())).findFirst().orElseThrow();
            assertEquals(1, repaired.attempt());
            assertEquals(missingMetadata.order().events().getFirst(), repaired.message().event());
            assertEquals(4, upgradeJdbc.queryForObject("SELECT count(*) FROM outbox_delivery", Integer.class));
            assertNotNull(upgradeJdbc.queryForObject("SELECT published_at FROM outbox_delivery WHERE event_id = ?", Object.class, firstId));
            assertEquals(3, upgradeJdbc.queryForObject("SELECT attempts FROM outbox_delivery WHERE event_id = ?", Integer.class, firstId));
            assertEquals(order.order().events(), oldStore.find(order.order().id()).orElseThrow().order().events());
        } finally {
            jdbc.execute("DROP SCHEMA outbox_upgrade CASCADE");
        }
    }

    private List<DeliveryClaim> claim(int limit, UUID token) {
        return transactions.execute(() -> outbox.claim(limit, LEASE, token));
    }

    private boolean acknowledge(DeliveryClaim claim) {
        return transactions.execute(() -> outbox.acknowledge(claim));
    }

    private boolean reschedule(DeliveryClaim claim, Duration delay) {
        return transactions.execute(() -> outbox.reschedule(claim, delay));
    }

    private static OutboxDispatcher dispatcher(EventPublisher publisher) {
        return new OutboxDispatcher(outbox, transactions, publisher, () -> uuid(IDS.getAndIncrement()),
                LEASE, new RetryPolicy(RETRY, Duration.ofHours(1)), transactions);
    }

    private static void expire(DeliveryClaim claim) {
        jdbc.update("UPDATE outbox_delivery SET lease_until = clock_timestamp() - interval '1 second' WHERE event_id = ?",
                claim.message().eventId());
    }

    private static void forceDue(DeliveryClaim claim) {
        jdbc.update("UPDATE outbox_delivery SET next_attempt_at = clock_timestamp() - interval '1 second' WHERE event_id = ?",
                claim.message().eventId());
    }

    private static Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    private static void awaitDatabaseWaiter(String applicationName) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                    WHERE application_name = ? AND cardinality(pg_blocking_pids(pid)) > 0)
                    """, Boolean.class, applicationName);
            if (Boolean.TRUE.equals(waiting)) return;
            Thread.yield();
        }
        fail("Completion did not become a database row-lock waiter");
    }

    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out awaiting transaction coordination");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }

    private static DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void seed(JdbcTemplate target) {
        target.update("INSERT INTO accounts(id, name, active) VALUES (?, ?, true)", ACCOUNT, "Outbox account");
        target.update("INSERT INTO instruments(id, symbol, currency, tick_size, quantity_step) VALUES (?, ?, ?, ?, ?)",
                INSTRUMENT, "OUTBOX", "USD", new BigDecimal("0.01"), new BigDecimal("1"));
    }

    private static OmsService newService(JdbcOmsStore target, SpringUnitOfWork unitOfWork) {
        return new OmsService(target, target, target, unitOfWork, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> uuid(IDS.getAndIncrement()));
    }

    private StoredOrder submit(String key) { return service.submit(command(), key).storedOrder(); }
    private static SubmitOrder command() {
        return new SubmitOrder(ACCOUNT, INSTRUMENT, Side.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100.01"));
    }
    private static UUID uuid(long value) { return new UUID(0, value); }
    private static int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private static final class DeliberateRollback extends RuntimeException { }
}
