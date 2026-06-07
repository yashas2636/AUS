package com.bentley.fibonacci;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@WebMvcTest(FibonacciController.class)
@Import({FibonacciService.class, CacheConfig.class, RateLimitInterceptor.class,
         WebConfig.class, FibonacciMetrics.class, TestMetricsConfig.class})
@TestPropertySource(properties = "rate.limit.requests-per-minute=100000")
class FibonacciLoadTest {

    private static final int TOTAL = 100;
    private static final int THREADS = 10;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void concurrentRequests_allReturn200() throws InterruptedException {
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(TOTAL);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < TOTAL; i++) {
            final int n = i % 50;
            executor.submit(() -> {
                try {
                    MvcResult result = mockMvc.perform(
                            get("/api/v1/fibonacci").param("n", String.valueOf(n)))
                            .andReturn();
                    if (result.getResponse().getStatus() == 200) {
                        success.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "All requests must complete within 30s");
        executor.shutdown();
        assertEquals(0, failed.get(), "No requests should fail");
        assertEquals(TOTAL, success.get(), "All requests should return 200");
    }

    @Test
    void badInputUnderLoad_noServerErrors() throws InterruptedException {
        AtomicInteger serverErrors = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(TOTAL);
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        String[] badInputs = {"-1", "abc", "3.14", "100001"};

        for (int i = 0; i < TOTAL; i++) {
            final String param = badInputs[i % badInputs.length];
            executor.submit(() -> {
                try {
                    MvcResult result = mockMvc.perform(
                            get("/api/v1/fibonacci").param("n", param))
                            .andReturn();
                    if (result.getResponse().getStatus() >= 500) {
                        serverErrors.incrementAndGet();
                    }
                } catch (Exception e) {
                    serverErrors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        assertEquals(0, serverErrors.get(), "Bad inputs must never produce 5xx");
    }
}
