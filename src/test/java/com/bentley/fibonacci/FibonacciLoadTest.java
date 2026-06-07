package com.bentley.fibonacci;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Simulates 1000 back-to-back concurrent requests.
 * Rate limiter is disabled (limit=100000) so we measure pure throughput.
 */
@Tag("load")
@WebMvcTest(FibonacciController.class)
@Import({FibonacciService.class, CacheConfig.class, RateLimitInterceptor.class, WebConfig.class, FibonacciMetrics.class, TestMetricsConfig.class})
@TestPropertySource(properties = "rate.limit.requests-per-minute=100000")
class FibonacciLoadTest {

    private static final int TOTAL_REQUESTS = 1000;
    private static final int THREAD_COUNT = 50;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void load_1000ConcurrentRequests_allReturn200() throws InterruptedException {
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(TOTAL_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            final int n = i % 50;  // varied inputs to exercise caching and computation
            futures.add(executor.submit(() -> {
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
            }));
        }

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.printf("[LoadTest] %d requests: %d succeeded, %d failed%n",
                TOTAL_REQUESTS, success.get(), failed.get());

        assertTrue(completed, "All requests should complete within 60s");
        assertEquals(0, failed.get(), "No requests should fail under load");
        assertEquals(TOTAL_REQUESTS, success.get(), "All 1000 requests should return 200");
    }

    @Test
    void load_badData_1000Requests_allReturn400() throws InterruptedException {
        AtomicInteger correct400 = new AtomicInteger(0);
        AtomicInteger unexpected = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(TOTAL_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        String[] badInputs = {"-1", "abc", "", "9.9", "true", "null", "100001", "<script>",
                              "'; DROP TABLE", "2147483648", "-2147483649", "NaN", "Infinity"};

        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            final String badN = badInputs[i % badInputs.length];
            executor.submit(() -> {
                try {
                    MvcResult result = mockMvc.perform(
                            get("/api/v1/fibonacci").param("n", badN))
                            .andReturn();
                    int status = result.getResponse().getStatus();
                    if (status == 400 || status == 404) {
                        correct400.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.printf("[LoadTest-BadData] %d bad requests: %d correct 4xx, %d unexpected%n",
                TOTAL_REQUESTS, correct400.get(), unexpected.get());

        assertTrue(completed, "All bad-data requests should complete within 60s");
        assertEquals(0, unexpected.get(), "Bad inputs must never return 2xx or 5xx");
    }

    @Test
    void load_mixedRequests_noInternalErrors() throws InterruptedException {
        AtomicInteger serverErrors = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(TOTAL_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        // Mix of valid, boundary, and invalid inputs
        Object[] inputs = {0, 1, 10, 100, 1000, 100000, -1, "abc", "3.14", 100001};

        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            final String param = String.valueOf(inputs[i % inputs.length]);
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

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(0, serverErrors.get(), "No 5xx errors should occur under any input");
    }
}
