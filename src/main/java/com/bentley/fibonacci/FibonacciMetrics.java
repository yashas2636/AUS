package com.bentley.fibonacci;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Custom business metrics exported to Prometheus + New Relic.
 *
 * Automatically available in NR via the Java agent's Micrometer integration:
 *   fibonacci.requests.total      â€” total successful requests
 *   fibonacci.rate_limited.total  â€” requests rejected by rate limiter (abuse indicator)
 *   fibonacci.validation.errors   â€” requests rejected by input validation
 *   fibonacci.n.value             â€” histogram of requested n values (usage pattern)
 *   fibonacci.computation.seconds â€” computation time histogram by n bucket
 *
 * These complement the automatic http.server.requests metric from Spring Actuator.
 */
@Component
public class FibonacciMetrics {

    private final Counter requestsTotal;
    private final Counter rateLimitedTotal;
    private final Counter validationErrorsTotal;
    private final DistributionSummary nValueSummary;
    private final Timer computationTimer;
    private final MeterRegistry registry;

    public FibonacciMetrics(MeterRegistry registry) {
        this.registry = registry;

        requestsTotal = Counter.builder("fibonacci.requests.total")
                .description("Total successful Fibonacci computations")
                .register(registry);

        rateLimitedTotal = Counter.builder("fibonacci.rate_limited.total")
                .description("Requests rejected by the rate limiter â€” high values indicate abuse or misconfigured client")
                .register(registry);

        validationErrorsTotal = Counter.builder("fibonacci.validation.errors")
                .description("Requests rejected by input validation (bad type, out of range)")
                .register(registry);

        // Histogram of n values: shows whether clients are calling with small n
        // (cache-friendly) or large n (computation-heavy)
        nValueSummary = DistributionSummary.builder("fibonacci.n.value")
                .description("Distribution of n values requested")
                .baseUnit("n")
                .register(registry);

        computationTimer = Timer.builder("fibonacci.computation.seconds")
                .description("Time spent computing F(n) â€” excludes cache hits")
                .register(registry);
    }

    public void recordRequest(int n) {
        requestsTotal.increment();
        nValueSummary.record(n);
    }

    public void recordRateLimited() {
        rateLimitedTotal.increment();
    }

    public void recordValidationError() {
        validationErrorsTotal.increment();
    }

    public Timer.Sample startComputationTimer() {
        return Timer.start(registry);
    }

    public void stopComputationTimer(Timer.Sample sample) {
        sample.stop(computationTimer);
    }
}
