package com.trading.drg.fn.finance;

import com.trading.drg.fn.FnN;

public class WeightedAverage implements FnN {
    @Override
    public double apply(double[] inputs) {
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

            if (Double.isNaN(val) || Double.isNaN(weight))
                return Double.NaN;

            sumProduct += val * weight;
            sumWeight += weight;
        }

        if (sumWeight == 0)
            return Double.NaN;
        return sumProduct / sumWeight;
    }
}
