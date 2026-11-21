package io.github.lachin12.oms.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.lachin12.oms.application.ConcurrentOrderUpdateException;
import io.github.lachin12.oms.application.IdempotencyConflictException;
import io.github.lachin12.oms.application.NotFoundException;
import io.github.lachin12.oms.application.OmsService;
import io.github.lachin12.oms.application.StoredOrder;
import io.github.lachin12.oms.application.SubmissionResult;
import io.github.lachin12.oms.domain.Account;
import io.github.lachin12.oms.domain.Instrument;
import io.github.lachin12.oms.domain.Order;
import io.github.lachin12.oms.domain.OrderType;
import io.github.lachin12.oms.domain.Side;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = {
        "spring.jackson.deserialization.fail-on-unknown-properties=true",
        "spring.jackson.deserialization.accept-float-as-int=false",
        "spring.mvc.problemdetails.enabled=true"
})
@ContextConfiguration(classes = OrderControllerTest.ApiTestConfiguration.class)
class OrderControllerTest {
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INSTRUMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant AT = Instant.parse("2026-11-16T12:00:00Z");
    private static final String BODY = """
            {"accountId":"%s","instrumentId":"%s","side":"BUY","type":"LIMIT",
             "quantity":"10","limitPrice":"100.25"}
            """.formatted(ACCOUNT_ID, INSTRUMENT_ID);

    @Configuration(proxyBeanMethods = false)
    @Import({OrderController.class, ApiExceptionHandler.class})
    static class ApiTestConfiguration { }

    @Autowired private MockMvc mvc;
    @MockitoBean private OmsService service;

