package com.trading.drg.fn.finance;

/**
 * Weighted average of inputs. Assumes inputs format:
 * {@code [val1, wt1, val2, wt2, ...]}.
 * <p>
 * Formula: {@code y = Sum(w_i * x_i) / Sum(w_i)}
 */
public class WeightedAverage extends AbstractFnN {

    @Override
    protected double calculate(double[] inputs) {
        if (inputs == null || inputs.length == 0)
            return Double.NaN;
        // Expect inputs to be pairs: [value1, weight1, value2, weight2, ...]
        if (inputs.length % 2 != 0)
            return Double.NaN;

        double sumProduct = 0;
        double sumWeight = 0;

        for (int i = 0; i < inputs.length; i += 2) {
            double val = inputs[i];
            double weight = inputs[i + 1];

            sumProduct += val * weight;
            sumWeight += weight;
        }

        if (sumWeight == 0)
            return Double.NaN;
        return sumProduct / sumWeight;
    }

}
