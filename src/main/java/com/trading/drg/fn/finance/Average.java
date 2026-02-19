package com.trading.drg.fn.finance;

import com.trading.drg.fn.FnN;

public class Average implements FnN {
    @Override
    public double apply(double[] inputs) {
        if (inputs == null || inputs.length == 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (double d : inputs) {
            if (Double.isNaN(d)) {
                return Double.NaN;
            }
            sum += d;
        }
        return sum / inputs.length;
    }
}