    @Test
    void newSubmissionReturnsAcceptedOrderAndConcurrencyHeaders() throws Exception {
        given(service.submit(any(), eq("key-1")))
                .willReturn(new SubmissionResult(new StoredOrder(accepted(), 0), false));

        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/" + ORDER_ID))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.instrumentId").value(INSTRUMENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.quantity").value("10.00000000"))
                .andExpect(jsonPath("$.limitPrice").value("100.25000000"))
                .andExpect(jsonPath("$.filledQuantity").value("0.00000000"))
                .andExpect(jsonPath("$.remainingQuantity").value("10.00000000"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.events").doesNotExist());
    }

    @Test
    void replayReturns200AndIdentifiesReplay() throws Exception {
        given(service.submit(any(), eq("key-1")))
                .willReturn(new SubmissionResult(new StoredOrder(accepted(), 0), true));

        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(header().string("ETag", "\"0\""));
    }

    @Test
    void conflictingIdempotencyKeyReturnsProblemDetail() throws Exception {
        given(service.submit(any(), eq("key-1"))).willThrow(new IdempotencyConflictException());

        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("idempotency-conflict"))
                .andExpect(jsonPath("$.type").value("urn:trading-oms:error:idempotency-conflict"));
    }

    @Test
    void missingSubmissionKeyFailsBeforeApplicationCall() throws Exception {
        mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "contains space", "contains\ttab"})
    void invalidSubmissionKeyFailsBeforeApplicationCall(String key) throws Exception {
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{not-json}",
            "{\"accountId\":\"not-a-uuid\"}",
            "{\"accountId\":\"00000000-0000-0000-0000-000000000002\",\"side\":\"HOLD\"}"
    })
    void missingOrMalformedInputReturns400(String body) throws Exception {
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
        verifyNoInteractions(service);
    }

    @Test
    void excessiveDecimalPrecisionIsRejectedBeforeApplicationCall() throws Exception {
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"10\"", "\"0.000000001\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void unknownFieldsFailInsteadOfSilentlyIgnoringTypos() throws Exception {
        mvc.perform(post("/api/v1/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("\"quantity\"", "\"quanity\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void getReturnsCurrentVersion() throws Exception {
        given(service.get(ORDER_ID)).willReturn(new StoredOrder(accepted(), 7));
        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"7\""))
                .andExpect(jsonPath("$.version").value(7));
    }

    @Test
    void missingOrderReturns404() throws Exception {
        given(service.get(ORDER_ID)).willThrow(new NotFoundException("Order not found"));
        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void invalidPathIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/orders/not-a-uuid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void listingAppliesDefaultBounds() throws Exception {
        given(service.list(ACCOUNT_ID, 50, 0)).willReturn(List.of(new StoredOrder(accepted(), 0)));
        mvc.perform(get("/api/v1/orders").param("accountId", ACCOUNT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ORDER_ID.toString()));
        verify(service).list(ACCOUNT_ID, 50, 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1"})
    void invalidListingLimitFails(String limit) throws Exception {
        mvc.perform(get("/api/v1/orders").param("accountId", ACCOUNT_ID.toString()).param("limit", limit))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void missingListingAccountFails() throws Exception {
        mvc.perform(get("/api/v1/orders"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void cancellationPassesExpectedVersionAndReturnsNewEtag() throws Exception {
        given(service.cancel(ORDER_ID, 0, "user request"))
                .willReturn(new StoredOrder(accepted().cancel("user request", AT.plusSeconds(1)), 1));
        mvc.perform(post("/api/v1/orders/{id}/cancel", ORDER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"reason\":\"user request\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.version").value(1));
        verify(service).cancel(ORDER_ID, 0, "user request");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"reason\":\"user request\"}",
            "{\"expectedVersion\":null,\"reason\":\"user request\"}",
            "{\"expectedVersion\":-1,\"reason\":\"user request\"}",
            "{\"expectedVersion\":0.5,\"reason\":\"user request\"}",
            "{\"expectedVersion\":0,\"reason\":\"   \"}",
            "{\"expectedVersion\":0}"
    })
    void cancellationRequiresAnExactVersionAndReason(String body) throws Exception {
        mvc.perform(post("/api/v1/orders/{id}/cancel", ORDER_ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-request"));
        verifyNoInteractions(service);
    }

    @Test
    void staleCancellationReturns409() throws Exception {
        given(service.cancel(ORDER_ID, 0, "request")).willThrow(new ConcurrentOrderUpdateException());
        mvc.perform(post("/api/v1/orders/{id}/cancel", ORDER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"reason\":\"request\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("concurrent-update"));
    }

    @Test
    void terminalStateCancellationReturns409() throws Exception {
        given(service.cancel(ORDER_ID, 0, "request")).willThrow(new IllegalStateException("Illegal order transition"));
        mvc.perform(post("/api/v1/orders/{id}/cancel", ORDER_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"reason\":\"request\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("illegal-state"));
    }

    @Test
    void historyUsesStableEventIdentityAndSequence() throws Exception {
        given(service.get(ORDER_ID)).willReturn(new StoredOrder(accepted(), 0));
        mvc.perform(get("/api/v1/orders/{id}/events", ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$[0].id").value(ORDER_ID + ":1"))
                .andExpect(jsonPath("$[0].type").value("SUBMITTED"))
                .andExpect(jsonPath("$[1].sequence").value(2))
                .andExpect(jsonPath("$[1].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[1].cumulativeQuantity").value("0.00000000"));
    }

    @Test
    void unexpectedFailureDoesNotLeakInfrastructureDetails() throws Exception {
        given(service.get(ORDER_ID)).willThrow(new RuntimeException("SQL password=secret table=orders"));
        mvc.perform(get("/api/v1/orders/{id}", ORDER_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("SQL"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void thereIsNoPublicFillRoute() throws Exception {
        mvc.perform(post("/api/v1/orders/{id}/fills", ORDER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    private static Order accepted() {
        Account account = new Account(ACCOUNT_ID, "Test account", true);
        Instrument instrument = new Instrument(INSTRUMENT_ID, "TEST", Currency.getInstance("USD"),
                new BigDecimal("0.01"), new BigDecimal("1"));
        return Order.submit(ORDER_ID, account, instrument, Side.BUY, OrderType.LIMIT,
                new BigDecimal("10"), new BigDecimal("100.25"), AT).accept(AT);
    }
}
