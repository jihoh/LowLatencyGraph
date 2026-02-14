package com.trading.drg;

import com.trading.drg.core.DoubleReadable;
import com.trading.drg.core.Node;
import com.trading.drg.io.GraphDefinition;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.io.JsonParser;
import com.trading.drg.node.DoubleNode;
import com.trading.drg.node.DoubleSourceNode;
import com.trading.drg.util.DoubleCutoffs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

public class TreasurySimulatorJson {

    public static void main(String[] args) throws IOException {
        System.out.println("════════════════════════════════════════════════");
        System.out.println("  Treasury Simulator (JSON Configured)");
        System.out.println("════════════════════════════════════════════════");

        // 1. Load JSON
        String json = Files.readString(Path.of("src/test/resources/treasury_topology.json"));
        GraphDefinition def = JsonParser.parse(json);

        // 2. Configure Compiler with Custom Factory
        var compiler = new JsonGraphCompiler().registerBuiltIns();

        compiler.registerFactory("weighted_avg", (name, props) -> {
            return new WeightedAvgNode(name);
        });

        // 3. Compile
        var compiled = compiler.compile(def);
        var engine = compiled.engine();
        var nodes = compiled.nodesByName();

        engine.stabilize();

        // 4. Simulation Loop
        String[] tenors = { "5Y", "10Y" }; // Only 2 in our JSON
        String[] venues = { "Btec", "Fenics", "Dweb" };

        Random rng = new Random(42);
        long t0 = System.nanoTime();
        int updates = 100_000;

        for (int i = 0; i < updates; i++) {
            String tenor = tenors[rng.nextInt(tenors.length)];
            String venue = venues[rng.nextInt(venues.length)];
            String base = "UST_" + tenor + "." + venue;

            // Update Price and Qty
            double mid = 100.0 + rng.nextGaussian() * 0.1;
            double spread = 0.015625;

            update(nodes, engine, base + ".bid", mid - spread / 2);
            update(nodes, engine, base + ".ask", mid + spread / 2);
            update(nodes, engine, base + ".bidQty", 1_000_000 + rng.nextInt(5_000_000));
            update(nodes, engine, base + ".askQty", 1_000_000 + rng.nextInt(5_000_000));

            engine.stabilize();

            if (i % 20000 == 0) {
                printSnapshot(tenor, nodes);
            }
        }

        long elapsed = System.nanoTime() - t0;
        System.out.printf("\nProcessed %d updates in %.1f ms (%.0f updates/sec)\n",
                updates, elapsed / 1e6, 1e9 * updates / elapsed);
    }

    private static void update(Map<String, Node<?>> nodes, com.trading.drg.core.StabilizationEngine engine, String name,
            double val) {
        if (nodes.get(name) instanceof DoubleSourceNode dsn) {
            dsn.updateDouble(val);
            engine.markDirty(name);
        }
    }

    private static void printSnapshot(String tenor, Map<String, Node<?>> nodes) {
        double bid = ((DoubleReadable) nodes.get("UST_" + tenor + ".wBid")).doubleValue();
        double ask = ((DoubleReadable) nodes.get("UST_" + tenor + ".wAsk")).doubleValue();
        System.out.printf("[UST_%s] Weighted Bid: %.5f | Weighted Ask: %.5f | Spread: %.5f\n",
                tenor, bid, ask, ask - bid);
    }

    // Custom Node for Weighted Average that supports late binding of dependencies
    private static class WeightedAvgNode extends DoubleNode implements JsonGraphCompiler.DependencyInjectable {
        private DoubleReadable[] inputs;

        public WeightedAvgNode(String name) {
            super(name, DoubleCutoffs.EXACT);
        }

        @Override
        public void injectDependencies(Node<?>[] upstreams) {
            this.inputs = new DoubleReadable[upstreams.length];
            for (int i = 0; i < upstreams.length; i++) {
                this.inputs[i] = (DoubleReadable) upstreams[i];
            }
        }

        @Override
        protected double compute() {
            if (inputs == null || inputs.length == 0)
                return 0.0;

            // Logic: pairs of (Price, Qty)
            double num = 0.0;
            double den = 0.0;

            for (int i = 0; i < inputs.length; i += 2) {
                if (i + 1 >= inputs.length)
                    break;
                double px = inputs[i].doubleValue();
                double qty = inputs[i + 1].doubleValue();
                num += px * qty;
                den += qty;
            }

            return den == 0 ? 0 : num / den;
        }
    }
}
