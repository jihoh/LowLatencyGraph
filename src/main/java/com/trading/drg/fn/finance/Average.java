package com.trading.drg.fn.finance;

import com.trading.drg.fn.FnN;

public class Average implements FnN {
    @Override
    public double apply(double[] inputs) {
        if (inputs.length == 0)
            return 0.0;
        double sum = 0.0;
        for (double v : inputs) {
            sum += v;
        }
        return sum / inputs.length;
    }
}
