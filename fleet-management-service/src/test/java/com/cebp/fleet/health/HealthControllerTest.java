package com.cebp.fleet.health;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cebp.fleet.config.ClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer test (no database). MockMvc ignores the /api/v1 context path,
 * so paths here start at /health.
 */
@WebMvcTest(HealthController.class)
@Import(ClockConfig.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthReportsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void correlationIdFromRequestIsEchoed() throws Exception {
        mockMvc.perform(get("/health").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
    }

    @Test
    void correlationIdIsGeneratedWhenMissing() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(header().string("X-Correlation-Id", startsWith("corr_")));
    }

    @Test
    void unknownUrlUsesTheStandardErrorFormat() throws Exception {
        mockMvc.perform(get("/no-such-endpoint"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
