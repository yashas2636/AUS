package com.bentley.fibonacci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class FibonacciServiceTest {

    private final FibonacciService service = new FibonacciService();

    @Test
    void baseCase_zero() {
        assertEquals(BigInteger.ZERO, service.compute(0));
    }

    @Test
    void baseCase_one() {
        assertEquals(BigInteger.ONE, service.compute(1));
    }

    // Spec examples from the brief
    @Test
    void specExample_n2_returns1() {
        assertEquals(BigInteger.ONE, service.compute(2));
    }

    @Test
    void specExample_n10_returns55() {
        assertEquals(BigInteger.valueOf(55), service.compute(10));
    }

    @ParameterizedTest(name = "F({0}) = {1}")
    @CsvSource({
        "0,  0",
        "1,  1",
        "2,  1",
        "3,  2",
        "5,  5",
        "10, 55",
        "20, 6765",
        "30, 832040"
    })
    void knownValues(int n, long expected) {
        assertEquals(BigInteger.valueOf(expected), service.compute(n));
    }

    @Test
    void largeValue_n50_noBigIntegerOverflow() {
        assertEquals(new BigInteger("12586269025"), service.compute(50));
    }

    @Test
    void largeValue_n100() {
        // Verifies that BigInteger correctly handles values beyond long range
        BigInteger result = service.compute(100);
        assertTrue(result.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0,
                "F(100) should exceed Long.MAX_VALUE");
    }

    @Test
    void negative_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> service.compute(-1));
    }

    @Test
    void negative_large_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> service.compute(Integer.MIN_VALUE));
    }
}
