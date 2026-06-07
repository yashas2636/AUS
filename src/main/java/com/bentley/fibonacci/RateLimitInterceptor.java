package com.bentley.fibonacci;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter keyed by client IP.
 * In production, back this with Redis for multi-replica consistency.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    private final FibonacciMetrics metrics;

    public RateLimitInterceptor(FibonacciMetrics metrics) {
        this.metrics = metrics;
    }

    @Value("${rate.limit.requests-per-minute:200}")
    private int maxRequestsPerMinute;

    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String ip = resolveClientIp(request);
        long now = System.currentTimeMillis();

        Deque<Long> timestamps = windows.computeIfAbsent(ip, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MS) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequestsPerMinute) {
                log.warn("Rate limit exceeded ip={} requests_in_window={}", ip, timestamps.size());
                metrics.recordRateLimited();
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":{\"code\":\"RATE_LIMIT_EXCEEDED\"," +
                        "\"message\":\"Too many requests. Max " + maxRequestsPerMinute +
                        " requests per minute per IP.\"}}"
                );
                return false;
            }
            timestamps.addLast(now);
        }
        return true;
    }

    private String resolveClientIp(HttpServletRequest request) {
        // Honour X-Forwarded-For set by the ingress / load balancer
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
