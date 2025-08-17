package de.herpersolutions.security;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple rate limiter to prevent DNS amplification attacks
 */
public class RateLimiter {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);
    
    private final ConcurrentMap<String, ClientMetrics> clientStats = new ConcurrentHashMap<>();
    private final int maxQueriesPerSecond;
    private final int windowSizeMs;
    
    public RateLimiter(int maxQueriesPerSecond, int windowSizeMs) {
        this.maxQueriesPerSecond = maxQueriesPerSecond;
        this.windowSizeMs = windowSizeMs;
        
        // Cleanup thread to remove old entries
        Thread cleanup = new Thread(this::cleanupOldEntries);
        cleanup.setDaemon(true);
        cleanup.start();
    }
    
    public boolean isAllowed(InetAddress clientIp) {
        String key = clientIp.getHostAddress();
        long now = System.currentTimeMillis();
        
        ClientMetrics metrics = clientStats.computeIfAbsent(key, k -> new ClientMetrics());
        
        synchronized (metrics) {
            // Reset window if needed
            if (now - metrics.windowStart > windowSizeMs) {
                metrics.windowStart = now;
                metrics.queryCount.set(0);
            }
            
            long currentCount = metrics.queryCount.incrementAndGet();
            metrics.lastSeen = now;
            
            if (currentCount > maxQueriesPerSecond) {
                logger.warn("Rate limit exceeded for client: {} (queries: {})", key, currentCount);
                return false;
            }
            
            return true;
        }
    }
    
    private void cleanupOldEntries() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(60000); // Cleanup every minute
                
                long cutoff = System.currentTimeMillis() - (windowSizeMs * 2);
                clientStats.entrySet().removeIf(entry -> entry.getValue().lastSeen < cutoff);
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    private static class ClientMetrics {
        volatile long windowStart = System.currentTimeMillis();
        volatile long lastSeen = System.currentTimeMillis();
        final AtomicLong queryCount = new AtomicLong(0);
    }
}
