package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn2;

/**
 * Rolling Beta of Y with respect to X.
 * 
 * Beta = Cov(X, Y) / Var(Y)
 * Where X is the Benchmark, Y is the Asset.
 * apply(x, y) -> x is benchmark.
 */
public class Beta implements Fn2 {
    private final double[] bufferX;
    private final double[] bufferY;
    private final int size;
    private int head = 0;
    private int count = 0;

    private double sumX = 0;
    private double sumY = 0;
    private double sumXY = 0;
    private double sumY2 = 0;

    public Beta(int size) {
        if (size < 2)
            throw new IllegalArgumentException("Size must be >= 2");
        this.size = size;
        this.bufferX = new double[size];
        this.bufferY = new double[size];
    }

    @Override
    public double apply(double x, double y) {
        if (Double.isNaN(x) || Double.isNaN(y))
            return Double.NaN;

        // Beta = Cov(x, y) / Var(y) (Beta of x relative to y, usually y is market)

        // Remove old
        if (count >= size) {
            double oldX = bufferX[head];
            double oldY = bufferY[head];
            sumX -= oldX;
            sumY -= oldY;
            sumXY -= oldX * oldY;
            sumY2 -= oldY * oldY;
        } else {
            count++;
        }

        // Add new
        bufferX[head] = x;
        bufferY[head] = y;
        sumX += x;
        sumY += y;
        sumXY += x * y;
        sumY2 += y * y;

        head++;
        if (head >= size)
            head = 0;

        if (count < 2)
            return 0.0;

        double meanX = sumX / count;
        double meanY = sumY / count;

        double covXY = (sumXY / count) - (meanX * meanY);
        double varY = (sumY2 / count) - (meanY * meanY);

        if (varY <= 1e-9)
            return 0.0; // Avoid division by zero

        return covXY / varY;
    }
}
