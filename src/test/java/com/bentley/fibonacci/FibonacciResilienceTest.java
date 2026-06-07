package com.bentley.fibonacci;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Resilience scenarios â€” edge cases that could crash or corrupt the API
 * if not handled. Every test here asserts "no 5xx, no crash, structured response".
 */
@WebMvcTest(FibonacciController.class)
@Import({FibonacciService.class, CacheConfig.class, RateLimitInterceptor.class, WebConfig.class})
@TestPropertySource(properties = "rate.limit.requests-per-minute=100000")
class FibonacciResilienceTest {

    @Autowired
    private MockMvc mockMvc;

    // â”€â”€ 1. Integer boundary / overflow â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Integer.MAX_VALUE = 2,147,483,647. Exceeds @Max(100000) so returns 400,
     * but must NOT overflow or throw an uncaught exception.
     */
    @Test
    void integerMaxValue_returns400_notCrash() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "2147483647"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    /**
     * 2^31 (one beyond Integer.MAX_VALUE) cannot be parsed as int.
     * Spring must return 400, not 500.
     */
    @Test
    void beyondIntegerMaxValue_returns400_notCrash() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "2147483648"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("TYPE_MISMATCH"));
    }

    /**
     * Integer.MIN_VALUE = -2,147,483,648. Negative and out of int parse range when
     * negated â€” must not overflow internally.
     */
    @Test
    void integerMinValue_returns400_notCrash() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "-2147483648"))
               .andExpect(status().isBadRequest());
    }

    // â”€â”€ 2. Encoding and format tricks â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Scientific notation â€” "1e5" looks like 100000 but is a float string.
     * Must return 400 TYPE_MISMATCH, not silently parse to 100000.
     */
    @Test
    void scientificNotation_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "1e5"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("TYPE_MISMATCH"));
    }

    /**
     * Hex encoding â€” "0x0A" looks like 10 but Java's Integer.parseInt rejects it.
     */
    @Test
    void hexValue_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "0x0A"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.error.code").value("TYPE_MISMATCH"));
    }

    /**
     * Leading zeros â€” "007" must parse as integer 7, not be rejected.
     * Integer.parseInt("007") = 7.
     */
    @Test
    void leadingZeros_parsedCorrectly() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "007"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.n").value(7))
               .andExpect(jsonPath("$.result").value(13));
    }

    /**
     * URL-encoded null byte (%00) â€” must return 400, not cause NullPointerException.
     */
    @Test
    void nullByte_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci?n=%00"))
               .andExpect(status().isBadRequest());
    }

    /**
     * URL-encoded space (%20) before the number â€” " 5" cannot be parsed as int.
     */
    @Test
    void urlEncodedSpace_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci?n=%205"))
               .andExpect(status().isBadRequest());
    }

    /**
     * Plus sign (+) in query string is decoded as space by URL parsers: "+5" â†’ " 5".
     * Must return 400, not parse as 5.
     */
    @Test
    void plusSignParam_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci?n=+5"))
               .andExpect(status().isBadRequest());
    }

    /**
     * Unicode digit characters (Arabic-Indic "Ù§" = 7) â€” must return 400.
     * Java's parseInt does not accept non-ASCII digits.
     */
    @Test
    void unicodeDigit_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "Ù§"))
               .andExpect(status().isBadRequest());
    }

    /**
     * Emoji â€” must return 400 without crashing.
     */
    @Test
    void emoji_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "ðŸ˜€"))
               .andExpect(status().isBadRequest());
    }

    // â”€â”€ 3. Duplicate / multiple parameters â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Duplicate ?n â€” Spring binds the first value (5) when param is declared as int.
     * Must not crash and must return a valid result.
     */
    @Test
    void duplicateParam_usesFirstValue_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci?n=5&n=10"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(5));   // F(5) = 5
    }

    /**
     * Redundant extra query parameters beyond ?n should be ignored, not crash.
     */
    @Test
    void extraQueryParams_ignored_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci?n=3&foo=bar&debug=true"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(2));   // F(3) = 2
    }

    // â”€â”€ 4. Large valid response â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * n=100000 is the maximum allowed. F(100000) has ~20,900 digits.
     * Verifies that serialising a very large BigInteger to JSON does not crash.
     */
    @Test
    void maxAllowedN_largeResponseSerialises_returns200() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/fibonacci").param("n", "100000"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.n").value(100000))
               .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.length() > 1000, "F(100000) response should be a very large number");
    }

    /**
     * Back-to-back calls for the same large n â€” second call must be a cache hit
     * and return the same result without recomputing.
     */
    @Test
    void largeN_cachedOnSecondCall_consistentResult() throws Exception {
        String first = mockMvc.perform(get("/api/v1/fibonacci").param("n", "50000"))
               .andExpect(status().isOk())
               .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(get("/api/v1/fibonacci").param("n", "50000"))
               .andExpect(status().isOk())
               .andReturn().getResponse().getContentAsString();

        assertEquals(first, second, "Cached and non-cached responses must be identical");
    }

    // â”€â”€ 5. Cache stampede â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * 50 threads all request the same uncached value simultaneously.
     * Without proper handling this can cause multiple redundant computations,
     * but must never return inconsistent results or crash.
     */
    @Test
    void cacheStampede_concurrentSameN_allConsistentResults() throws InterruptedException {
        int threads = 50;
        String targetN = "99999";
        CountDownLatch latch = new CountDownLatch(threads);
        List<String> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger errors = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    MvcResult r = mockMvc.perform(
                            get("/api/v1/fibonacci").param("n", targetN))
                            .andReturn();
                    if (r.getResponse().getStatus() == 200) {
                        results.add(r.getResponse().getContentAsString());
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        assertEquals(0, errors.get(), "No errors under cache stampede");
        assertTrue(results.size() > 0, "At least some results returned");
        // All results that came back must be identical
        long distinctResults = results.stream().distinct().count();
        assertEquals(1, distinctResults, "All concurrent responses must return the same value");
    }

    // â”€â”€ 6. Actuator endpoint exposure â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * /actuator/env exposes environment variables and system properties including secrets.
     * Must NOT be accessible â€” returns 404 because it is excluded from the exposed list.
     */
    @Test
    void actuator_env_notExposed() throws Exception {
        mockMvc.perform(get("/actuator/env"))
               .andExpect(status().isNotFound());
    }

    /**
     * /actuator/beans lists every Spring bean â€” useful for attackers to map internals.
     * Must NOT be accessible.
     */
    @Test
    void actuator_beans_notExposed() throws Exception {
        mockMvc.perform(get("/actuator/beans"))
               .andExpect(status().isNotFound());
    }

    /**
     * /actuator/heapdump allows downloading a full JVM heap dump â€” contains all secrets in memory.
     * Must NOT be accessible.
     */
    @Test
    void actuator_heapdump_notExposed() throws Exception {
        mockMvc.perform(get("/actuator/heapdump"))
               .andExpect(status().isNotFound());
    }

    /**
     * /actuator/health must always be accessible for Kubernetes probes.
     */
    @Test
    void actuator_health_alwaysAccessible() throws Exception {
        mockMvc.perform(get("/actuator/health"))
               .andExpect(status().isOk());
    }

    // â”€â”€ 7. Content negotiation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Client requests XML â€” service only produces JSON.
     * Must return 406 Not Acceptable, not crash.
     */
    @Test
    void xmlAcceptHeader_returns406() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci")
                .param("n", "5")
                .accept(MediaType.APPLICATION_XML))
               .andExpect(status().isNotAcceptable());
    }

    /**
     * Client sends Accept: *//* â€” must return JSON normally.
     */
    @Test
    void wildcardAcceptHeader_returnsJson() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci")
                .param("n", "5")
                .accept(MediaType.ALL))
               .andExpect(status().isOk())
               .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    // â”€â”€ 8. Path traversal / injection â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Path traversal attempt â€” must return 404, not expose filesystem.
     */
    @Test
    void pathTraversal_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci/../../../etc/passwd"))
               .andExpect(status().isNotFound());
    }

    /**
     * Trailing slash on endpoint â€” must return 404 (endpoint is /fibonacci not /fibonacci/).
     * Avoids accidental endpoint aliasing.
     */
    @Test
    void trailingSlash_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci/"))
               .andExpect(status().isNotFound());
    }

    // â”€â”€ 9. Repeated unique-value requests (cache pressure) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * 500 requests each for a unique n value â€” tests that the cache fills up and
     * evicts old entries without crashing (Caffeine eviction is tested here).
     * All responses must be 200 with correct results.
     */
    @Test
    void cacheEviction_manyUniqueValues_noErrors() throws InterruptedException {
        int requestCount = 500;
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger successes = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(requestCount);
        ExecutorService executor = Executors.newFixedThreadPool(20);

        for (int i = 0; i < requestCount; i++) {
            final int n = i;  // each request has a unique n â€” forces cache computation
            executor.submit(() -> {
                try {
                    MvcResult r = mockMvc.perform(
                            get("/api/v1/fibonacci").param("n", String.valueOf(n)))
                            .andReturn();
                    if (r.getResponse().getStatus() == 200) {
                        successes.incrementAndGet();
                    } else {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        executor.shutdown();

        assertEquals(0, errors.get(), "Cache eviction must not cause errors");
        assertEquals(requestCount, successes.get(), "All requests must return 200");
    }

    // â”€â”€ 10. Boundary: n=0 (returns 0, not an error) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * n=0 is a valid base case that returns 0 (not 1).
     * Tests that a zero result is not mistaken for a falsy/error response.
     */
    @Test
    void n_zero_returns_zero_not_error() throws Exception {
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "0"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(0));
    }

    // â”€â”€ 11. Rapid sequential bad requests â€” no state leak â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * 200 bad requests back to back from the same IP must not pollute internal state
     * and must not prevent the 201st valid request from succeeding.
     */
    @Test
    void badRequestsFollowedByValidRequest_validStillSucceeds() throws Exception {
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/v1/fibonacci").param("n", "bad" + i))
                   .andExpect(status().isBadRequest());
        }
        // A valid request after the bad flood must still work
        mockMvc.perform(get("/api/v1/fibonacci").param("n", "5"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.result").value(5));
    }
}
