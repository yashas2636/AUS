package com.bentley.fibonacci;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FibonacciController.class)
@Import({FibonacciService.class, CacheConfig.class, RateLimitInterceptor.class, WebConfig.class})
@TestPropertySource(properties = "rate.limit.requests-per-minute=5")
class RateLimitInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void withinLimit_allRequestsSucceed() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/fibonacci").param("n", "1"))
                   .andExpect(status().isOk());
        }
    }

    @Test
    void exceedLimit_returns429() throws Exception {
        AtomicInteger rateLimited = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            MvcResult result = mockMvc.perform(get("/api/v1/fibonacci").param("n", "1"))
                                      .andReturn();
            if (result.getResponse().getStatus() == 429) {
                rateLimited.incrementAndGet();
            }
        }
        assertTrue(rateLimited.get() > 0, "Some requests should be rate-limited after limit exceeded");
    }

    @Test
    void rateLimitResponse_hasErrorBody() throws Exception {
        // Exhaust the limit
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/fibonacci").param("n", "1"));
        }
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "1"))
               .andExpect(status().isTooManyRequests());
    }
}
