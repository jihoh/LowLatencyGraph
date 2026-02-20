package com.trading.drg;

import com.trading.drg.engine.*;
import com.trading.drg.dsl.*;
import com.trading.drg.node.*;
import com.trading.drg.fn.TemplateFactory;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.io.JsonParser;
import com.trading.drg.node.ScalarCalcNode;
import com.trading.drg.node.ScalarSourceNode;
import com.trading.drg.node.VectorSourceNode;

public class LLGraphTest {
    private static int passed = 0, failed = 0;

    static void check(String label, boolean ok) {
        if (ok) {
            System.out.println("  ✓ " + label);
            passed++;
        } else {
            System.out.println("  ✗ FAIL: " + label);
            failed++;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("════════════════════════════════════════════════");
        System.out.println("  LLGraph — Single File Test Suite");
        System.out.println("════════════════════════════════════════════════\n");

        testBasic();
        testQuoter();
        testVector();
        testSignals();
        testTemplate();
        testCutoff();
        testCycleDetection();
        testJson();
        testBenchmark();

        System.out.println("\n════════════════════════════════════════════════");
        System.out.printf("  %d passed, %d failed%n", passed, failed);
        System.out.println("════════════════════════════════════════════════");
        if (failed > 0)
            System.exit(1);
    }

    static void testBasic() {
        System.out.println("── 1. Basic bid/ask → mid/spread ──");
        var g = GraphBuilder.create("basic");
        var bid = g.scalarSource("bid", 99.5);
        var ask = g.scalarSource("ask", 100.5);
        var mid = g.compute("mid", (b, a) -> (b + a) / 2.0, bid, ask);
        var spread = g.compute("spread", (a, b) -> a - b, ask, bid);

        var engine = g.build();
        engine.markDirty("bid");
        engine.markDirty("ask");
        engine.stabilize();
        check("mid = 100.0", Math.abs(mid.doubleValue() - 100.0) < 1e-10);
        check("spread = 1.0", Math.abs(spread.doubleValue() - 1.0) < 1e-10);

        bid.updateDouble(99.0);
        engine.markDirty("bid");
        int n = engine.stabilize();
        check("Incremental: 3 nodes", n == 3);
        check("mid = 99.75", Math.abs(mid.doubleValue() - 99.75) < 1e-10);
    }

    static void testQuoter() {
        System.out.println("\n── 2. Quoter ──");
        var g = GraphBuilder.create("quoter");
        var theo = g.scalarSource("theo", 100.0);
        var vol = g.scalarSource("vol", 0.20);
        var inv = g.scalarSource("inv", 0.0);
        var baseSpread = g.scalarSource("base_spread", 0.10);
        var skew = g.compute("skew", i -> i * 0.01, inv);
        var aSpread = g.compute("aspread", (bs, v) -> bs * (1.0 + v), baseSpread, vol);
        var quoteBid = g.compute("bid", (t, s, sk) -> t - s / 2.0 + sk, theo, aSpread, skew);
        var quoteAsk = g.compute("ask", (t, s, sk) -> t + s / 2.0 - sk, theo, aSpread, skew);

        var engine = g.build();
        for (String s : new String[] { "theo", "vol", "inv", "base_spread" })
            engine.markDirty(s);
        engine.stabilize();
        check("Spread = 0.12", Math.abs(aSpread.doubleValue() - 0.12) < 1e-10);
        check("Bid < theo", quoteBid.doubleValue() < 100.0);
        check("Ask > theo", quoteAsk.doubleValue() > 100.0);
    }

    static void testVector() {
        System.out.println("\n── 3. Vector (curve) ──");
        var g = GraphBuilder.create("curve");
        var rates = g.vectorSource("rates", 4);
        rates.update(new double[] { 0.045, 0.048, 0.050, 0.052 });
        double[] tenors = { 0.25, 0.5, 1.0, 2.0 };

        var curve = g.computeVector("disc_curve", 4, 1e-12,
                (inputs, output) -> {
                    var r = (VectorSourceNode) inputs[0];
                    for (int i = 0; i < 4; i++)
                        output[i] = 1.0 / (1.0 + r.valueAt(i) * tenors[i]);
                },
                rates);
        var df0 = g.vectorElement("df_3m", curve, 0);

        var engine = g.build();
        engine.markDirty("rates");
        engine.stabilize();
        double expected = 1.0 / (1.0 + 0.045 * 0.25);
        check("DF(3m) correct", Math.abs(df0.doubleValue() - expected) < 1e-10);
    }

    static void testSignals() {
        System.out.println("\n── 5. Signals & select ──");
        var g = GraphBuilder.create("sig");
        var vol = g.scalarSource("vol", 0.15);
        var tight = g.scalarSource("tight", 100.0);
        var wide = g.scalarSource("wide", 99.5);
        var lowVol = g.condition("low_vol", vol, v -> v < 0.20);
        var quote = g.select("quote", lowVol, tight, wide);

        var engine = g.build();
        for (String s : new String[] { "vol", "tight", "wide" })
            engine.markDirty(s);
        engine.stabilize();
        check("Low vol → tight quote", Math.abs(quote.doubleValue() - 100.0) < 1e-10);

        vol.updateDouble(0.35);
        engine.markDirty("vol");
        engine.stabilize();
        check("High vol → wide quote", Math.abs(quote.doubleValue() - 99.5) < 1e-10);
    }

    static void testTemplate() {
        System.out.println("\n── 6. Template ──");
        var g = GraphBuilder.create("portfolio");
        var rate = g.scalarSource("rate", 0.05);
        record Cfg(double notional, double fixedRate) {
        }
        record Out(ScalarCalcNode npv) {
        }

        TemplateFactory<Cfg, Out> tmpl = (b, pfx, c) -> {
            var npv = b.compute(pfx + ".npv", r -> c.notional * (r - c.fixedRate), rate);
            return new Out(npv);
        };
        var s1 = g.template("swap1", tmpl, new Cfg(10_000_000, 0.04));
        var s2 = g.template("swap2", tmpl, new Cfg(5_000_000, 0.06));
        var total = g.computeN("total", d -> d[0] + d[1], s1.npv(), s2.npv());

        var engine = g.build();
        engine.markDirty("rate");
        engine.stabilize();
        check("Total = sum of swaps",
                Math.abs(total.doubleValue() - (s1.npv().doubleValue() + s2.npv().doubleValue())) < 1e-10);
    }

    static void testCutoff() {
        System.out.println("\n── 7. Cutoff ──");
        var g = GraphBuilder.create("cutoff");
        var x = g.scalarSource("x", 1.0);
        var y = g.compute("y", v -> v * 2.0, x);

        var engine = g.build();
        engine.markDirty("x");
        engine.stabilize();
        check("y = 2.0", Math.abs(y.doubleValue() - 2.0) < 1e-10);

        x.updateDouble(1.0); // same value
        engine.markDirty("x");
        int n = engine.stabilize();
        check("Same value → only source recomputed (cutoff stops propagation)", n == 1);
    }

    static void testCycleDetection() {
        System.out.println("\n── 8. Cycle detection ──");
        boolean caught = false;
        try {
            var t = TopologicalOrder.builder();
            t.addNode(new ScalarSourceNode("a", 0));
            t.addNode(new ScalarSourceNode("b", 0));
            t.addNode(new ScalarSourceNode("c", 0));
            t.addEdge("a", "b");
            t.addEdge("b", "c");
            t.addEdge("c", "a");
            t.build();
        } catch (IllegalStateException e) {
            caught = true;
        }
        check("Cycle rejected", caught);
    }

    static void testJson() {
        System.out.println("\n── 9. JSON compilation ──");
        String json = """
                { "graph": { "name": "test", "version": "1.0", "nodes": [
                    { "name": "x", "type": "scalar_source", "source": true, "properties": { "initial_value": 42.0 } },
                    { "name": "y", "type": "scalar_source", "source": true, "properties": { "initial_value": 7.0 } }
                ]}}""";
        var def = JsonParser.parse(json);
        var compiled = new JsonGraphCompiler().registerBuiltIns().compile(def);
        compiled.engine().markDirty("x");
        compiled.engine().markDirty("y");
        compiled.engine().stabilize();
        var x = (ScalarSourceNode) compiled.nodesByName().get("x");
        check("JSON source x = 42", Math.abs(x.doubleValue() - 42.0) < 1e-10);
    }

    static void testBenchmark() {
        System.out.println("\n── 11. Benchmark ──");
        var g = GraphBuilder.create("bench");
        var bid = g.scalarSource("bid", 99.5);
        var ask = g.scalarSource("ask", 100.5);
        var mid = g.compute("mid", (b, a) -> (b + a) / 2.0, bid, ask);
        var spread = g.compute("spread", (a, b) -> a - b, ask, bid);
        var skew = g.compute("skew", s -> s * 0.01, spread);
        var qBid = g.compute("qbid", (m, sk) -> m - sk, mid, skew);
        var qAsk = g.compute("qask", (m, sk) -> m + sk, mid, skew);
        var qSpread = g.compute("qspread", (a, b) -> a - b, qAsk, qBid);
        var signal = g.condition("wide", qSpread, s -> s > 2.0);

        var engine = g.build();
        engine.markDirty("bid");
        engine.markDirty("ask");
        engine.stabilize();

        // Warmup
        for (int i = 0; i < 100_000; i++) {
            bid.updateDouble(99.5 + (i % 100) * 0.001);
            engine.markDirty("bid");
            engine.stabilize();
        }

        int iters = 1_000_000;
        long t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            bid.updateDouble(99.5 + (i % 100) * 0.001);
            engine.markDirty("bid");
            engine.stabilize();
        }
        long elapsed = System.nanoTime() - t0;
        double avgNs = elapsed / (double) iters;
        System.out.printf("  %d iters, avg %.0fns (%.3fμs), %.0f stab/sec%n",
                iters, avgNs, avgNs / 1000.0, 1e9 / avgNs);
        check("Avg < 500ns", avgNs < 500);
        check("Throughput > 2M/sec", 1e9 / avgNs > 2_000_000);
    }
}
