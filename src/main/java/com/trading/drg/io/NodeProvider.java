package com.trading.drg.io;

/**
 * Extension point for registering custom calculator nodes without
 * modifying the core library.
 *
 * <p>Implement this interface in your own package and install it
 * via {@link NodeRegistry#install(NodeProvider)} before graph
 * compilation. All registration uses string-based type names,
 * so custom calculators do not require changes to {@link NodeType}.
 *
 * <h3>Example</h3>
 * <pre>{@code
 * public class AlphaNodeProvider implements NodeProvider {
 *     @Override
 *     public void register(NodeRegistry registry) {
 *         registry.registerFn2(
 *             "MOMENTUM_SCORE",
 *             new String[]{"price", "movingAvg"},
 *             props -> new MomentumScore(
 *                 JsonGraphCompiler.getInt(props, "lookback", 20)
 *             )
 *         );
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface NodeProvider {

    /**
     * Register all custom node types provided by this pack.
     *
     * @param registry the node registry to register factories into
     */
    void register(NodeRegistry registry);
}
