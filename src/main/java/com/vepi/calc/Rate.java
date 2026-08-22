package com.vepi.calc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Exact rational rate (numerator / denominator) used for all per-hour throughput
 * calculations, so that results never suffer floating-point drift such as
 * {@code 59.999999999}.
 *
 * <p>{@code cyclesPerHour = 3600 / cycleTimeSeconds} is represented exactly here.
 * For real PI cycle times (1800s and 3600s) this is an integer (2 and 1), but the
 * model stays exact for any cycle time.
 */
public final class Rate implements Comparable<Rate> {
    private final long num;
    private final long den;   // always positive

    private Rate(long num, long den) {
        if (den == 0) throw new ArithmeticException("Zero denominator in Rate");
        if (den < 0) { num = -num; den = -den; }
        long g = gcd(Math.abs(num), den);
        this.num = num / g;
        this.den = den / g;
    }

    /** Create an exact rate num/den, reduced to lowest terms. */
    public static Rate of(long num, long den) { return new Rate(num, den); }

    /** Create an integer rate. */
    public static Rate of(long value) { return new Rate(value, 1); }

    public long numerator() { return num; }
    public long denominator() { return den; }

    public boolean isInteger() { return den == 1; }

    public Rate multiply(long factor) { return new Rate(num * factor, den); }

    /** Exact product of two rates (num*o.num / (den*o.den), reduced). */
    public Rate multiply(Rate other) { return new Rate(num * other.num, den * other.den); }

    public Rate add(Rate other) {
        return new Rate(num * other.den + other.num * den, den * other.den);
    }

    /** Convert to BigDecimal with enough scale for commodity volumes. */
    public BigDecimal toBigDecimal() {
        return BigDecimal.valueOf(num).divide(BigDecimal.valueOf(den), 10, RoundingMode.HALF_UP);
    }

    /** Human-friendly: integer when exact, otherwise a plain decimal. */
    public String toDisplayString() {
        if (den == 1) return Long.toString(num);
        return toBigDecimal().stripTrailingZeros().toPlainString();
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    @Override
    public int compareTo(Rate o) {
        return BigInteger.valueOf(num).multiply(BigInteger.valueOf(o.den))
                .compareTo(BigInteger.valueOf(o.num).multiply(BigInteger.valueOf(den)));
    }

    @Override
    public String toString() { return num + "/" + den; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Rate r)) return false;
        return num == r.num && den == r.den;
    }

    @Override
    public int hashCode() { return Long.hashCode(num * 31 + den); }
}
