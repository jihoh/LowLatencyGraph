package com.trading.drg.fn.finance;

/**
 * Harmonic Mean of N inputs. Useful for averaging rates or ratios.
 * <p>
 * Formula: {@code y = N / Sum(1 / x_i)}
 */
public class HarmonicMean extends AbstractFnN {

    @Override
    protected double calculate(double[] inputs) {
        if (inputs == null || inputs.length == 0) {
            return Double.NaN; // Consistent with Average for empty/null inputs
        }

        double sumInverse = 0;
        // Zero-GC loop
        for (double val : inputs) {
            if (val == 0.0) {
                return Double.NaN; // Cannot divide by zero or process NaN
            }
            sumInverse += 1.0 / val;
        }

        return inputs.length / sumInverse;
    }

}
