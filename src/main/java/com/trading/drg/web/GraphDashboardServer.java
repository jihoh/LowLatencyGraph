package com.trading.drg.web;

import io.javalin.Javalin;
import io.javalin.websocket.WsContext;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Lightweight web server for the real-time graph dashboard and WebSocket state
 * pushing.
 */
@Log4j2
public class GraphDashboardServer {

    // Track active WebSocket client sessions (Set wrapper)
    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private Javalin app;

    // Cached heavy structural JSON sent upon connection
    @Setter
    private volatile String initialGraphConfig = null;

    // Supplier to build the snapshot JSON on demand
    @Setter
    private Supplier<String> snapshotSupplier = null;

    /** Starts the dashboard server on the specified port. */
    public void start(int port) {
        log.info("Starting Graph Dashboard Server on port {}", port);

        app = Javalin.create(config -> {
            // Serve frontend files from classpath 'public'
            config.staticFiles.add("/public");

            // Allow massive JSON payloads
            config.jetty.modifyWebSocketServletFactory(factory -> {
                factory.setMaxTextMessageSize(10_000_000L);
            });
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

                // Immediately flush structural payload
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

    /** Broadcasts a JSON string payload to all connected clients. */
    public void broadcast(String jsonPayload) {
        if (sessions.isEmpty()) {
            return; // Fast path
        }

        for (WsContext ctx : sessions) {
            if (ctx.session.isOpen()) {
                ctx.send(jsonPayload);
            }
        }
    }

    /** Stops the dashboard server. */
    public void stop() {
        if (app != null) {
            app.stop();
            sessions.clear();
        }
    }
}
