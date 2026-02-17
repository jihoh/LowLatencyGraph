package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn2;

/**
 * Rolling Beta of Y with respect to X.
 * 
 * Beta = Cov(X, Y) / Var(X)
 * Where X is the Benchmark, Y is the Asset.
 * apply(x, y) -> x is benchmark.
 */
public class Beta implements Fn2 {
    private final double[] xWindow;
    private final double[] yWindow;
    private final int size;
    private int head = 0;
    private int count = 0;

    private double sumX = 0;
    private double sumY = 0;
    private double sumX2 = 0;
    private double sumXY = 0;

    public Beta(int size) {
        if (size < 2)
            throw new IllegalArgumentException("Size must be >= 2");
        this.size = size;
        this.xWindow = new double[size];
        this.yWindow = new double[size];
    }

    @Override
    public double apply(double x, double y) {
        if (count < size) {
            xWindow[head] = x;
            yWindow[head] = y;
            sumX += x;
            sumY += y;
            sumX2 += x * x;
            sumXY += x * y;
            head = (head + 1) % size;
            count++;
        } else {
            double oldX = xWindow[head];
            double oldY = yWindow[head];

            xWindow[head] = x;
            yWindow[head] = y;

            sumX += x - oldX;
            sumY += y - oldY;
            sumX2 += (x * x) - (oldX * oldX);
            sumXY += (x * y) - (oldX * oldY);

            head = (head + 1) % size;
        }

        if (count < 2)
            return 0.0;

        double n = count;
        double covNum = n * sumXY - sumX * sumY;
        double varNum = n * sumX2 - sumX * sumX;

        // Beta = Cov / Var
        // (CovNum / N^2) / (VarNum / N^2) = CovNum / VarNum

        if (Math.abs(varNum) < 1e-12)
            return 0.0;

        return covNum / varNum;
    }
}
