package io.github.lachin12.oms.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.lachin12.oms.application.ConcurrentOrderUpdateException;
import io.github.lachin12.oms.application.ExecutionConflictException;
import io.github.lachin12.oms.application.InvalidOrderRequestException;
import io.github.lachin12.oms.application.OrderConflictException;
import io.github.lachin12.oms.application.StoredOrder;
import io.github.lachin12.oms.application.simulation.ScriptedExecutionSimulator;
import io.github.lachin12.oms.application.simulation.SimulationResult;
import io.github.lachin12.oms.domain.Account;
import io.github.lachin12.oms.domain.Execution;
import io.github.lachin12.oms.domain.Instrument;
import io.github.lachin12.oms.domain.Order;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = {
        "oms.simulation.enabled=true",
        "spring.jackson.deserialization.fail-on-unknown-properties=true",
        "spring.jackson.deserialization.accept-float-as-int=false",
        "spring.mvc.problemdetails.enabled=true"
})
@ContextConfiguration(classes = SimulationControllerTest.ApiTestConfiguration.class)
class SimulationControllerTest {
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INSTRUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SCENARIO_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID SECOND_EXECUTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final Instant AT = Instant.parse("2026-11-16T12:00:00Z");
    private static final String PATH = "/api/v1/simulation/orders/" + ORDER_ID + "/steps";
    private static final String BODY = """
            {"scenarioId":"%s","step":0,"expectedVersion":0,
             "quantity":"4","price":"100.25","executedAt":"2026-11-16T12:00:01Z"}
            """.formatted(SCENARIO_ID);

    @Configuration(proxyBeanMethods = false)
    @Import({SimulationController.class, ApiExceptionHandler.class})
    static class ApiTestConfiguration { }

    @Autowired private MockMvc mvc;
    @MockitoBean private ScriptedExecutionSimulator simulator;

    @Test
    void explicitPartialFillReturnsExactDecimalsAndVersion() throws Exception {
        given(simulator.execute(eq(ORDER_ID), any()))
                .willReturn(new SimulationResult(EXECUTION_ID, new StoredOrder(partial(), 1), false));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Execution-Replayed", "false"))
                .andExpect(jsonPath("$.executionId").value(EXECUTION_ID.toString()))
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.order.status").value("PARTIALLY_FILLED"))
                .andExpect(jsonPath("$.order.quantity").value("10.00000000"))
                .andExpect(jsonPath("$.order.filledQuantity").value("4.00000000"))
                .andExpect(jsonPath("$.order.remainingQuantity").value("6.00000000"))
                .andExpect(jsonPath("$.order.limitPrice").value("100.25000000"))
                .andExpect(jsonPath("$.order.version").value(1));
        verify(simulator).execute(eq(ORDER_ID), argThat(step ->
                step.scenarioId().equals(SCENARIO_ID) && step.step() == 0 && step.expectedVersion() == 0
                        && step.quantity().compareTo(new BigDecimal("4")) == 0
                        && step.price().compareTo(new BigDecimal("100.25")) == 0
                        && step.executedAt().equals(AT.plusSeconds(1))));
    }

