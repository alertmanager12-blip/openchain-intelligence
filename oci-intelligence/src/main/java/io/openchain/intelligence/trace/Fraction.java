package io.openchain.intelligence.trace;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/** Exact non-negative rational number (raw token units): no rounding until it is displayed. */
public record Fraction(BigInteger num, BigInteger den) implements Comparable<Fraction> {
    public static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
    public static final Fraction ONE = new Fraction(BigInteger.ONE, BigInteger.ONE);

    public Fraction {
        if (den.signum() <= 0) throw new IllegalArgumentException("denominator must be positive");
        BigInteger g = num.gcd(den);
        if (g.signum() > 0 && !g.equals(BigInteger.ONE)) { num = num.divide(g); den = den.divide(g); }
        if (num.signum() == 0) den = BigInteger.ONE;
    }

    public static Fraction of(BigInteger v) { return new Fraction(v, BigInteger.ONE); }
    public static Fraction of(long v) { return of(BigInteger.valueOf(v)); }

    public Fraction plus(Fraction o) { return new Fraction(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den)); }
    public Fraction minus(Fraction o) { return new Fraction(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den)); }
    public Fraction times(Fraction o) { return new Fraction(num.multiply(o.num), den.multiply(o.den)); }
    public Fraction dividedBy(Fraction o) {
        if (o.num.signum() == 0) throw new ArithmeticException("division by zero");
        BigInteger n = num.multiply(o.den), d = den.multiply(o.num);
        return d.signum() < 0 ? new Fraction(n.negate(), d.negate()) : new Fraction(n, d);
    }
    public Fraction min(Fraction o) { return compareTo(o) <= 0 ? this : o; }
    public boolean isZero() { return num.signum() == 0; }
    public int signum() { return num.signum(); }

    @Override public int compareTo(Fraction o) { return num.multiply(o.den).compareTo(o.num.multiply(den)); }

    /** Human amount of a raw amount in a token with {@code decimals}, for display only. */
    public BigDecimal toDecimal(int decimals, int scale) {
        return new BigDecimal(num).divide(new BigDecimal(den), new MathContext(64)).movePointLeft(decimals).setScale(scale, RoundingMode.HALF_EVEN);
    }

    @Override public String toString() { return den.equals(BigInteger.ONE) ? num.toString() : num + "/" + den; }
}
