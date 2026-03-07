package com.loggenengine.core;

import java.util.List;
import java.util.Random;

/**
 * Deterministic random number generator seeded from simulation config.
 * All methods advance the same internal {@link Random} state in a fixed order,
 * guaranteeing that the same seed always produces the same sequence.
 */
public class DeterministicRandom {

    private final Random random;

    public DeterministicRandom(long seed) {
        this.random = new Random(seed);
    }

    /** Uniform [0, 1) */
    public double nextDouble() {
        return random.nextDouble();
    }

    /** Uniform int in [0, bound) */
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    /** True with given probability */
    public boolean nextBoolean(double probability) {
        return random.nextDouble() < probability;
    }

    /**
     * Poisson-distributed sample using Knuth's algorithm.
     * Deterministic: always consumes exactly k+1 doubles from the RNG stream.
     * Caps at 1000 to avoid pathological loops for very large lambda.
     */
    public int poissonSample(double lambda) {
        if (lambda <= 0) return 0;
        // For large lambda use Gaussian approximation to avoid long loops
        if (lambda > 30) {
            double sample = lambda + Math.sqrt(lambda) * random.nextGaussian();
            return Math.max(0, (int) Math.round(sample));
        }
        double L = Math.exp(-lambda);
        int k = 0;
        double p = 1.0;
        do {
            k++;
            p *= random.nextDouble();
        } while (p > L && k < 1000);
        return k - 1;
    }

    /**
     * Gaussian sample clipped to [mean - 3σ, ∞), always positive.
     */
    public double nextGaussianPositive(double mean, double stddev) {
        return Math.max(1.0, mean + stddev * random.nextGaussian());
    }

    /**
     * Hex string of exactly {@code length} lowercase hex characters.
     */
    public String nextHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            sb.append(Integer.toHexString(random.nextInt(16)));
        }
        return sb.toString();
    }

    /** Pick a random element from a non-empty list. */
    public <T> T nextElement(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }

    /** Integer in [min, max] inclusive. */
    public int nextIntBetween(int min, int max) {
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
    }

    /** Long in [min, max] inclusive. */
    public long nextLongBetween(long min, long max) {
        if (min >= max) return min;
        return min + (long) (random.nextDouble() * (max - min + 1));
    }
}
