package com.trading.drg;

import com.trading.drg.api.*;
// import com.trading.drg.wiring.*;
import com.trading.drg.node.*;
import com.trading.drg.dsl.GraphBuilder;

// Disruptor imports removed
import com.trading.drg.api.ScalarValue;
import com.trading.drg.api.Node;
// wiring imports removed
import com.trading.drg.util.GraphExplain;
import com.trading.drg.node.MapNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SwapPricerDemo: Swap Outright Pricer
 * 
 * Topology Depth: 4 Layers
 * Nodes: >10
 * Logic: Fixed-Float Swap Valuation
 */
public class SwapPricerDemo {
    private static final Logger log = LogManager.getLogger(SwapPricerDemo.class);

    public static void main(String[] args) throws InterruptedException {
        log.info("════════════════════════════════════════════════");
        log.info("  Swap Pricer Demo (4 Layers, Vector Math)");
        log.info("════════════════════════════════════════════════");

        // 1. Build Graph
        var g = GraphBuilder.create("swap_pricer");

        // --- Layer 1: Market Data ---
        // 5Y Benchmark Yield
        var yield5Y = g.scalarSource("Mkt.UST_5Y.Yield", 4.50);
        // 5Y Swap Spread (bps)
        var spread5Y = g.scalarSource("Mkt.SwapSpread.5Y", 0.15); // 15 bps
        // Overnight Rate for Discounting
        var sofrRate = g.scalarSource("Mkt.SOFR.Rate", 4.40);
        // Trade Notional (Constants can contain "Const" in name for clarity, but they
        // are just sources/params)
        var notional = g.scalarSource("Mkt.Notional", 10_000_000.0);

        // --- Layer 2: Derived Curves & Rates ---

        // Swap Rate = Yield + Spread
        var swapRate = g.compute("Calc.SwapRate", (y, s) -> y + s, yield5Y, spread5Y);

        // Discount Curve (Vector of 10 points)
        // Logic: Simple flat curve bootstrap simulation
        // D(t) = 1 / (1 + r)^t
        int curvePoints = 10; // 1Y to 10Y
        var discountCurve = g.computeVector("Calc.DiscountCurve", curvePoints, 1e-15,
                (inputs, output) -> {
                    double r = ((ScalarValue) inputs[0]).doubleValue() / 100.0;
                    for (int i = 0; i < output.length; i++) {
                        double t = i + 1.0;
                        output[i] = 1.0 / Math.pow(1.0 + r, t);
                    }
                },
                sofrRate); // varargs

        // Layer 2.5: Sum of Discount Factors
        List<ScalarValue> dfs = new ArrayList<>();
        for (int i = 0; i < curvePoints; i++) {
            dfs.add(g.vectorElement("Calc.DF." + (i + 1) + "Y", discountCurve, i));
        }

        var annuityFactorS = g.computeN("Risk.AnnuityFactor",
                (inputs) -> {
                    double sum = 0;
                    for (double d : inputs)
                        sum += d;
                    return sum * 0.5;
                },
                dfs.toArray(new ScalarValue[0]));

        // Fixed Leg PV
        var fixedLegPVS = g.compute("Pricer.FixedLeg.PV",
                (n, r, a) -> n * (r / 100.0) * a,
                notional, swapRate, annuityFactorS);

        // Float Leg PV
        var lastDF = dfs.get(curvePoints - 1);
        var floatLegPVS = g.compute("Pricer.FloatLeg.PV",
                (n, df) -> n * (1.0 - df),
                notional, lastDF);

        // --- Layer 4: Valuation & Risk ---

        // NPV
        var npv = g.compute("Valuation.NPV", (fl, fx) -> fl - fx, floatLegPVS, fixedLegPVS);

        // DV01
        var dv01 = g.compute("Valuation.DV01", (a, n) -> a * n * 0.0001, annuityFactorS, notional);

        // Reporting Node
        String[] reportKeys = { "NPV", "DV01", "Rate", "Spread" };

        var report = g.mapNode("Report.SwapDetails",
                (inputs, writer) -> {
                    writer.put("NPV", ((ScalarValue) inputs[0]).doubleValue());
                    writer.put("DV01", ((ScalarValue) inputs[1]).doubleValue());
                    writer.put("Rate", ((ScalarValue) inputs[2]).doubleValue());
                    writer.put("Spread", ((ScalarValue) inputs[3]).doubleValue());
                },
                reportKeys,
                npv, dv01, swapRate, spread5Y);

        // 2. Build Engine
        var context = g.buildWithContext();
        var engine = context.engine();
        engine.setListener(new com.trading.drg.util.LatencyTrackingListener());

        // 3. Status
        // No Disruptor needed.

        long tStart = System.nanoTime();
        int totalUpdates = 200_000;

        // 4. Simulation Loop
        Random rng = new Random(42);
        log.info("Starting producer...");

        // Pre-resolve node IDs (Zero-GC hot path)
        int yieldId = context.getNodeId("Mkt.UST_5Y.Yield");
        int spreadId = context.getNodeId("Mkt.SwapSpread.5Y");
        int rateId = context.getNodeId("Mkt.SOFR.Rate");

        // Pre-resolve nodes for direct update
        ScalarSourceNode yieldNode = (ScalarSourceNode) context.nodesByName().get("Mkt.UST_5Y.Yield");
        ScalarSourceNode spreadNode = (ScalarSourceNode) context.nodesByName().get("Mkt.SwapSpread.5Y");
        ScalarSourceNode rateNode = (ScalarSourceNode) context.nodesByName().get("Mkt.SOFR.Rate");

        for (int i = 0; i < totalUpdates; i++) {
            // Random walk on Market Data
            double yieldShock = rng.nextGaussian() * 0.01;
            double spreadShock = rng.nextGaussian() * 0.001;
            double rateShock = rng.nextGaussian() * 0.01;

            // Direct updates
            yieldNode.updateDouble(4.50 + yieldShock);
            engine.markDirty(yieldId);

            spreadNode.updateDouble(0.15 + spreadShock);
            engine.markDirty(spreadId);

            rateNode.updateDouble(4.40 + rateShock);
            engine.markDirty(rateId);

            // Stabilize
            engine.stabilize();

            if (i % 50000 == 0) {
                printReport(report);
            }
        }

        long elapsed = System.nanoTime() - tStart;
        log.info(String.format("Processed %d updates (batches) in %.1f ms (%.0f batches/sec)",
                totalUpdates, elapsed / 1e6, 1e9 * totalUpdates / elapsed));
    }

    private static void printReport(MapNode node) {
        Map<String, Double> val = node.value();
        log.info(String.format("[Report] NPV: $%,.2f | DV01: $%,.2f | Rate: %.3f%% | Spread: %.2f bps",
                val.get("NPV"), val.get("DV01"), val.get("Rate"), val.get("Spread") * 10000));
    }
}
