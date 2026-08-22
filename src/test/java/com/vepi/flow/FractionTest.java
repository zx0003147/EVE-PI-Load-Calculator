package com.vepi.flow;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-rational arithmetic tests: the flow solver must never make a quantity
 * decision on a double, so reduction, sign handling and comparison exactness
 * are contract, not implementation detail.
 */
class FractionTest {

    @Test
    void reducesToLowestTerms() {
        Fraction f = Fraction.of(48, 60);
        assertEquals(4, f.numerator().intValueExact());
        assertEquals(5, f.denominator().intValueExact());
        assertEquals("4/5", f.toString());
    }

    @Test
    void negativeDenominatorNormalized() {
        Fraction f = Fraction.of(-3, -9);
        assertEquals("1/3", f.toString());
        assertEquals(Fraction.of(1, 3), f);
    }

    @Test
    void zeroReducesToCanonicalZero() {
        assertEquals("0", Fraction.of(0, 12345).toString());
        assertTrue(Fraction.of(0, 7).isZero());
    }

    @Test
    void arithmeticIsExact() {
        // 1/3 + 1/6 = 1/2, exactly.
        assertEquals(Fraction.of(1, 2), Fraction.of(1, 3).add(Fraction.of(1, 6)));
        // 4/5 - 7/10 = 1/10.
        assertEquals(Fraction.of(1, 10), Fraction.of(4, 5).subtract(Fraction.of(7, 10)));
        // (2/3) * (3/4) = 1/2.
        assertEquals(Fraction.of(1, 2), Fraction.of(2, 3).multiply(Fraction.of(3, 4)));
        // (1/7) / (1/14) = 2.
        assertEquals(Fraction.of(2, 1), Fraction.of(1, 7).divide(Fraction.of(1, 14)));
    }

    @Test
    void comparisonIsCrossMultiplierExact() {
        assertTrue(Fraction.of(48, 60).compareTo(Fraction.of(4, 5)) == 0);
        // 5/9 (=35/63) < 4/7 (=36/63): cross-multiplication, no double rounding.
        assertTrue(Fraction.of(5, 9).compareTo(Fraction.of(4, 7)) < 0);
        assertTrue(Fraction.of(4, 7).compareTo(Fraction.of(5, 9)) > 0);
    }

    @Test
    void isOneAndSignum() {
        assertTrue(Fraction.ONE.isOne());
        assertTrue(Fraction.of(9, 9).isOne());
        assertTrue(Fraction.of(-2, 7).signum() < 0);
    }

    @Test
    void zeroDenominatorRejected() {
        assertThrows(ArithmeticException.class, () -> Fraction.of(1, 0));
    }

    @Test
    void divisionByZeroFractionRejected() {
        assertThrows(ArithmeticException.class, () -> Fraction.ONE.divide(Fraction.ZERO));
    }

    @Test
    void percentIsDisplayOnly() {
        assertEquals("80%", Fraction.of(4, 5).toPercent());
        assertEquals("66.7%", Fraction.of(2, 3).toPercent());
        assertEquals("100%", Fraction.ONE.toPercent());
        // Display decimals round; the decision values stay exact.
        assertEquals(new BigDecimal("0.800"), Fraction.of(4, 5).toBigDecimal(3));
    }
}
