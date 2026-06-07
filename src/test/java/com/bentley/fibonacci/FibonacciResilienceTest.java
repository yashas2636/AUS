package com.bentley.fibonacci;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FibonacciController.class)
@Import({FibonacciService.class, CacheConfig.class, RateLimitInterceptor.class,
         WebConfig.class, FibonacciMetrics.class, TestMetricsConfig.class})
@TestPropertySource(properties = "rate.limit.requests-per-minute=100000")
class FibonacciResilienceTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void integerMaxValue_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "2147483647"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void beyondIntegerMaxValue_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "2147483648"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("TYPE_MISMATCH"));
    }

    @Test
    void integerMinValue_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "-2147483648"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void scientificNotation_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "1e5"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("TYPE_MISMATCH"));
    }

    @Test
    void hexValue_decodedAs10_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "0x0A"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.n").value(10))
               .andExpect(jsonPath("$.result").value(55));
    }

    @Test
    void leadingZeros_parsedCorrectly() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "007"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.n").value(7))
               .andExpect(jsonPath("$.result").value(13));
    }

    @Test
    void nullByte_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci?n=%00"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void urlEncodedSpace_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci?n=%205"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void plusSignParam_acceptedAsPositiveInt() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci?n=+5"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(5));
    }

    // Java's Character.digit('٧', 10) = 7, so parseInt accepts Arabic-Indic digits
    @Test
    void unicodeDigit_parsedAs7_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "٧"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.n").value(7))
               .andExpect(jsonPath("$.result").value(13));
    }

    @Test
    void emoji_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "😀"))
               .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateParam_usesFirstValue() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci?n=5&n=10"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(5));
    }

    @Test
    void extraQueryParams_ignored() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci?n=3&foo=bar"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(2));
    }

    @Test
    void maxAllowedN_returns200() throws Exception {
        var result = mockMvc.perform(get("/api/v1/fibonacci").param("n", "100000"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.n").value(100000))
               .andReturn();
        assertTrue(result.getResponse().getContentAsString().length() > 1000);
    }

    @Test
    void largeN_cacheConsistency() throws Exception {
        String first = mockMvc.perform(get("/api/v1/fibonacci").param("n", "50000"))
               .andExpect(status().isOk())
               .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/v1/fibonacci").param("n", "50000"))
               .andExpect(status().isOk())
               .andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
    }

    @Test
    void actuator_env_notExposed() throws Exception {
        mockMvc.perform(get("/actuator/env"))
               .andExpect(status().isNotFound());
    }

    @Test
    void actuator_beans_notExposed() throws Exception {
        mockMvc.perform(get("/actuator/beans"))
               .andExpect(status().isNotFound());
    }

    @Test
    void actuator_heapdump_notExposed() throws Exception {
        mockMvc.perform(get("/actuator/heapdump"))
               .andExpect(status().isNotFound());
    }

    @Test
    void xmlAcceptHeader_returns406() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "5")
                .accept(MediaType.APPLICATION_XML))
               .andExpect(status().isNotAcceptable());
    }

    @Test
    void wildcardAcceptHeader_returnsJson() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "5")
                .accept(MediaType.ALL))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void pathTraversal_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci/../../../etc/passwd"))
               .andExpect(status().isNotFound());
    }

    @Test
    void trailingSlash_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci/"))
               .andExpect(status().isNotFound());
    }

    @Test
    void n_zero_returns_zero() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "0"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(0));
    }

    @Test
    void badRequestsFollowedByValidRequest_succeeds() throws Exception {
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/api/v1/fibonacci").param("n", "bad" + i))
                   .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "5"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(5));
    }
}
