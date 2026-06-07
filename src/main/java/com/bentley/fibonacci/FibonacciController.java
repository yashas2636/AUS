package com.bentley.fibonacci;

import io.micrometer.core.instrument.Timer;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Validated
public class FibonacciController {

    private static final Logger log = LoggerFactory.getLogger(FibonacciController.class);

    private final FibonacciService fibonacciService;
    private final FibonacciMetrics metrics;

    public FibonacciController(FibonacciService fibonacciService, FibonacciMetrics metrics) {
        this.fibonacciService = fibonacciService;
        this.metrics = metrics;
    }

    @GetMapping("/fibonacci")
    public ResponseEntity<Map<String, Object>> fibonacci(
            @RequestParam
            @Min(value = 0, message = "n must be >= 0")
            @Max(value = 100_000, message = "n must be <= 100000 to prevent excessive computation")
            int n) {

        log.info("Fibonacci request n={}", n);

        Timer.Sample sample = metrics.startComputationTimer();
        BigInteger result = fibonacciService.compute(n);
        metrics.stopComputationTimer(sample);

        metrics.recordRequest(n);
        log.info("Fibonacci response n={} result_digits={}", n, result.toString().length());

        return ResponseEntity.ok(Map.of("n", n, "result", result));
    }
}
