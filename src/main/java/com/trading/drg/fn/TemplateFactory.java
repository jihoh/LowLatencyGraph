package com.trading.drg.fn;

import com.trading.drg.dsl.GraphBuilder;

/**
 * Interface for reusable graph sub-structures (templates).
 *
 * <p>
 * Allows encapsulating common patterns (e.g., "Create a Black-Scholes Pricer")
 * into reusable factories.
 *
 * @param <C> Configuration object type.
 * @param <T> Result type (e.g., the output node, or a container of output
 *            nodes).
 */
@FunctionalInterface
public interface TemplateFactory<C, T> {
    /**
     * Instantiates the template in the given graph builder.
     *
     * @param b      The graph builder.
     * @param prefix A unique prefix for naming nodes created by this template.
     * @param config Configuration parameters.
     * @return The result artifact.
     */
    T create(GraphBuilder b, String prefix, C config);
}
