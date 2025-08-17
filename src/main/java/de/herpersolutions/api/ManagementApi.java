package de.herpersolutions.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import de.herpersolutions.Zones.ZoneStore;
import de.herpersolutions.monitoring.DnsMetrics;
import de.herpersolutions.engine.AuthoritativeEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST API for DNS server management and monitoring
 */
public class ManagementApi {
    private static final Logger logger = LoggerFactory.getLogger(ManagementApi.class);
    
    private final Javalin app;
    private final ZoneStore zoneStore;
    private final DnsMetrics metrics;
    private final AuthoritativeEngine engine;
    
    public ManagementApi(int port, ZoneStore zoneStore, DnsMetrics metrics, AuthoritativeEngine engine) {
        this.zoneStore = zoneStore;
        this.metrics = metrics;
        this.engine = engine;
        
        this.app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        }).start(port);
        
        setupRoutes();
    }
    
    private void setupRoutes() {
        // Health check
        app.get("/health", this::healthCheck);
        
        // Metrics
        app.get("/metrics", this::getMetrics);
        app.post("/metrics/reset", this::resetMetrics);
        
        // Zone management
        app.get("/zones", this::listZones);
        app.get("/zones/{origin}", this::getZone);
        app.post("/zones/{origin}/reload", this::reloadZone);
        
        // Server management
        app.post("/reload", this::reloadAll);
    }
    
    private void healthCheck(Context ctx) {
        ctx.json("""
            {
              "status": "healthy",
              "server": "JDNS",
              "timestamp": %d,
              "zones_loaded": %d
            }""".formatted(System.currentTimeMillis(), zoneStore.zones.size()));
    }
    
    private void getMetrics(Context ctx) {
        ctx.contentType("application/json");
        ctx.result(metrics.getStatsJson());
    }
    
    private void resetMetrics(Context ctx) {
        metrics.reset();
        ctx.json("{\"message\": \"Metrics reset successfully\"}");
    }
    
    private void listZones(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"zones\": [\n");
        
        boolean first = true;
        for (String origin : zoneStore.zones.keySet()) {
            if (!first) sb.append(",\n");
            sb.append("    \"").append(origin).append("\"");
            first = false;
        }
        
        sb.append("\n  ],\n");
        sb.append("  \"count\": ").append(zoneStore.zones.size()).append("\n");
        sb.append("}");
        
        ctx.contentType("application/json");
        ctx.result(sb.toString());
    }
    
    private void getZone(Context ctx) {
        String origin = ctx.pathParam("origin");
        String normalizedOrigin = ZoneStore.normalize(origin);
        
        var zone = zoneStore.zones.get(normalizedOrigin);
        if (zone == null) {
            ctx.status(404).json("{\"error\": \"Zone not found\"}");
            return;
        }
        
        ctx.json(zone);
    }
    
    private void reloadZone(Context ctx) {
        try {
            engine.rebuildIndex();
            ctx.json("{\"message\": \"Zone index rebuilt successfully\"}");
            logger.info("Zone index rebuilt via API");
        } catch (Exception e) {
            logger.error("Failed to rebuild zone index", e);
            ctx.status(500).json("{\"error\": \"Failed to rebuild zone index: " + e.getMessage() + "\"}");
        }
    }
    
    private void reloadAll(Context ctx) {
        try {
            zoneStore.loadAll();
            engine.rebuildIndex();
            ctx.json("{\"message\": \"All zones reloaded successfully\"}");
            logger.info("All zones reloaded via API");
        } catch (Exception e) {
            logger.error("Failed to reload zones", e);
            ctx.status(500).json("{\"error\": \"Failed to reload zones: " + e.getMessage() + "\"}");
        }
    }
    
    public void stop() {
        if (app != null) {
            app.stop();
        }
    }
}
