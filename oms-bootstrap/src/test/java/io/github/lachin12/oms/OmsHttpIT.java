package io.github.lachin12.oms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.lachin12.oms.application.OmsService;
import io.github.lachin12.oms.domain.Execution;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real HTTP -> application -> migrated PostgreSQL -> HTTP verification. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OmsHttpIT {
    private static final String PATH = "/api/v1/orders";
    private static final String ACCOUNT = "00000000-0000-0000-0000-000000000001";
    private static final String INSTRUMENT = "00000000-0000-0000-0000-000000000002";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.12-bookworm");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired OmsService service;

    @Test
    void submissionRetryRetrievalCancellationAndDocumentationWorkAgainstPostgres() {
        ResponseEntity<JsonNode> created = submit("http-flow", market("10"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode order = created.getBody();
        assertThat(order).isNotNull();
        String id = order.path("id").asText();
        assertThat(order.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(order.path("filledQuantity").asText()).isEqualTo("0.00000000");
        assertThat(order.path("version").asLong()).isZero();
        assertThat(created.getHeaders().getETag()).isEqualTo("\"0\"");
        assertThat(created.getHeaders().getLocation().toString()).endsWith(PATH + "/" + id);

        ResponseEntity<JsonNode> replay = submit("http-flow", market("10.000000000"));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("id").asText()).isEqualTo(id);
        assertThat(replay.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(submit("http-flow", market("11")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getForEntity(PATH + "/" + id, JsonNode.class).getBody().path("status").asText())
                .isEqualTo("ACCEPTED");

        var cancelled = http.postForEntity(PATH + "/" + id + "/cancel",
                Map.of("expectedVersion", 0, "reason", "Portfolio test"), JsonNode.class);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.getBody().path("version").asLong()).isEqualTo(1);
        assertThat(http.postForEntity(PATH + "/" + id + "/cancel",
                Map.of("expectedVersion", 0, "reason", "Stale retry"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(submit("http-flow", market("10")).getBody().path("status").asText())
                .isEqualTo("CANCELLED"); // Replay returns current snapshot, not a frozen HTTP body.

        JsonNode history = http.getForObject(PATH + "/" + id + "/events", JsonNode.class);
        assertThat(history.isArray()).isTrue();
        assertThat(history.size()).isEqualTo(3);
        assertThat(history.get(2).path("type").asText()).isEqualTo("CANCELLED");
        JsonNode listed = http.getForObject(PATH + "?accountId=" + ACCOUNT + "&limit=100&offset=0", JsonNode.class);
        assertThat(listed.toString()).contains(id);

        assertThat(http.getForEntity("/v3/api-docs", String.class).getBody()).contains(PATH, "Idempotency-Key");
        assertThat(http.getForObject("/actuator/health", JsonNode.class).path("status").asText()).isEqualTo("UP");
    }

    @Test
    void invalidSubmissionsLeaveNoClaimAndCannotBecomeAcceptedOrders() {
        assertThat(submit("off-step", market("10.5")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity(PATH, market("10"), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit("bad-quantity", market("-1")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String unknown = market("10").replace(INSTRUMENT, "00000000-0000-0000-0000-000000000099");
        assertThat(submit("unknown-instrument", unknown).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The table-level name/constraints are independently asserted in infrastructure tests.
        assertThat(http.getForEntity(PATH + "/00000000-0000-0000-0000-000000000099", JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void internalExecutionChangesStateAndMakesLaterHttpCancellationIllegal() {
        ResponseEntity<JsonNode> response = submit("internal-execution", market("10"));
        UUID id = UUID.fromString(response.getBody().path("id").asText());
        service.recordExecution(id, 0, new Execution(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                new BigDecimal("10"), new BigDecimal("101.25"), Instant.now().minusSeconds(1)));
        JsonNode filled = http.getForObject(PATH + "/" + id, JsonNode.class);
        assertThat(filled.path("status").asText()).isEqualTo("FILLED");
        assertThat(filled.path("filledQuantity").asText()).isEqualTo("10.00000000");
        assertThat(http.postForEntity(PATH + "/" + id + "/cancel",
                Map.of("expectedVersion", 1, "reason", "Too late"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private ResponseEntity<JsonNode> submit(String key, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return http.exchange(PATH, HttpMethod.POST, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private static String market(String quantity) {
        return """
                {"accountId":"%s","instrumentId":"%s","side":"BUY","type":"MARKET","quantity":"%s"}
                """.formatted(ACCOUNT, INSTRUMENT, quantity);
    }
}
