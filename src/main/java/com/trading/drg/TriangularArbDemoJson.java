package com.trading.drg;

// Disruptor imports removed
import com.trading.drg.api.Node;
import com.trading.drg.api.ScalarValue;
import com.trading.drg.fn.finance.TriangularArbSpread;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.io.JsonParser;
import com.trading.drg.node.GenericFn1Node;
import com.trading.drg.node.ScalarNode;
import com.trading.drg.util.GraphExplain;
import com.trading.drg.util.ScalarCutoffs;
import com.trading.drg.fn.finance.Ewma;
// wiring imports removed
import com.trading.drg.node.ScalarSourceNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

/**
 * JSON-based version of {@link TriangularArbDemo}.
 *
 * Demonstrates:
 * 1. Loading graph definition from JSON.
 * 2. Registering custom node types (`tri_arb_spread`).
 * 3. `DependencyInjectable` for late-binding inputs in JSON nodes.
 */
public class TriangularArbDemoJson {
    private static final Logger log = LogManager.getLogger(TriangularArbDemoJson.class);

    public static void main(String[] args) throws Exception {
        log.info("Starting Triangular Arbitrage Demo (JSON)...");

        // 1. Load JSON Definition
        // In a real app, this path would be config-driven
        var jsonPath = Path.of("src/main/resources/tri_arb.json");
        var graphDef = JsonParser.parseFile(jsonPath);

        // 2. Configure Compiler
        var compiler = new JsonGraphCompiler();
        compiler.registerBuiltIns(); // Register scalar_source, etc.

        // 3. Compile Graph
        var compiledGraph = compiler.compile(graphDef);
        var engine = compiledGraph.engine();
        var context = compiledGraph.nodesByName(); // Name -> Node map

        // 4. Setup Listener
        var listener = new com.trading.drg.util.LatencyTrackingListener();
        engine.setListener(listener);

        // 5. Setup (Direct Engine Usage)
        // No Disruptor needed.

        // Get the specific node we want to watch
        var arbSpreadNode = (ScalarValue) context.get("Arb.Spread");
        var eurUsdNode = (ScalarValue) context.get("EURUSD");
        var usdJpyNode = (ScalarValue) context.get("USDJPY");
        var eurJpyNode = (ScalarValue) context.get("EURJPY");
        var arbEwmaNode = (ScalarValue) context.get("Arb.Spread.Ewma");

        // Cast to ScalarSourceNode for updates
        var eurUsdSource = (ScalarSourceNode) context.get("EURUSD");
        var usdJpySource = (ScalarSourceNode) context.get("USDJPY");
        var eurJpySource = (ScalarSourceNode) context.get("EURJPY");

        // Pre-resolve node IDs using the engine's topology
        int eurUsdId = engine.topology().topoIndex("EURUSD");
        int usdJpyId = engine.topology().topoIndex("USDJPY");
        int eurJpyId = engine.topology().topoIndex("EURJPY");

        // --- Export Graph Visualization ---
        try {
            String mermaid = new GraphExplain(engine).toMermaid();
            java.nio.file.Files.writeString(java.nio.file.Path.of("tri_arb_demo_json.md"), mermaid);
            log.info("Graph visualization saved to tri_arb_demo_json.md");
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }

        // 6. Simulation Loop
        Random rng = new Random(42);
        int updates = 10_000;
        CountDownLatch latch = new CountDownLatch(1);

        log.info("Publishing updates...");
        for (int i = 0; i < updates; i++) {
            double shock = (rng.nextDouble() - 0.5) * 0.01;

            if (i % 500 == 0) {
                eurJpySource.updateDouble(158.0);
                engine.markDirty(eurJpyId);
            } else {
                eurUsdSource.updateDouble(eurUsdNode.doubleValue() + shock);
                engine.markDirty(eurUsdId);

                usdJpySource.updateDouble(usdJpyNode.doubleValue() + shock * 100);
                engine.markDirty(usdJpyId);

                eurJpySource.updateDouble(eurJpyNode.doubleValue() + shock * 100);
                engine.markDirty(eurJpyId);
            }

            engine.stabilize();

            if (i % 1000 == 0) {
                double spread = arbSpreadNode.doubleValue();
                if (Math.abs(spread) > 0.05) {
                    log.info(String.format(
                            "[Arb Opportunity] Spread: %.4f | EWMA: %.4f | EURUSD: %.4f | USDJPY: %.2f | EURJPY: %.2f",
                            spread, arbEwmaNode.doubleValue(), eurUsdNode.doubleValue(), usdJpyNode.doubleValue(),
                            eurJpyNode.doubleValue()));
                }
            }

            try {
                Thread.sleep(0, 500_000);
            } catch (InterruptedException e) {
            }
        }

        Thread.sleep(1000); // Wait for processing

        log.info("Demo complete.");
        log.info(String.format("Latency Stats: Avg: %.2f us | Min: %d ns | Max: %d ns | Total Events: %d",
                listener.avgLatencyMicros(),
                listener.minLatencyNanos(),
                listener.maxLatencyNanos(),
                listener.totalStabilizations()));
    }

    /**
     * Custom node implementation for Triangular Arbitrage.
     * Implements DependencyInjectable to receive inputs from JSON wiring.
     */
    private static class TriangularArbNode extends ScalarNode implements JsonGraphCompiler.DependencyInjectable {
        private final TriangularArbSpread fn = new TriangularArbSpread();
        private ScalarValue leg1;
        private ScalarValue leg2;
        private ScalarValue direct;

        public TriangularArbNode(String name) {
            super(name, ScalarCutoffs.EXACT);
        }

        @Override
        public void injectDependencies(Node<?>[] upstreams) {
            if (upstreams.length != 3) {
                throw new IllegalArgumentException("TriangularArbNode requires 3 inputs: leg1, leg2, direct");
            }
            // Assuming order matches JSON dependencies list: [EURUSD, USDJPY, EURJPY]
            this.leg1 = (ScalarValue) upstreams[0];
            this.leg2 = (ScalarValue) upstreams[1];
            this.direct = (ScalarValue) upstreams[2];
        }

        @Override
        protected double compute() {
            return fn.apply(leg1.doubleValue(), leg2.doubleValue(), direct.doubleValue());
        }
    }

    private static class EwmaNode extends ScalarNode implements JsonGraphCompiler.DependencyInjectable {
        private final Ewma ewma;
        private ScalarValue input;

        public EwmaNode(String name, double alpha) {
            super(name, ScalarCutoffs.EXACT);
            this.ewma = new Ewma(alpha);
        }

        @Override
        public void injectDependencies(Node<?>[] upstreams) {
            this.input = (ScalarValue) upstreams[0];
        }

        @Override
        protected double compute() {
            return ewma.apply(input.doubleValue());
        }
    }
}
