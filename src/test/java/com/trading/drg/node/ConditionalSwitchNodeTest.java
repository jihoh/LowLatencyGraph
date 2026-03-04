package com.trading.drg.node;

import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.engine.StabilizationEngine;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link ConditionalSwitchNode}.
 *
 * Graph topology used across tests:
 * <pre>
 *   priceA ──┐
 *             ├─► switch (condition=IsBullish) ─► switch.true  ─► bullishCalc
 *   priceB ──┘                                ─► switch.false ─► bearishCalc
 *   signal ──► IsBullish
 * </pre>
 */
public class ConditionalSwitchNodeTest {

    private StabilizationEngine engine;
    private ScalarSourceNode priceA;
    private ScalarSourceNode priceB;
    private ScalarSourceNode signal;
    private ConditionalSwitchNode sw;

    // Downstream calc nodes that track the two branches
    private ScalarCalcNode bullishCalc;
    private ScalarCalcNode bearishCalc;

    // Stabilization counters for each calc node
    private int bullishCount;
    private int bearishCount;

    @Before
    public void setUp() {
        bullishCount = 0;
        bearishCount = 0;

        GraphBuilder b = GraphBuilder.create();

        priceA = b.scalarSource("priceA", 100.0);
        priceB = b.scalarSource("priceB", 50.0);
        signal = b.scalarSource("signal", 1.0); // 1.0 => bullish (true)

        BooleanNode isBullish = b.condition("IsBullish", signal, v -> v > 0.0);

        // Route priceA through the switch based on bullish/bearish signal
        sw = b.conditionalSwitch("Switch", isBullish, priceA);

        // Downstream nodes attached to each branch
        bullishCalc = new ScalarCalcNode("BullishCalc",
                com.trading.drg.util.ScalarCutoffs.ALWAYS, () -> {
                    bullishCount++;
                    return sw.branchTrue().value() * 1.1;
                });
        bearishCalc = new ScalarCalcNode("BearishCalc",
                com.trading.drg.util.ScalarCutoffs.ALWAYS, () -> {
                    bearishCount++;
                    return sw.branchFalse().value() * 0.9;
                });

        engine = GraphBuilder.create()
                .build(); // placeholder — we'll use the builder pattern below

        // Rebuild using the builder to wire BullishCalc / BearishCalc via edges
        GraphBuilder b2 = GraphBuilder.create();
        priceA  = b2.scalarSource("priceA", 100.0);
        priceB  = b2.scalarSource("priceB", 50.0);
        signal  = b2.scalarSource("signal", 1.0);

        BooleanNode isBullish2 = b2.condition("IsBullish", signal, v -> v > 0.0);
        sw = b2.conditionalSwitch("Switch", isBullish2, priceA);

        // Inline lambdas that read from the branch nodes directly
        final ConditionalSwitchNode swRef = sw;
        ScalarCalcNode bUp = new ScalarCalcNode("BullishCalc",
                com.trading.drg.util.ScalarCutoffs.ALWAYS, () -> {
                    bullishCount++;
                    return swRef.branchTrue().value() * 1.1;
                });
        ScalarCalcNode bDown = new ScalarCalcNode("BearishCalc",
                com.trading.drg.util.ScalarCutoffs.ALWAYS, () -> {
                    bearishCount++;
                    return swRef.branchFalse().value() * 0.9;
                });

        // Manually build topology so we can attach BullishCalc / BearishCalc
        com.trading.drg.engine.TopologicalOrder topo =
                com.trading.drg.engine.TopologicalOrder.builder()
                        .addNode(priceA)
                        .addNode(priceB)
                        .addNode(signal)
                        .addNode(isBullish2)
                        .addNode(sw)
                        .addNode(sw.branchTrue())
                        .addNode(sw.branchFalse())
                        .addNode(bUp)
                        .addNode(bDown)
                        .markSource("priceA")
                        .markSource("priceB")
                        .markSource("signal")
                        .addEdge("signal",   "IsBullish")
                        .addEdge("priceA",   "Switch")
                        .addEdge("IsBullish","Switch")
                        .addEdge("Switch",   "Switch.true")
                        .addEdge("Switch",   "Switch.false")
                        .addEdge("Switch.true",  "BullishCalc")
                        .addEdge("Switch.false", "BearishCalc")
                        .build();

        engine = new StabilizationEngine(topo);
        bullishCalc = bUp;
        bearishCalc = bDown;
    }

    @Test
    public void testInitialStabilization_trueBranchActivated() {
        // signal=1.0 => condition=true => branchTrue should propagate
        engine.stabilize();

        assertTrue("BullishCalc should run on initial stabilization", bullishCount > 0);
        // BearishCalc still runs on first epoch (initialization fires both)
        assertEquals(110.0, bullishCalc.value(), 1e-9);
    }

    @Test
    public void testTrueBranchPropagatesOnInputChange_whenConditionTrue() {
        engine.stabilize(); // initial pass
        bullishCount = 0;
        bearishCount = 0;

        // Update priceA while condition is true => only true branch propagates
        priceA.update(200.0);
        engine.markDirty("priceA");
        engine.stabilize();

        assertTrue("BullishCalc should fire when priceA changes and condition=true",
                bullishCount > 0);
        assertEquals(220.0, bullishCalc.value(), 1e-9);
        assertEquals("BearishCalc must NOT fire; false branch is inactive", 0, bearishCount);
    }

    @Test
    public void testFalseBranchPropagatesOnInputChange_whenConditionFalse() {
        engine.stabilize(); // initial pass, condition=true

        // Flip condition to false
        signal.update(-1.0);
        engine.markDirty("signal");
        engine.stabilize();

        bullishCount = 0;
        bearishCount = 0;

        // Update priceA while condition is false => only false branch propagates
        priceA.update(50.0);
        engine.markDirty("priceA");
        engine.stabilize();

        assertEquals("BullishCalc must NOT fire; true branch is inactive", 0, bullishCount);
        assertTrue("BearishCalc should fire when priceA changes and condition=false",
                bearishCount > 0);
        assertEquals(45.0, bearishCalc.value(), 1e-9);
    }

    @Test
    public void testBothBranchesEvaluatedOnConditionFlip() {
        engine.stabilize(); // initial, condition=true
        bullishCount = 0;
        bearishCount = 0;

        // Flip condition: true → false
        signal.update(-5.0);
        engine.markDirty("signal");
        engine.stabilize();

        // Switch fires because condition changed; both branches re-evaluate.
        // true branch returns false (no longer active), false branch returns true.
        assertTrue("BearishCalc should fire after condition flips to false", bearishCount > 0);
    }

    @Test
    public void testNoWorkWhenInputUnchanged() {
        engine.stabilize(); // initial pass
        bullishCount = 0;
        bearishCount = 0;

        // No updates — second stabilize should do nothing
        engine.stabilize();

        assertEquals(0, bullishCount);
        assertEquals(0, bearishCount);
    }

    @Test
    public void testBranchNodeNames() {
        assertEquals("Switch.true",  sw.branchTrue().name());
        assertEquals("Switch.false", sw.branchFalse().name());
        assertTrue(sw.branchTrue().isTrue());
        assertFalse(sw.branchFalse().isTrue());
    }
}
