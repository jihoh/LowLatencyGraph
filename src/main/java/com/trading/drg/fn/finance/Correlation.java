package com.trading.drg.fn.finance;

import com.trading.drg.fn.Fn2;

/**
 * Rolling Correlation of X and Y.
 * 
 * Corr = Cov(X, Y) / (StdDev(X) * StdDev(Y))
 * 
 * Implements Fn2 so it can be used in g.compute(name, new Correlation(20), x,
 * y).
 */
public class Correlation implements Fn2 {
    private final double[] xWindow;
    private final double[] yWindow;
    private final int size;
    private int head = 0;
    private int count = 0;

    private double sumX = 0;
    private double sumY = 0;
    private double sumX2 = 0;
    private double sumY2 = 0;
    private double sumXY = 0;

    public Correlation(int size) {
        if (size < 2)
            throw new IllegalArgumentException("Size must be >= 2");
        this.size = size;
        this.xWindow = new double[size];
        this.yWindow = new double[size];
    }

    @Override
    public double apply(double x, double y) {
        if (count < size) {
            // Fill
            xWindow[head] = x;
            yWindow[head] = y;
            sumX += x;
            sumY += y;
            sumX2 += x * x;
            sumY2 += y * y;
            sumXY += x * y;
            head = (head + 1) % size;
            count++;
        } else {
            // Update
            double oldX = xWindow[head];
            double oldY = yWindow[head];

            xWindow[head] = x;
            yWindow[head] = y;

            sumX += x - oldX;
            sumY += y - oldY;
            sumX2 += (x * x) - (oldX * oldX);
            sumY2 += (y * y) - (oldY * oldY);
            sumXY += (x * y) - (oldX * oldY);

            head = (head + 1) % size;
        }

        if (count < 2)
            return 0.0;

        // E[XY] - E[X]E[Y]
        // This is Cov(X,Y) * N^2 ... wait.
        // Cov = (SumXY / N) - (SumX/N)*(SumY/N)
        // VarX = (SumX2 / N) - (SumX/N)^2

        // To avoid divisions, multiply everything by N^2?
        // Corr = Cov / sqrt(VarX * VarY)
        // = [ N*SumXY - SumX*SumY ] / sqrt( [N*SumX2 - SumX^2] * [N*SumY2 - SumY^2] )

        double n = count;
        double num = n * sumXY - sumX * sumY;
        double denX = n * sumX2 - sumX * sumX;
        double denY = n * sumY2 - sumY * sumY;

        if (denX <= 1e-12 || denY <= 1e-12)
            return 0.0;

        return num / Math.sqrt(denX * denY);
    }
}
