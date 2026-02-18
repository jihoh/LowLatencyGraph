package com.trading.drg.fn.finance;

import com.trading.drg.fn.FnN;

public class WeightedAverage implements FnN {
    @Override
    public double apply(double[] inputs) {
        double sumProd = 0.0;
        double sumW = 0.0;
        // Expect inputs as pairs: (value, weight, value, weight, ...)
        for (int i = 0; i < inputs.length; i += 2) {
            double val = inputs[i];
            // If odd number of inputs, last weight is missing (assume 1.0 or ignore?)
            // Safest to check bounds
            if (i + 1 >= inputs.length) break;
            
            double w = inputs[i + 1];
            sumProd += val * w;
            sumW += w;
        }
        return (sumW == 0) ? 0.0 : sumProd / sumW;
    }
}
