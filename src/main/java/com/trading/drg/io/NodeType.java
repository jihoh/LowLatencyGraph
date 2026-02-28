package com.trading.drg.io;

import com.trading.drg.fn.finance.*;
import com.trading.drg.node.*;

/**
 * Enum of all built-in node types.
 * Factory lambdas are registered separately in {@link NodeRegistry}.
 */
public enum NodeType {
    EWMA(Ewma.class),
    DIFF(Diff.class),
    HIST_VOL(HistVol.class),
    LOG_RETURN(LogReturn.class),
    MACD(Macd.class),
    ROLLING_MAX(RollingMax.class),
    ROLLING_MIN(RollingMin.class),
    RSI(Rsi.class),
    SMA(Sma.class),
    Z_SCORE(ZScore.class),
    SPREAD(Spread.class),
    BETA(Beta.class),
    CORRELATION(Correlation.class),
    TRI_ARB_SPREAD(TriangularArbSpread.class),
    HARMONIC_MEAN(HarmonicMean.class),
    WEIGHTED_AVG(WeightedAverage.class),
    AVERAGE(Average.class),
    SCALAR_SOURCE(ScalarSourceNode.class),
    VECTOR_SOURCE(VectorSourceNode.class),
    VECTOR_ELEMENT(ScalarCalcNode.class),
    COMPUTE_VECTOR(VectorCalcNode.class),
    TEMPLATE(null);

    private final Class<?> nodeClass;

    NodeType(Class<?> nodeClass) {
        this.nodeClass = nodeClass;
    }

    public Class<?> getNodeClass() {
        return nodeClass;
    }

    public static NodeType fromString(String text) {
        for (NodeType b : NodeType.values()) {
            if (b.name().equalsIgnoreCase(text)) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unknown NodeType: " + text);
    }
}
