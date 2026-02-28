package com.trading.drg.fn.finance;

/**
 * Rolling Maximum over a window.
 * <p>
 * Formula: {@code y[t] = max(x[t-N+1], ..., x[t])}
 */
public class RollingMax extends AbstractFn1 {
    private final double[] window;
    private final int size;
    private int head = 0;
    private int count = 0;

    public RollingMax(int size) {
        if (size < 1)
            throw new IllegalArgumentException("Size must be >= 1");
        this.size = size;
        this.window = new double[size];
    }

    @Override
    protected double calculate(double input) {
        window[head] = input;
        head++;
        if (head >= size) {
            head = 0;
        }

        if (count < size) {
            count++;
        }

        double max = -Double.MAX_VALUE;

        // Re-implementing existing logic but safely inside try-catch
        for (int i = 0; i < count; i++) {
            if (window[i] > max) {
                max = window[i];
            }
        }
        return max;
    }
}
