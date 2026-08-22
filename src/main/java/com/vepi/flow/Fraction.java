package com.vepi.flow;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Exact rational number (numerator/denominator, BigInteger, always reduced,
 * denominator always positive). The flow solver uses it for facility
 * utilization and rate arithmetic so that <b>no quantity decision is ever made
 * on a double</b> — utilization ratios like 48/60 stay exactly 4/5 until they
 * are turned into integer per-block quantities.
 */
public final class Fraction implements Comparable<Fraction> {

    public static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
    public static final Fraction ONE = new Fraction(BigInteger.ONE, BigInteger.ONE);

    private final BigInteger num;
    private final BigInteger den;

    private Fraction(BigInteger num, BigInteger den) {
        this.num = num;
        this.den = den;
    }

    public static Fraction of(long numerator, long denominator) {
        return of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public static Fraction of(BigInteger numerator, BigInteger denominator) {
        Objects.requireNonNull(numerator, "numerator");
        Objects.requireNonNull(denominator, "denominator");
        if (denominator.signum() == 0) {
            throw new ArithmeticException("fraction with zero denominator");
        }
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger g = numerator.gcd(denominator);
        if (g.signum() == 0) {
            return ZERO;
        }
        return new Fraction(numerator.divide(g), denominator.divide(g));
    }

    public BigInteger numerator() {
        return num;
    }

    public BigInteger denominator() {
        return den;
    }

    public Fraction add(Fraction other) {
        return of(num.multiply(other.den).add(other.num.multiply(den)),
                den.multiply(other.den));
    }

    public Fraction subtract(Fraction other) {
        return of(num.multiply(other.den).subtract(other.num.multiply(den)),
                den.multiply(other.den));
    }

    public Fraction multiply(Fraction other) {
        return of(num.multiply(other.num), den.multiply(other.den));
    }

    public Fraction divide(Fraction other) {
        if (other.num.signum() == 0) {
            throw new ArithmeticException("division by zero fraction");
        }
        return of(num.multiply(other.den), den.multiply(other.num));
    }

    public int signum() {
        return num.signum();
    }

    public boolean isZero() {
        return num.signum() == 0;
    }

    public boolean isOne() {
        return num.equals(den);
    }

    @Override
    public int compareTo(Fraction other) {
        return num.multiply(other.den).compareTo(other.num.multiply(den));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Fraction f && num.equals(f.num) && den.equals(f.den);
    }

    @Override
    public int hashCode() {
        return Objects.hash(num, den);
    }

    /** Decimal approximation for display only — never used for decisions. */
    public BigDecimal toBigDecimal(int scale) {
        return new BigDecimal(num).divide(new BigDecimal(den), scale, RoundingMode.HALF_UP);
    }

    /** Percentage string like "80%", "66.7%" — display only. */
    public String toPercent() {
        BigDecimal percent = toBigDecimal(3).multiply(BigDecimal.valueOf(100));
        percent = percent.stripTrailingZeros();
        if (percent.scale() < 0) {
            percent = percent.setScale(0, RoundingMode.UNNECESSARY);
        }
        return percent.toPlainString() + "%";
    }

    @Override
    public String toString() {
        return den.equals(BigInteger.ONE) ? num.toString() : num + "/" + den;
    }
}
