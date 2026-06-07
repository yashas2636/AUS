package com.bentley.fibonacci;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FibonacciController.class)
@Import({FibonacciService.class, CacheConfig.class, RateLimitInterceptor.class, WebConfig.class})
@TestPropertySource(properties = "rate.limit.requests-per-minute=100000")
class FibonacciControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // â”€â”€ Happy path â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void fibonacci_n0_returns0() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "0"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.n").value(0))
               .andExpect(jsonPath("$.result").value(0));
    }

    @Test
    void fibonacci_n2_returns1() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "2"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(1));
    }

    @Test
    void fibonacci_n10_returns55() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "10"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(55));
    }

    @Test
    void fibonacci_maxAllowed_n100000_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "100000"))
               .andExpect(status().isOk());
    }

    // â”€â”€ Bad data: wrong types â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void fibonacci_floatParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "3.14"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("TYPE_MISMATCH"));
    }

    @Test
    void fibonacci_stringParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "abc"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("TYPE_MISMATCH"));
    }

    @Test
    void fibonacci_emptyParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", ""))
               .andExpect(status().isBadRequest());
    }

    @Test
    void fibonacci_booleanParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "true"))
               .andExpect(status().isBadRequest());
    }

    // â”€â”€ Bad data: out of range â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void fibonacci_negativeN_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "-1"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
               .andExpect(jsonPath("$.error.message").exists());
    }

    @Test
    void fibonacci_exceedsMax_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "100001"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void fibonacci_integerMinValue_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "-2147483648"))
               .andExpect(status().isBadRequest());
    }

    // â”€â”€ Missing / malformed request â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void fibonacci_missingParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("MISSING_PARAMETER"));
    }

    @Test
    void fibonacci_sqlInjectionAttempt_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "1; DROP TABLE users"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void fibonacci_xssAttempt_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "<script>alert(1)</script>"))
               .andExpect(status().isBadRequest());
    }

    // â”€â”€ Wrong HTTP method â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void fibonacci_postRequest_returns405() throws Exception {
        mockMvc.perform(post("/api/v1/fibonacci").param("n", "5"))
               .andExpect(status().isMethodNotAllowed())
               .andExpect(jsonPath("$.error.code").value("METHOD_NOT_ALLOWED"));
    }

    // â”€â”€ Unknown endpoint â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void unknownEndpoint_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/doesnotexist"))
               .andExpect(status().isNotFound())
               .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // â”€â”€ Response structure â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Test
    void response_hasExpectedFields() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "5"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.n").exists())
               .andExpect(jsonPath("$.result").exists());
    }

    @Test
    void errorResponse_hasCodeAndMessage() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "-1"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").exists())
               .andExpect(jsonPath("$.error.message").exists());
    }
}
