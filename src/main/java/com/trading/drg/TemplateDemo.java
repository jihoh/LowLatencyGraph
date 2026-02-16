package com.trading.drg;

import com.trading.drg.api.*;
import com.trading.drg.engine.*;
import com.trading.drg.dsl.*;
import com.trading.drg.wiring.*;
import com.trading.drg.node.*;

import com.trading.drg.api.DoubleValue;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.fn.TemplateFactory;
import com.trading.drg.node.DoubleSourceNode;
import com.trading.drg.node.DoubleCalcNode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Random;

public class TemplateDemo {
    private static final Logger log = LogManager.getLogger(TemplateDemo.class);

    // ── Templates ────────────────────────────────────────────────────────

    // 1. Venue Book Template: Represents L2 data (Bid/Ask Px & Qty) for one venue
    record VenueConfig(String instrument, String venue) {
    }

    record VenueBook(DoubleSourceNode bidPx, DoubleSourceNode bidQty,
            DoubleSourceNode askPx, DoubleSourceNode askQty) {
    }

    static final TemplateFactory<VenueConfig, VenueBook> VENUE_TEMPLATE = (g, pfx, c) -> {
        // e.g., "UST_10Y.Btec.bid"
        String base = c.instrument + "." + c.venue;
        return new VenueBook(
                g.doubleSource(base + ".bid", 100.0),
                g.doubleSource(base + ".bidQty", 1000.0),
                g.doubleSource(base + ".ask", 100.015625), // 1/64 spread roughly
                g.doubleSource(base + ".askQty", 1000.0));
    };

    // 2. Instrument Template: Aggregates multiple venues
    record InstConfig(String name) {
    }

    record Instrument(VenueBook btec, VenueBook fenics, VenueBook dweb,
            DoubleCalcNode wBid, DoubleCalcNode wAsk) {
    }

    static final TemplateFactory<InstConfig, Instrument> INST_TEMPLATE = (g, pfx, c) -> {
        // Instantiate 3 venues
        var btec = g.template(pfx + ".Btec", VENUE_TEMPLATE, new VenueConfig(c.name, "Btec"));
        var fenics = g.template(pfx + ".Fenics", VENUE_TEMPLATE, new VenueConfig(c.name, "Fenics"));
        var dweb = g.template(pfx + ".Dweb", VENUE_TEMPLATE, new VenueConfig(c.name, "Dweb"));

        // Weighted Bid = Sum(Px * Qty) / Sum(Qty)
        // Inputs: 6 nodes (3 px, 3 qty)
        var wBid = g.computeN(pfx + ".wBid",
                new DoubleValue[] {
                        btec.bidPx(), btec.bidQty(),
                        fenics.bidPx(), fenics.bidQty(),
                        dweb.bidPx(), dweb.bidQty()
                },
                (inputs) -> {
                    double num = inputs[0] * inputs[1] + inputs[2] * inputs[3] + inputs[4] * inputs[5];
                    double den = inputs[1] + inputs[3] + inputs[5];
                    return den == 0 ? 0 : num / den;
                });

        // Weighted Ask
        var wAsk = g.computeN(pfx + ".wAsk",
                new DoubleValue[] {
                        btec.askPx(), btec.askQty(),
                        fenics.askPx(), fenics.askQty(),
                        dweb.askPx(), dweb.askQty()
                },
                (inputs) -> {
                    double num = inputs[0] * inputs[1] + inputs[2] * inputs[3] + inputs[4] * inputs[5];
                    double den = inputs[1] + inputs[3] + inputs[5];
                    return den == 0 ? 0 : num / den;
                });

        return new Instrument(btec, fenics, dweb, wBid, wAsk);
    };

    // ── Simulation ───────────────────────────────────────────────────────

    public static void main(String[] args) {
        log.info("════════════════════════════════════════════════");
        log.info("  Template Demo (7 OTRs x 3 Venues)");
        log.info("════════════════════════════════════════════════");

        var g = GraphBuilder.create("treasuries"); // Replaced LLGraph.builder
        String[] tenors = { "2Y", "3Y", "5Y", "7Y", "10Y", "20Y", "30Y" };
        Instrument[] instruments = new Instrument[tenors.length];

        // 1. Build Graph
        for (int i = 0; i < tenors.length; i++) {
            instruments[i] = g.template(tenors[i], INST_TEMPLATE, new InstConfig("UST_" + tenors[i]));
        }

        var engine = g.build();
        engine.stabilize(); // Initial state (Prices ~100.0)

        // 2. Simulation Loop
        Random rng = new Random(42);
        long t0 = System.nanoTime();
        int updates = 100_000;

        for (int i = 0; i < updates; i++) {
            // Randomly pick an instrument and venue to update
            int idx = rng.nextInt(tenors.length);
            Instrument inst = instruments[idx];
            int venueIdx = rng.nextInt(3);
            VenueBook book = switch (venueIdx) {
                case 0 -> inst.btec;
                case 1 -> inst.fenics;
                default -> inst.dweb;
            };

            // Update Price and Qty
            double mid = 100.0 + rng.nextGaussian() * 0.1;
            double spread = 0.015625; // 1/64
            book.bidPx().updateDouble(mid - spread / 2);
            book.askPx().updateDouble(mid + spread / 2);
            book.bidQty().updateDouble(1_000_000 + rng.nextInt(5_000_000));
            book.askQty().updateDouble(1_000_000 + rng.nextInt(5_000_000));

            // Mark Dirty
            engine.markDirty(book.bidPx().name());
            engine.markDirty(book.askPx().name());
            engine.markDirty(book.bidQty().name());
            engine.markDirty(book.askQty().name());

            engine.stabilize();

            if (i % 20000 == 0) {
                printSnapshot(tenors[idx], inst);
            }
        }

        long elapsed = System.nanoTime() - t0;
        log.info(String.format("Processed %d updates in %.1f ms (%.0f updates/sec)",
                updates, elapsed / 1e6, 1e9 * updates / elapsed));
    }

    private static void printSnapshot(String name, Instrument inst) {
        log.info(String.format("[%s] Weighted Bid: %.5f | Weighted Ask: %.5f | Spread: %.5f",
                name, inst.wBid.value(), inst.wAsk.value(),
                inst.wAsk.value() - inst.wBid.value()));
    }
}
