package com.trading.drg.fn.finance;

public class Average extends AbstractFnN {

    @Override
    protected double calculate(double[] inputs) {
        if (inputs == null || inputs.length == 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (double d : inputs) {
            sum += d;
        }
        return sum / inputs.length;
    }

}
