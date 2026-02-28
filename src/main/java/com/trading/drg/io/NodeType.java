package com.trading.drg.io;

import com.trading.drg.fn.finance.Average;
import com.trading.drg.fn.finance.Beta;
import com.trading.drg.fn.finance.Correlation;
import com.trading.drg.fn.finance.Diff;
import com.trading.drg.fn.finance.Ewma;
import com.trading.drg.fn.finance.HarmonicMean;
import com.trading.drg.fn.finance.HistVol;
import com.trading.drg.fn.finance.LogReturn;
import com.trading.drg.fn.finance.Macd;
import com.trading.drg.fn.finance.RollingMax;
import com.trading.drg.fn.finance.RollingMin;
import com.trading.drg.fn.finance.Rsi;
import com.trading.drg.fn.finance.Sma;
import com.trading.drg.fn.finance.Spread;
import com.trading.drg.fn.finance.TriangularArbSpread;
import com.trading.drg.fn.finance.WeightedAverage;
import com.trading.drg.fn.finance.ZScore;
import com.trading.drg.node.ScalarCalcNode;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.VectorCalcNode;
import com.trading.drg.node.VectorSourceNode;

/**
 * Enum of all built-in node types.
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
        for (NodeType b : values()) {
            if (b.name().equalsIgnoreCase(text)) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unknown NodeType: " + text);
    }
}
