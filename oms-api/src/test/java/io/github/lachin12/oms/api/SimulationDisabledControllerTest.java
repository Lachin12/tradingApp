package io.github.lachin12.oms.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = "oms.simulation.enabled=false")
@ContextConfiguration(classes = SimulationDisabledControllerTest.ApiTestConfiguration.class)
class SimulationDisabledControllerTest {
    @Configuration(proxyBeanMethods = false)
    @Import({SimulationController.class, ApiExceptionHandler.class})
    static class ApiTestConfiguration { }

    @Autowired private MockMvc mvc;
    @Autowired private ApplicationContext context;

    @Test
    void explicitlyDisabledSimulationHasNoControllerOrRoute() throws Exception {
        assertThat(context.getBeansOfType(SimulationController.class)).isEmpty();
        mvc.perform(post("/api/v1/simulation/orders/00000000-0000-0000-0000-000000000001/steps")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
