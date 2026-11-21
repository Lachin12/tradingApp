package io.github.lachin12.oms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "oms.simulation.enabled=true")
@Import(SimulationHttpIT.FixedClock.class)
class SimulationHttpIT {
    private static final String ORDERS = "/api/v1/orders";
    private static final String SCENARIO = "00000000-0000-0000-0000-000000000100";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.12-bookworm");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary
        Clock simulatorClock() {
            return Clock.fixed(Instant.parse("2026-01-01T09:00:10Z"), ZoneOffset.UTC);
        }
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void explicitStepsProducePartialAndFinalFillsAndStableReplayAfterCompletion() {
        String id = submit("sim-http-flow");
        JsonNode accepted = http.getForObject(ORDERS + "/" + id, JsonNode.class);
        assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(accepted.path("filledQuantity").asText()).isEqualTo("0.00000000");

        var first = step(id, body(0, 0, "4", "100.25", "2026-01-01T09:00:01Z"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().path("replay").asBoolean()).isFalse();
        assertThat(first.getBody().path("order").path("status").asText()).isEqualTo("PARTIALLY_FILLED");
        assertThat(first.getBody().path("order").path("filledQuantity").asText()).isEqualTo("4.00000000");
        assertThat(first.getHeaders().getETag()).isEqualTo("\"1\"");
        String executionId = first.getBody().path("executionId").asText();

        var second = step(id, body(1, 1, "6", "100.25", "2026-01-01T09:00:02Z"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().path("order").path("status").asText()).isEqualTo("FILLED");
        assertThat(second.getHeaders().getETag()).isEqualTo("\"2\"");

        var replay = step(id, body(0, 0, "4.000000000", "100.250", "2026-01-01T09:00:01Z"));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("replay").asBoolean()).isTrue();
        assertThat(replay.getBody().path("executionId").asText()).isEqualTo(executionId);
        assertThat(replay.getBody().path("order").path("status").asText()).isEqualTo("FILLED");
        assertThat(replay.getHeaders().getFirst("Execution-Replayed")).isEqualTo("true");

        assertThat(step(id, body(0, 0, "5", "100.25", "2026-01-01T09:00:01Z")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.postForEntity(ORDERS + "/" + id + "/cancel",
                Map.of("expectedVersion", 2, "reason", "Already filled"), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("execution_receipts", id)).isEqualTo(2);
        assertThat(count("order_events", id)).isEqualTo(4);
        assertThat(count("outbox_events", id)).isEqualTo(4);
        assertThat(http.getForObject(ORDERS + "/" + id + "/events", JsonNode.class).size()).isEqualTo(4);
        assertThat(http.getForObject("/v3/api-docs", String.class)).contains("/api/v1/simulation/orders/");
    }

    @Test
    void failedOrStaleStepsDoNotReserveAnExecutionOrChangeTheOrder() {
        String id = submit("sim-http-validation");
        assertThat(step(id, body(0, 0, "11", "100.25", "2026-01-01T09:00:01Z")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(step(id, body(0, 0, "1", "100.25", "2026-01-01T09:00:11Z")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(step(id, body(0, 0, "1", "100.25", "+300000-01-01T00:00:00Z")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(count("execution_receipts", id)).isZero();
        assertThat(count("order_events", id)).isEqualTo(2);

        assertThat(step(id, body(0, 0, "4", "100.25", "2026-01-01T09:00:01Z")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(step(id, body(1, 0, "6", "100.25", "2026-01-01T09:00:02Z")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(count("execution_receipts", id)).isEqualTo(1);
        assertThat(count("order_events", id)).isEqualTo(3);
    }

    private String submit(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        Map<String, String> input = Map.of("accountId", "00000000-0000-0000-0000-000000000001",
                "instrumentId", "00000000-0000-0000-0000-000000000002", "side", "BUY",
                "type", "MARKET", "quantity", "10");
        var created = http.exchange(ORDERS, HttpMethod.POST, new HttpEntity<>(input, headers), JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().path("id").asText();
    }

    private ResponseEntity<JsonNode> step(String orderId, Map<String, Object> input) {
        return http.postForEntity("/api/v1/simulation/orders/" + orderId + "/steps", input, JsonNode.class);
    }

    private static Map<String, Object> body(int step, long version, String quantity, String price, String at) {
        Map<String, Object> input = new HashMap<>();
        input.put("scenarioId", SCENARIO);
        input.put("step", step);
        input.put("expectedVersion", version);
        input.put("quantity", quantity);
        input.put("price", price);
        input.put("executedAt", at);
        return input;
    }

    private int count(String table, String orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE order_id = ?",
                Integer.class, UUID.fromString(orderId));
    }
}
