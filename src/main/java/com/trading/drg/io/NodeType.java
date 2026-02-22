package com.trading.drg.io;

public enum NodeType {
    EWMA,
    DIFF,
    HIST_VOL,
    LOG_RETURN,
    MACD,
    ROLLING_MAX,
    ROLLING_MIN,
    RSI,
    SMA,
    Z_SCORE,
    BETA,
    CORRELATION,
    TRI_ARB_SPREAD,
    HARMONIC_MEAN,
    WEIGHTED_AVG,
    AVERAGE,
    SCALAR_SOURCE,
    DOUBLE_SOURCE,
    VECTOR_SOURCE,
    TEMPLATE;

    public static NodeType fromString(String text) {
        for (NodeType b : NodeType.values()) {
            if (b.name().equalsIgnoreCase(text)) {
                return b;
            }
        }
        throw new IllegalArgumentException("Unknown NodeType: " + text);
    }
}