    @Test
    void explicitFinalStepReturnsFilledStatus() throws Exception {
        given(simulator.execute(eq(ORDER_ID), any()))
                .willReturn(new SimulationResult(SECOND_EXECUTION_ID, new StoredOrder(filled(), 2), false));
        String finalStep = BODY.replace("\"step\":0", "\"step\":1")
                .replace("\"expectedVersion\":0", "\"expectedVersion\":1")
                .replace("\"quantity\":\"4\"", "\"quantity\":\"6\"");

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(finalStep))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.order.status").value("FILLED"))
                .andExpect(jsonPath("$.order.filledQuantity").value("10.00000000"))
                .andExpect(jsonPath("$.order.remainingQuantity").value("0.00000000"));
    }

    @Test
    void identicalRetryCanReturnCurrentOrderDespiteOriginalStaleVersion() throws Exception {
        given(simulator.execute(eq(ORDER_ID), any()))
                .willReturn(new SimulationResult(EXECUTION_ID, new StoredOrder(filled(), 2), true));

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(header().string("Execution-Replayed", "true"))
                .andExpect(jsonPath("$.executionId").value(EXECUTION_ID.toString()))
                .andExpect(jsonPath("$.replay").value(true))
                .andExpect(jsonPath("$.order.status").value("FILLED"));
        verify(simulator).execute(eq(ORDER_ID), argThat(step -> step.expectedVersion() == 0));
    }

    @Test
    void executionIdentityConflictReturns409() throws Exception {
        given(simulator.execute(eq(ORDER_ID), any()))
                .willThrow(new ExecutionConflictException());

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("execution-conflict"))
                .andExpect(jsonPath("$.type").value("urn:trading-oms:error:execution-conflict"));
    }

    @Test
    void staleVersionForNewExecutionReturns409() throws Exception {
        given(simulator.execute(eq(ORDER_ID), any())).willThrow(new ConcurrentOrderUpdateException());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("concurrent-update"));
    }

    @Test
    void terminalOrderConflictReturns409() throws Exception {
        given(simulator.execute(eq(ORDER_ID), any()))
                .willThrow(new OrderConflictException("Cannot execute a cancelled order"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal-state"));
    }

    @Test
    void instrumentValidationFailureReturns400() throws Exception {
        given(simulator.execute(eq(ORDER_ID), any()))
                .willThrow(new InvalidOrderRequestException("Execution price must respect instrument tick"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidOrMissingInputIsRejectedBeforeSimulatorCall(String body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
        verifyNoInteractions(simulator);
    }

    @Test
    void invalidOrderIdReturns400BeforeSimulatorCall() throws Exception {
        mvc.perform(post("/api/v1/simulation/orders/not-a-uuid/steps")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(simulator);
    }

    private static Stream<String> invalidBodies() {
        return Stream.of(
                "{}",
                "{not-json}",
                BODY.replace("\"scenarioId\":\"" + SCENARIO_ID + "\"", "\"scenarioId\":null"),
                BODY.replace(SCENARIO_ID.toString(), "not-a-uuid"),
                BODY.replace("\"step\":0", "\"step\":null"),
                BODY.replace("\"step\":0", "\"step\":-1"),
                BODY.replace("\"step\":0", "\"step\":1000"),
                BODY.replace("\"step\":0", "\"step\":0.5"),
                BODY.replace("\"expectedVersion\":0", "\"expectedVersion\":null"),
                BODY.replace("\"expectedVersion\":0", "\"expectedVersion\":-1"),
                BODY.replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"),
                BODY.replace("\"quantity\":\"4\"", "\"quantity\":null"),
                BODY.replace("\"quantity\":\"4\"", "\"quantity\":\"0\""),
                BODY.replace("\"quantity\":\"4\"", "\"quantity\":\"-1\""),
                BODY.replace("\"quantity\":\"4\"", "\"quantity\":\"0.000000001\""),
                BODY.replace("\"price\":\"100.25\"", "\"price\":null"),
                BODY.replace("\"price\":\"100.25\"", "\"price\":\"0\""),
                BODY.replace("\"executedAt\":\"2026-11-16T12:00:01Z\"", "\"executedAt\":null"),
                BODY.replace("2026-11-16T12:00:01Z", "not-an-instant"),
                BODY.replace("2026-11-16T12:00:01Z", "+300000-01-01T00:00:00Z"),
                BODY.replace("2026-11-16T12:00:01Z", "0000-01-01T00:00:00Z"),
                BODY.replace("\"quantity\"", "\"quanity\""));
    }

    private static Order accepted() {
        Account account = new Account(ACCOUNT_ID, "Test account", true);
        Instrument instrument = new Instrument(INSTRUMENT_ID, "TEST", Currency.getInstance("USD"),
                new BigDecimal("0.01"), new BigDecimal("1"));
        return Order.submit(ORDER_ID, account, instrument, Side.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100.25"), AT).accept(AT);
    }

    private static Order partial() {
        return accepted().fill(new Execution(EXECUTION_ID, new BigDecimal("4"),
                new BigDecimal("100.25"), AT.plusSeconds(1)), AT.plusSeconds(1));
    }

    private static Order filled() {
        return partial().fill(new Execution(SECOND_EXECUTION_ID, new BigDecimal("6"),
                new BigDecimal("100.25"), AT.plusSeconds(2)), AT.plusSeconds(2));
    }
}
