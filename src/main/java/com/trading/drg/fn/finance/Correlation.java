package com.trading.drg.fn.finance;

/**
 * Rolling Correlation between X and Y using Welford's Method.
 * <br>
 * Formula: {@code Corr = mXY / sqrt(m2X * m2Y)}
 * <ul>
 * <li>{@code mXY = Σ((x_i - μX) * (y_i - μY))} (Co-moment)</li>
 * <li>{@code m2X = Σ(x_i - μX)^2} (Sum of Squares X)</li>
 * <li>{@code m2Y = Σ(y_i - μY)^2} (Sum of Squares Y)</li>
 * </ul>
 */
public class Correlation extends AbstractFn2 {
    private final double[] xWindow;
    private final double[] yWindow;
    private final int size;
    private int head = 0;
    private int count = 0;

    // Running Moments
    private double meanX = 0;
    private double meanY = 0;
    // M2x = sum((x_i - meanX)^2)
    private double m2X = 0;
    private double m2Y = 0;
    // Co-moment sum((x_i - meanX) * (y_i - meanY))
    private double mXY = 0;

    public Correlation(int size) {
        if (size < 2)
            throw new IllegalArgumentException("Size must be >= 2");
        this.size = size;
        this.xWindow = new double[size];
        this.yWindow = new double[size];
    }

    @Override
    protected double calculate(double x, double y) {
        if (count < size) {
            // 1. Initial Fill Mode (Standard Welford Add)
            count++;
            double deltaX = x - meanX;
            double deltaY = y - meanY;

            meanX += deltaX / count;
            meanY += deltaY / count;

            m2X += deltaX * (x - meanX);
            m2Y += deltaY * (y - meanY);
            mXY += deltaX * (y - meanY); // Equivalent to deltaY * (x - meanX)

        } else {
            // 2. Rolling Window Mode (Welford Remove Then Add)
            double oldX = xWindow[head];
            double oldY = yWindow[head];

            // Step 2a: Remove oldest point
            double oldDeltaX = oldX - meanX;
            double oldDeltaY = oldY - meanY;

            meanX -= oldDeltaX / (size - 1);
            meanY -= oldDeltaY / (size - 1);

            m2X -= oldDeltaX * (oldX - meanX);
            m2Y -= oldDeltaY * (oldY - meanY);
            mXY -= oldDeltaX * (oldY - meanY);

            // Step 2b: Add newest point
            double newDeltaX = x - meanX;
            double newDeltaY = y - meanY;

            meanX += newDeltaX / size;
            meanY += newDeltaY / size;

            m2X += newDeltaX * (x - meanX);
            m2Y += newDeltaY * (y - meanY);
            mXY += newDeltaX * (y - meanY);
        }

        // 3. Store in circular buffer
        xWindow[head] = x;
        yWindow[head] = y;

        head++;
        if (head >= size) {
            head = 0;
        }

        // 4. Calculate Final Correlation
        if (count < 2) {
            return 0.0;
        }

        // Correlation = Covariance / (StdDevX * StdDevY)
        // Which simplifies identically to: mXY / sqrt(m2X * m2Y)
        if (m2X <= 1e-14 || m2Y <= 1e-14) {
            // Handle zero variance edge cases (flat lines)
            return 0.0;
        }

        double corr = mXY / Math.sqrt(m2X * m2Y);

        // Clamp floating point strays [-1.0, 1.0]
        if (corr > 1.0)
            return 1.0;
        if (corr < -1.0)
            return -1.0;

        return corr;
    }
}
