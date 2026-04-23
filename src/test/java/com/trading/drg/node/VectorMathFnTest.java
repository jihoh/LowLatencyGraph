package com.trading.drg.node;

import com.trading.drg.api.ScalarValue;
import com.trading.drg.api.VectorValue;
import com.trading.drg.dsl.GraphBuilder;
import com.trading.drg.engine.StabilizationEngine;
import com.trading.drg.engine.TopologicalOrder;
import com.trading.drg.io.GraphDefinition;
import com.trading.drg.io.JsonGraphCompiler;
import com.trading.drg.io.NodeType;

import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for VectorMathFn integration through GraphBuilder.computeVectorMath.
 * Validates zero-allocation N-to-M scalar computation.
 */
public class VectorMathFnTest {

    private GraphBuilder builder;

    @Before
    public void setUp() {
        builder = GraphBuilder.create();
    }

    /**
     * 3 inputs (width, height, depth) → 2 outputs (volume, surface_area).
     */
    @Test
    public void testNToMComputation() {
        ScalarSourceNode width = builder.scalarSource("width", 3.0);
        ScalarSourceNode height = builder.scalarSource("height", 4.0);
        ScalarSourceNode depth = builder.scalarSource("depth", 5.0);

        VectorCalcNode result = builder.computeVectorMath("box_stats", 2, 1e-15,
                (inputs, outputs) -> {
                    double w = inputs[0], h = inputs[1], d = inputs[2];
                    outputs[0] = w * h * d;                           // volume
                    outputs[1] = 2.0 * (w * h + h * d + w * d);      // surface area
                }, width, height, depth);

        builder.vectorElement("volume", result, 0);
        builder.vectorElement("surface_area", result, 1);

        StabilizationEngine engine = builder.build();
        TopologicalOrder topo = engine.topology();
        engine.stabilize();

        VectorValue vec = (VectorValue) topo.node(topo.topoIndex("box_stats"));
        assertEquals(60.0, vec.valueAt(0), 1e-12);   // 3*4*5
        assertEquals(94.0, vec.valueAt(1), 1e-12);    // 2*(12+20+15)

        ScalarValue vol = (ScalarValue) topo.node(topo.topoIndex("volume"));
        ScalarValue sa = (ScalarValue) topo.node(topo.topoIndex("surface_area"));
        assertEquals(60.0, vol.value(), 1e-12);
        assertEquals(94.0, sa.value(), 1e-12);
    }

    @Test
    public void testPropagatesOnInputChange() {
        ScalarSourceNode a = builder.scalarSource("A", 10.0);
        ScalarSourceNode b = builder.scalarSource("B", 20.0);

        builder.computeVectorMath("sum_diff", 2, 1e-15,
                (inputs, outputs) -> {
                    outputs[0] = inputs[0] + inputs[1]; // sum
                    outputs[1] = inputs[0] - inputs[1]; // diff
                }, a, b);

        StabilizationEngine engine = builder.build();
        TopologicalOrder topo = engine.topology();
        engine.stabilize();

        VectorValue vec = (VectorValue) topo.node(topo.topoIndex("sum_diff"));
        assertEquals(30.0, vec.valueAt(0), 1e-12);
        assertEquals(-10.0, vec.valueAt(1), 1e-12);

        // Update input A → should propagate
        a.update(15.0);
        engine.markDirty(topo.topoIndex("A"));
        engine.stabilize();

        assertEquals(35.0, vec.valueAt(0), 1e-12);
        assertEquals(-5.0, vec.valueAt(1), 1e-12);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRejectsZeroSize() {
        ScalarSourceNode a = builder.scalarSource("A", 1.0);
        builder.computeVectorMath("bad", 0, 1e-15,
                (inputs, outputs) -> {}, a);
    }

    /**
     * Parses an inline JSON graph definition with a custom VectorMathFn
     * registered via JsonGraphCompiler, verifying that auto_expand produces
     * the expected scalar output nodes.
     */
    @Test
    public void testJsonCompilationWithAutoExpand() throws Exception {
        String json = """
                {
                  "graph": {
                    "name": "vector_math_test",
                    "version": "1.0",
                    "nodes": [
                      { "name": "width",  "type": "SCALAR_SOURCE", "properties": { "value": 3.0 } },
                      { "name": "height", "type": "SCALAR_SOURCE", "properties": { "value": 4.0 } },
                      { "name": "depth",  "type": "SCALAR_SOURCE", "properties": { "value": 5.0 } },
                      {
                        "name": "box_stats",
                        "type": "VECTOR_MATH",
                        "inputs": { "i0": "width", "i1": "height", "i2": "depth" },
                        "properties": {
                          "size": 2,
                          "auto_expand": true,
                          "auto_expand_labels": ["volume", "surface_area"]
                        }
                      }
                    ]
                  }
                }
                """;

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        GraphDefinition def = mapper.readValue(json, GraphDefinition.class);

        // Register custom VectorMathFn on the compiler
        JsonGraphCompiler compiler = new JsonGraphCompiler();
        compiler.getRegistry().registerVectorMathFn(NodeType.VECTOR_MATH, props -> {
            return (inputs, outputs) -> {
                double w = inputs[0], h = inputs[1], d = inputs[2];
                outputs[0] = w * h * d;                           // volume
                outputs[1] = 2.0 * (w * h + h * d + w * d);      // surface area
            };
        });

        // Compile and stabilize
        JsonGraphCompiler.CompiledGraph compiled = compiler.compile(def);
        StabilizationEngine engine = compiled.engine();
        TopologicalOrder topo = engine.topology();
        engine.stabilize();

        // Verify the auto-expanded scalar element nodes exist and have correct values
        ScalarValue vol = (ScalarValue) topo.node(topo.topoIndex("box_stats.volume"));
        ScalarValue sa = (ScalarValue) topo.node(topo.topoIndex("box_stats.surface_area"));
        assertEquals(60.0, vol.value(), 1e-12);   // 3*4*5
        assertEquals(94.0, sa.value(), 1e-12);     // 2*(12+20+15)
    }

    @Test
    public void testWithMixedScalarAndVectorInputs() {
        VectorSourceNode vec = builder.vectorSource("vec_in", 3);
        ScalarSourceNode scalar = builder.scalarSource("scalar_in", 2.0);

        VectorCalcNode result = builder.computeVectorMath("mixed_stats", 1, 1e-15,
                (inputs, outputs) -> {
                    // inputs[0], inputs[1], inputs[2] from vec
                    // inputs[3] from scalar 
                    double sumVec = inputs[0] + inputs[1] + inputs[2];
                    outputs[0] = sumVec * inputs[3];
                }, vec, scalar);

        StabilizationEngine engine = builder.build();
        TopologicalOrder topo = engine.topology();
        engine.stabilize();

        // Initially vector is 0,0,0
        VectorValue resVec = (VectorValue) topo.node(topo.topoIndex("mixed_stats"));
        assertEquals(0.0, resVec.valueAt(0), 1e-12);

        // Update vector
        vec.update(new double[]{1.0, 2.0, 3.0});
        engine.markDirty(topo.topoIndex("vec_in"));
        engine.stabilize();

        // Sum = 6.0, multiplied by scalar 2.0 => 12.0
        assertEquals(12.0, resVec.valueAt(0), 1e-12);
    }
}
