package io.github.lachin12.oms.infrastructure;

import io.github.lachin12.oms.application.*;
import io.github.lachin12.oms.application.simulation.*;
import io.github.lachin12.oms.domain.Execution;
import io.github.lachin12.oms.domain.Order;
import io.github.lachin12.oms.domain.OrderStatus;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/** Simulated reports use the same durable receipt and order transaction as execution adapters. */
@Testcontainers
@Timeout(30)
class ScriptedExecutionSimulatorIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.12-bookworm");
    private static final UUID ACCOUNT = uuid(1);
    private static final UUID INSTRUMENT = uuid(2);
    private static final UUID SCENARIO = uuid(50);
    private static final Instant NOW = Instant.parse("2026-01-01T09:00:00.123456Z");
    private static final AtomicLong IDS = new AtomicLong(10_000);
    private static JdbcTemplate jdbc;
    private static JdbcOmsStore store;
    private static SpringUnitOfWork transactions;
    private OmsService service;
    private ScriptedExecutionSimulator simulator;
    private ExecutorService workers;

    @BeforeAll
    static void migrateDatabase() {
        DriverManagerDataSource dataSource = dataSource(POSTGRES.getJdbcUrl());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new JdbcOmsStore(jdbc);
        transactions = new SpringUnitOfWork(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE execution_receipts, outbox_delivery, outbox_events, order_events, submission_keys, orders, accounts, instruments CASCADE");
        seed(jdbc);
        service = newService(store, store);
        simulator = simulator(service);
        workers = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void explicitStepsMoveAMarketOrderFromAcceptanceThroughPartialAndFullExecution() {
        StoredOrder accepted = service.submit(new SubmitOrder(ACCOUNT, INSTRUMENT, Side.BUY,
                OrderType.MARKET, decimal("10"), null), "market-script").storedOrder();
        assertEquals(OrderStatus.ACCEPTED, accepted.order().status());
        assertEquals(0, count("execution_receipts"));
        SimulationResult partial = simulator.execute(accepted.order().id(), step(0, 0, "4", "100.01"));
        SimulationResult full = simulator.execute(accepted.order().id(), step(1, 1, "6", "100.02"));

        assertFalse(partial.replay());
        assertEquals(OrderStatus.PARTIALLY_FILLED, partial.storedOrder().order().status());
        assertEquals(decimal("4.00000000"), partial.storedOrder().order().filledQuantity());
        assertEquals(OrderStatus.FILLED, full.storedOrder().order().status());
        assertEquals(decimal("10.00000000"), full.storedOrder().order().filledQuantity());
        assertEquals(2, full.storedOrder().version());
        assertCounts(2, 4);
    }

    @Test
    void restartingTheSimulatorAndReplayingOldStepsReturnsCurrentFilledStateWithoutNewFacts() {
        StoredOrder order = submit("restart");
        SimulationStep firstStep = step(0, 0, "4", "100");
        SimulationStep secondStep = step(1, 1, "6", "100.01");
        SimulationResult first = simulator.execute(order.order().id(), firstStep);
        SimulationResult second = simulator.execute(order.order().id(), secondStep);
        ScriptedExecutionSimulator restarted = simulator(newService(store, store));

        SimulationResult firstReplay = restarted.execute(order.order().id(), firstStep);
        SimulationResult secondReplay = restarted.execute(order.order().id(), secondStep);
        assertTrue(firstReplay.replay());
        assertTrue(secondReplay.replay());
        assertEquals(first.executionId(), firstReplay.executionId());
        assertEquals(second.executionId(), secondReplay.executionId());
        assertEquals(OrderStatus.FILLED, firstReplay.storedOrder().order().status());
        assertEquals(2, firstReplay.storedOrder().version());
        assertEquals(second.storedOrder().order().events(), firstReplay.storedOrder().order().events());
        assertCounts(2, 4);
    }

    @Test
    void anIdenticalReportCanReplayAfterLaterCancellationEvenWithItsOldExpectedVersion() {
        StoredOrder order = submit("cancelled-replay");
        SimulationStep originalStep = step(0, 0, "2", "100");
        SimulationResult original = simulator.execute(order.order().id(), originalStep);
        StoredOrder cancelled = service.cancel(order.order().id(), 1, "User request");

        SimulationResult replay = simulator(newService(store, store)).execute(order.order().id(), originalStep);
        assertTrue(replay.replay());
        assertEquals(original.executionId(), replay.executionId());
        assertEquals(OrderStatus.CANCELLED, replay.storedOrder().order().status());
        assertEquals(cancelled.order().events(), replay.storedOrder().order().events());
        assertEquals(2, replay.storedOrder().version());
        assertCounts(1, 4);
    }

    @Test
    void conflictingPayloadForADeterministicStepCannotChangeTheFirstExecution() {
        StoredOrder order = submit("step-payload-conflict");
        SimulationResult original = simulator.execute(order.order().id(), step(0, 0, "2", "100"));
        assertThrows(ExecutionConflictException.class,
                () -> simulator.execute(order.order().id(), step(0, 0, "3", "100")));
        assertThrows(ExecutionConflictException.class,
                () -> simulator.execute(order.order().id(), step(0, 0, "2", "99.99")));
        SimulationStep changedTime = new SimulationStep(SCENARIO, 0, 0, decimal("2"), decimal("100"), NOW.minusSeconds(2));
        assertThrows(ExecutionConflictException.class, () -> simulator.execute(order.order().id(), changedTime));
        assertEquals(original.storedOrder().order().events(), service.get(order.order().id()).order().events());
        assertCounts(1, 3);
    }

    @Test
    void aGlobalExecutionIdentityCannotBeAppliedToAnotherOrder() {
        StoredOrder first = submit("global-a");
        StoredOrder second = submit("global-b");
        Execution report = report(600, "2", "100");
        service.reportExecution(first.order().id(), 0, report);
        assertThrows(ExecutionConflictException.class,
                () -> service.reportExecution(second.order().id(), 0, report));
        StoredOrder unchanged = service.get(second.order().id());
        assertEquals(0, unchanged.version());
        assertEquals(OrderStatus.ACCEPTED, unchanged.order().status());
        assertEquals(second.order().events(), unchanged.order().events());
        assertEquals(1, count("execution_receipts"));
        assertEquals(5, count("outbox_events"));
    }

    @Test
    void simultaneousIdenticalReportsApplyOnceAndTheWaitingReportReplays() throws Exception {
        StoredOrder order = submit("parallel-reports");
        Execution report = report(601, "2", "100");
        CyclicBarrier bothClaiming = new CyclicBarrier(2);
        ExecutionStore coordinated = (id, execution) -> {
            await(bothClaiming);
            return store.claimExecution(id, execution);
        };
        OmsService concurrentService = newService(store, coordinated);
        Future<ExecutionResult> first = workers.submit(() -> concurrentService.reportExecution(order.order().id(), 0, report));
        Future<ExecutionResult> second = workers.submit(() -> concurrentService.reportExecution(order.order().id(), 0, report));
        ExecutionResult a = first.get(10, TimeUnit.SECONDS);
        ExecutionResult b = second.get(10, TimeUnit.SECONDS);
        assertNotEquals(a.replay(), b.replay());
        assertEquals(a.storedOrder().order().events(), b.storedOrder().order().events());
        assertEquals(1, a.storedOrder().version());
        assertEquals(decimal("2.00000000"), service.get(order.order().id()).order().filledQuantity());
        assertCounts(1, 3);
    }

    @Test
    void waitingReporterCanOwnTheExecutionAfterTheFirstReceiptTransactionRollsBack() throws Exception {
        StoredOrder order = submit("rollback-reporter");
        Execution report = report(602, "2", "100");
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        Future<?> owner = workers.submit(() -> assertThrows(DeliberateRollback.class,
                () -> transactions.execute(() -> {
                    assertTrue(store.claimExecution(order.order().id(), report).owner());
                    claimed.countDown();
                    await(releaseOwner);
                    throw new DeliberateRollback();
                })));
        Future<ExecutionResult> contender;
        try {
            assertTrue(claimed.await(5, TimeUnit.SECONDS));
            OmsService waitingService = newService(store, (id, execution) -> {
                jdbc.queryForObject("SELECT set_config('application_name', 'waiting-execution-receipt', true)", String.class);
                return store.claimExecution(id, execution);
            });
            contender = workers.submit(() -> waitingService.reportExecution(order.order().id(), 0, report));
            awaitDatabaseWaiter("waiting-execution-receipt");
        } finally {
            releaseOwner.countDown();
        }
        owner.get(10, TimeUnit.SECONDS);
        assertFalse(contender.get(10, TimeUnit.SECONDS).replay());
        assertCounts(1, 3);
    }

    @Test
    void staleNewStepRollsBackItsReceiptAndCanBeRetriedWithTheReloadedVersion() {
        StoredOrder order = submit("stale-step");
        simulator.execute(order.order().id(), step(0, 0, "2", "100"));
        assertThrows(ConcurrentOrderUpdateException.class,
                () -> simulator.execute(order.order().id(), step(1, 0, "2", "100")));
        assertCounts(1, 3);
        SimulationResult retry = simulator.execute(order.order().id(), step(1, 1, "2", "100"));
        assertFalse(retry.replay());
        assertEquals(decimal("4.00000000"), retry.storedOrder().order().filledQuantity());
        assertCounts(2, 4);
    }

    enum InvalidReport { OVERFILL, LIMIT_PRICE, QUANTITY_STEP, PRICE_TICK, FUTURE_TIME }

    @ParameterizedTest
    @EnumSource(InvalidReport.class)
    void invalidReportsDoNotPoisonTheirExecutionIdentity(InvalidReport invalid) {
        StoredOrder order = submit("invalid-report");
        UUID id = uuid(610);
        Execution report = switch (invalid) {
            case OVERFILL -> new Execution(id, decimal("11"), decimal("100"), NOW);
            case LIMIT_PRICE -> new Execution(id, decimal("1"), decimal("100.02"), NOW);
            case QUANTITY_STEP -> new Execution(id, decimal("1.5"), decimal("100"), NOW);
            case PRICE_TICK -> new Execution(id, decimal("1"), decimal("100.001"), NOW);
            case FUTURE_TIME -> new Execution(id, decimal("1"), decimal("100"), NOW.plusSeconds(1));
        };
        assertThrows(InvalidOrderRequestException.class,
                () -> service.reportExecution(order.order().id(), 0, report));
        assertCounts(0, 2);
        assertEquals(0, service.get(order.order().id()).version());
        assertFalse(service.reportExecution(order.order().id(), 0,
                new Execution(id, decimal("1"), decimal("100"), NOW)).replay());
        assertCounts(1, 3);
    }

    @Test
    void aCancelledOrderCannotCommitANewReceipt() {
        StoredOrder order = submit("cancel-before-step");
        service.cancel(order.order().id(), 0, "Cancelled before simulation");
        assertThrows(OrderConflictException.class,
                () -> simulator.execute(order.order().id(), step(0, 1, "1", "100")));
        assertCounts(0, 3);
        assertEquals(OrderStatus.CANCELLED, service.get(order.order().id()).order().status());
    }

    @Test
    void aMissingOrderCannotLeaveAReceiptBehind() {
        assertThrows(NotFoundException.class, () -> service.reportExecution(uuid(999_999), 0, report(611, "1", "100")));
        assertEquals(0, count("execution_receipts"));
    }

    enum FailurePoint { EVENT, OUTBOX }

    @ParameterizedTest
    @EnumSource(FailurePoint.class)
    void persistenceFailureRollsBackTheReceiptOrderAndAllNewFacts(FailurePoint point) {
        StoredOrder order = submit("failed-execution-write");
        String table = point == FailurePoint.EVENT ? "order_events" : "outbox_events";
        jdbc.execute("CREATE FUNCTION reject_simulated_execution() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'deliberate execution persistence failure'; END; $$");
        jdbc.execute("CREATE TRIGGER reject_simulated_execution BEFORE INSERT ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION reject_simulated_execution()");
        try {
            assertThrows(DataAccessException.class,
                    () -> simulator.execute(order.order().id(), step(0, 0, "2", "100")));
            assertCounts(0, 2);
            assertEquals(order.order().events(), service.get(order.order().id()).order().events());
            assertEquals(0, service.get(order.order().id()).version());
        } finally {
            jdbc.execute("DROP TRIGGER reject_simulated_execution ON " + table);
            jdbc.execute("DROP FUNCTION reject_simulated_execution()");
        }
        assertFalse(simulator.execute(order.order().id(), step(0, 0, "2", "100")).replay());
        assertCounts(1, 3);
    }

    @Test
    void receiptMustMatchAnExecutionFactAtCommit() {
        StoredOrder order = submit("receipt-fact-required");
        assertThrows(RuntimeException.class, () -> transactions.execute(() ->
                store.claimExecution(order.order().id(), report(620, "1", "100"))));
        assertCounts(0, 2);

        assertThrows(RuntimeException.class, () -> transactions.execute(() -> {
            store.claimExecution(order.order().id(), report(621, "1", "100"));
            return store.update(order.order().fill(report(621, "2", "100"), NOW), 0);
        }));
        assertCounts(0, 2);
        assertEquals(0, service.get(order.order().id()).version());
    }

    @Test
    void legacyDirectFactsCanAcquireTheirMatchingReceiptOnReplay() {
        StoredOrder original = submit("legacy-direct-fact");
        Execution report = report(630, "2", "100");
        StoredOrder directlyUpdated = transactions.execute(() -> store.update(original.order().fill(report, NOW), 0));
        assertCounts(0, 3);
        ExecutionResult replay = service.reportExecution(original.order().id(), 0, report);
        assertTrue(replay.replay());
        assertEquals(directlyUpdated.order().events(), replay.storedOrder().order().events());
        assertEquals(1, replay.storedOrder().version());
        assertCounts(1, 3);
    }

    @Test
    void mismatchingLegacyFactRejectsTheReceiptAndPreservesTheOriginalHistory() {
        StoredOrder original = submit("legacy-conflict");
        Execution report = report(631, "2", "100");
        StoredOrder directlyUpdated = transactions.execute(() -> store.update(original.order().fill(report, NOW), 0));
        assertThrows(ExecutionConflictException.class,
                () -> service.reportExecution(original.order().id(), 0, report(631, "3", "100")));
        assertCounts(0, 3);
        assertEquals(directlyUpdated.order().events(), service.get(original.order().id()).order().events());
    }

    @Test
    void committedReceiptsCannotBeUpdatedOrDeleted() {
        StoredOrder original = submit("immutable-receipt");
        Execution report = report(640, "1", "100");
        service.reportExecution(original.order().id(), 0, report);
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE execution_receipts SET price = price WHERE execution_id = ?", report.id()));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("DELETE FROM execution_receipts WHERE execution_id = ?", report.id()));
        assertTrue(service.reportExecution(original.order().id(), 0, report).replay());
        assertCounts(1, 3);
    }

    enum Race {
        PARTIAL_FILL_FIRST(true, "4"), PARTIAL_CANCEL_FIRST(false, "4"),
        FULL_FILL_FIRST(true, "10"), FULL_CANCEL_FIRST(false, "10");
        final boolean fillWins;
        final String quantity;
        Race(boolean fillWins, String quantity) { this.fillWins = fillWins; this.quantity = quantity; }
    }

    @ParameterizedTest
    @EnumSource(Race.class)
    void simulatedExecutionAndCancellationRevalidateTheSharedVersionAndCommitNoOrphanReceipt(Race race) throws Exception {
        StoredOrder original = submit("simulated-cancel-race");
        CyclicBarrier bothRead = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        OmsService fillService = newService(racingStore(original.order().id(), bothRead, winnerCommitted, !race.fillWins), store);
        OmsService cancelService = newService(racingStore(original.order().id(), bothRead, winnerCommitted, race.fillWins), store);
        SimulationStep step = step(0, 0, race.quantity, "100");
        Future<Outcome> fill = workers.submit(() -> {
            try { return capture(() -> simulator(fillService).execute(original.order().id(), step)); }
            finally { if (race.fillWins) winnerCommitted.countDown(); }
        });
        Future<Outcome> cancel = workers.submit(() -> {
            try { return capture(() -> cancelService.cancel(original.order().id(), 0, "Concurrent cancellation")); }
            finally { if (!race.fillWins) winnerCommitted.countDown(); }
        });
        Outcome fillOutcome = fill.get(10, TimeUnit.SECONDS);
        Outcome cancelOutcome = cancel.get(10, TimeUnit.SECONDS);
        assertNull(race.fillWins ? fillOutcome.failure() : cancelOutcome.failure());
        assertInstanceOf(ConcurrentOrderUpdateException.class,
                race.fillWins ? cancelOutcome.failure() : fillOutcome.failure());
        StoredOrder committed = service.get(original.order().id());
        assertEquals(1, committed.version());
        assertCounts(race.fillWins ? 1 : 0, 3);
        if (!race.fillWins) {
            assertEquals(OrderStatus.CANCELLED, committed.order().status());
            assertThrows(OrderConflictException.class,
                    () -> simulator.execute(original.order().id(), step(0, 1, race.quantity, "100")));
            assertCounts(0, 3);
        } else if (race.quantity.equals("4")) {
            StoredOrder cancelled = service.cancel(original.order().id(), 1, "Reloaded cancellation");
            assertEquals(OrderStatus.CANCELLED, cancelled.order().status());
            assertEquals(decimal("4.00000000"), cancelled.order().filledQuantity());
            assertCounts(1, 4);
        } else {
            assertEquals(OrderStatus.FILLED, committed.order().status());
            assertThrows(OrderConflictException.class,
                    () -> service.cancel(original.order().id(), 1, "Too late"));
            assertCounts(1, 3);
        }
    }

    @Test
    void migrationBackfillsLegacyFillsWithoutChangingTheirHistoryAndMakesThemReplayable() {
        jdbc.execute("CREATE SCHEMA simulation_upgrade");
        DriverManagerDataSource oldDataSource = dataSource(POSTGRES.getJdbcUrl()
                + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=simulation_upgrade");
        try {
            Flyway.configure().dataSource(oldDataSource).defaultSchema("simulation_upgrade").target("3").load().migrate();
            JdbcTemplate oldJdbc = new JdbcTemplate(oldDataSource);
            JdbcOmsStore oldStore = new JdbcOmsStore(oldJdbc);
            SpringUnitOfWork oldTransactions = new SpringUnitOfWork(new DataSourceTransactionManager(oldDataSource));
            OmsService oldService = newService(oldStore, oldStore, oldTransactions);
            StoredOrder original = oldService.submit(command(), "legacy-filled").storedOrder();
            Execution report = report(650, "2", "100");
            StoredOrder filled = oldTransactions.execute(() -> oldStore.update(original.order().fill(report, NOW), 0));
            Flyway.configure().dataSource(oldDataSource).defaultSchema("simulation_upgrade").load().migrate();

            assertEquals(1, oldJdbc.queryForObject("SELECT count(*) FROM execution_receipts", Integer.class));
            assertEquals(report.quantity(), oldJdbc.queryForObject("SELECT quantity FROM execution_receipts WHERE execution_id = ?", BigDecimal.class, report.id()));
            assertEquals(report.price(), oldJdbc.queryForObject("SELECT price FROM execution_receipts WHERE execution_id = ?", BigDecimal.class, report.id()));
            ExecutionResult replay = oldService.reportExecution(original.order().id(), 0, report);
            assertTrue(replay.replay());
            assertEquals(filled.order().events(), replay.storedOrder().order().events());
            assertEquals(1, replay.storedOrder().version());
            assertEquals(3, oldJdbc.queryForObject("SELECT count(*) FROM order_events", Integer.class));
            assertEquals(3, oldJdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class));
        } finally {
            jdbc.execute("DROP SCHEMA simulation_upgrade CASCADE");
        }
    }

    private static OrderStore racingStore(UUID orderId, CyclicBarrier bothRead,
                                           CountDownLatch winnerCommitted, boolean waitForWinner) {
        return new OrderStore() {
            @Override public Optional<StoredOrder> find(UUID id) {
                Optional<StoredOrder> result = store.find(id);
                if (id.equals(orderId)) await(bothRead);
                return result;
            }
            @Override public List<StoredOrder> list(UUID accountId, int limit, int offset) { return store.list(accountId, limit, offset); }
            @Override public StoredOrder insert(Order order) { return store.insert(order); }
            @Override public StoredOrder update(Order order, long expectedVersion) {
                if (waitForWinner) await(winnerCommitted);
                return store.update(order, expectedVersion);
            }
        };
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
        fail("Execution reporter did not become a receipt lock waiter");
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

    private static OmsService newService(OrderStore orderStore, ExecutionStore executionStore) {
        return newService(orderStore, executionStore, transactions);
    }
    private static OmsService newService(OrderStore orderStore, ExecutionStore executionStore, SpringUnitOfWork unitOfWork) {
        JdbcOmsStore references = orderStore instanceof JdbcOmsStore jdbcStore ? jdbcStore : store;
        return new OmsService(references, orderStore, references, executionStore, unitOfWork,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> uuid(IDS.getAndIncrement()));
    }
    private static ScriptedExecutionSimulator simulator(OmsService service) {
        return new ScriptedExecutionSimulator(service, new NameBasedSimulationExecutionIds());
    }
    private static SimulationStep step(int step, long expectedVersion, String quantity, String price) {
        return new SimulationStep(SCENARIO, step, expectedVersion, decimal(quantity), decimal(price), NOW);
    }
    private StoredOrder submit(String key) { return service.submit(command(), key).storedOrder(); }
    private static SubmitOrder command() {
        return new SubmitOrder(ACCOUNT, INSTRUMENT, Side.BUY, OrderType.LIMIT, decimal("10"), decimal("100.01"));
    }
    private static Execution report(long id, String quantity, String price) {
        return new Execution(uuid(id), decimal(quantity), decimal(price), NOW);
    }
    private static DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private static void seed(JdbcTemplate target) {
        target.update("INSERT INTO accounts(id, name, active) VALUES (?, ?, true)", ACCOUNT, "Simulation account");
        target.update("INSERT INTO instruments(id, symbol, currency, tick_size, quantity_step) VALUES (?, ?, ?, ?, ?)",
                INSTRUMENT, "SIMULATION", "USD", decimal("0.01"), decimal("1"));
    }
    private static int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private static void assertCounts(int receipts, int facts) {
        assertEquals(receipts, count("execution_receipts"));
        assertEquals(facts, count("order_events"));
        assertEquals(facts, count("outbox_events"));
    }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static UUID uuid(long value) { return new UUID(0, value); }
    private static Outcome capture(Callable<?> action) {
        try { return new Outcome(action.call(), null); }
        catch (Exception ex) { return new Outcome(null, ex); }
    }
    private record Outcome(Object value, Exception failure) { }
    private static final class DeliberateRollback extends RuntimeException { }
}
