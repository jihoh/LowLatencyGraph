package com.trading.drg.fn.finance;

/**
 * Rolling Correlation of X and Y.
 * 
 * Corr = Cov(X, Y) / (StdDev(X) * StdDev(Y))
 * 
 * Implements Fn2 so it can be used in g.compute(name, new Correlation(20), x,
 * y).
 */
public class Correlation extends AbstractFn2 {
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
    protected double calculate(double x, double y) {
        // Remove old
        if (count >= size) {
            double oldX = xWindow[head];
            double oldY = yWindow[head];
            sumX -= oldX;
            sumY -= oldY;
            sumXY -= oldX * oldY;
            sumX2 -= oldX * oldX;
            sumY2 -= oldY * oldY;
        } else {
            // Logic error in previous snippet: count++ should happen if NOT full.
            // But let's be cleaner.
            // If full, remove old. If not full, count increases.
            // But removal happens at 'head' which is the oldest.
            // If full, we overwrite 'head'.
        }

        // Add new
        xWindow[head] = x;
        yWindow[head] = y;
        sumX += x;
        sumY += y;
        sumXY += x * y;
        sumX2 += x * x;
        sumY2 += y * y;

        head++;
        if (head >= size)
            head = 0;

        if (count < size)
            count++;

        if (count < 2)
            return 0.0;

        // To avoid divisions, multiply everything by N^2?
        // Corr = Cov / sqrt(VarX * VarY)
        // Cov = (SumXY - SumX*SumY/N) / N
        // VarX = (SumX2 - SumX^2/N) / N
        // Corr = (N*SumXY - SumX*SumY) / sqrt((N*SumX2 - SumX^2) * (N*SumY2 - SumY^2))

        double n = count;
        double num = n * sumXY - sumX * sumY;
        double denX = n * sumX2 - sumX * sumX;
        double denY = n * sumY2 - sumY * sumY;

        if (denX <= 1e-9 || denY <= 1e-9)
            return 0.0; // Avoid division by zero or NaN due to sqrt of neg

        return num / Math.sqrt(denX * denY);
    }

}
