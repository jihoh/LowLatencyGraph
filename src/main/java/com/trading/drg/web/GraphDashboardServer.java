package com.trading.drg.web;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * A lightweight web server that hosts the real-time graph dashboard
 * and manages WebSocket connections for pushing state updates.
 */
public class GraphDashboardServer {
    private static final Logger log = LogManager.getLogger(GraphDashboardServer.class);

    // We use a ConcurrentHashMap to track active WebSocket client sessions.
    // The boolean value is just a placeholder, we only care about the keys
    // (WsContext).
    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private Javalin app;

    // Cached heavy structural JSON sent strictly upon connection
    private volatile String initialGraphConfig = null;

    // Supplier to build the snapshot JSON on demand
    private Supplier<String> snapshotSupplier = null;

    /**
     * Injects the heavy static Graph Configuration payload (Topology and Routing)
     * which only needs to be sent once per client connection.
     */
    public void setInitialGraphConfig(String jsonPayload) {
        this.initialGraphConfig = jsonPayload;
    }

    /**
     * Sets the supplier that generates the exact runtime state of the graph
     * when the /api/snapshot endpoint is called.
     */
    public void setSnapshotSupplier(Supplier<String> supplier) {
        this.snapshotSupplier = supplier;
    }

    /**
     * Starts the dashboard server on the specified port.
     * Starts serving static files from src/main/resources/public.
     *
     * @param port The port to listen on (e.g., 7070).
     */
    public void start(int port) {
        log.info("Starting Graph Dashboard Server on port {}", port);

        app = Javalin.create(config -> {
            // Serve frontend files (HTML/JS/CSS) from the classpath 'public' directory
            config.staticFiles.add("/public");
        }).start(port);

        // Snapshot API Endpoint
        app.get("/api/snapshot", ctx -> {
            if (snapshotSupplier != null) {
                ctx.contentType("application/json");
                ctx.result(snapshotSupplier.get());
            } else {
                ctx.status(503).result("{\"error\":\"Snapshot supplier not configured\"}");
            }
        });

        app.ws("/ws/graph", ws -> {
            ws.onConnect(ctx -> {
                log.info("WebSocket Client Connected: {}", ctx.sessionId());
                sessions.add(ctx);

                // Immediately flush the heavy structural payload dynamically.
                // The frontend relies on this specific 'init' structured response.
                if (initialGraphConfig != null && ctx.session.isOpen()) {
                    ctx.send(initialGraphConfig);
                }
            });
            ws.onClose(ctx -> {
                log.info("WebSocket Client Disconnected: {}", ctx.sessionId());
                sessions.remove(ctx);
            });
            ws.onError(ctx -> {
                log.error("WebSocket Client Error: {}", ctx.sessionId(), ctx.error());
                sessions.remove(ctx);
            });
        });
    }

    /**
     * Broadcasts a JSON string payload to all currently connected WebSocket
     * clients.
     *
     * @param jsonPayload The JSON string to broadcast.
     */
    public void broadcast(String jsonPayload) {
        if (sessions.isEmpty()) {
            return; // Fast path: avoid iteration if nobody is listening
        }

        for (WsContext ctx : sessions) {
            if (ctx.session.isOpen()) {
                ctx.send(jsonPayload);
            }
        }
    }

    /**
     * Stops the dashboard server.
     */
    public void stop() {
        if (app != null) {
            app.stop();
            sessions.clear();
        }
    }
}
