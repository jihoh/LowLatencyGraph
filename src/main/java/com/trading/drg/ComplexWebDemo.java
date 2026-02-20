package com.trading.drg;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;

/**
 * A comprehensive demonstration of the graph executing over 40 nodes
 * simultaneously.
 * Plugs directly into the javalin live dashboard.
 */
public class ComplexWebDemo {
    private static final Logger log = LogManager.getLogger(ComplexWebDemo.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting Complex Web Demo...");

        var graph = new CoreGraph("src/main/resources/complex_demo.json");
        var profiler = graph.enableNodeProfiling();
        var latencyListener = graph.enableLatencyTracking();

        // Ensure the server port is clear
        log.info("Booting Live Dashboard Server on 7077...");
        var dashboardServer = new com.trading.drg.web.GraphDashboardServer();
        dashboardServer.start(7077);

        var wsListener = new com.trading.drg.web.WebsocketPublisherListener(graph.getEngine(), dashboardServer);
        wsListener.setLatencyListener(latencyListener);
        wsListener.setProfileListener(profiler);
        graph.setListener(wsListener);

        String[] tickers = { "AAPL", "MSFT", "GOOG", "AMZN" };
        Random rng = new Random(100);

        log.info("Publishing updates for deep nodes...");
        for (int i = 0; i < 20_000; i++) {

            // Occasionally inject NaN for demonstration of NaN visualization
            if (i == 500) {
                graph.update("AAPL", Double.NaN);
                graph.stabilize();
                Thread.sleep(100);
                continue;
            } else if (i == 1000) { // Restore the real value
                graph.update("AAPL", 180.50);
            }

            // Normal random walk
            for (String ticker : tickers) {
                double current = graph.getDouble(ticker);
                double shock = (rng.nextDouble() - 0.5) * 0.5; // Simulate $0.50 volatility

                // If it's NaN, just reset it to a base value so it keeps ticking
                if (Double.isNaN(current)) {
                    graph.update(ticker, 150.0 + shock);
                } else {
                    graph.update(ticker, current + shock);
                }
            }

            graph.stabilize();

            if (i % 500 == 0) {
                log.info(String.format("Epoch %d: Tech_Avg_MACD=%.2f | GOOG_AMZN_Risk=%.2f | AAPL_Standalone_Risk=%.2f",
                        i,
                        graph.getDouble("Tech_Avg_MACD"),
                        graph.getDouble("GOOG_AMZN_Risk"),
                        graph.getDouble("AAPL_Standalone_Risk")));
            }

            Thread.sleep(50);
        }

        dashboardServer.stop();
        log.info("Complex web demo finished.");
    }
}
