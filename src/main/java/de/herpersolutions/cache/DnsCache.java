package de.herpersolutions.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.xbill.DNS.Message;
import org.xbill.DNS.Name;

/**
 * Simple DNS query cache to improve performance for repeated queries
 */
public class DnsCache {
    private final ConcurrentMap<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
    private final long defaultTtlMs;
    private final int maxEntries;
    
    public DnsCache(long defaultTtlMs, int maxEntries) {
        this.defaultTtlMs = defaultTtlMs;
        this.maxEntries = maxEntries;
        
        // Cleanup thread
        Thread cleanup = new Thread(this::cleanupExpiredEntries);
        cleanup.setDaemon(true);
        cleanup.start();
    }
    
    public Message get(Name qname, int qtype) {
        CacheKey key = new CacheKey(qname.toString(), qtype);
        CacheEntry entry = cache.get(key);
        
        if (entry != null && entry.expiryTime > System.currentTimeMillis()) {
            return entry.response.clone();
        }
        
        // Remove expired entry
        if (entry != null) {
            cache.remove(key);
        }
        
        return null;
    }
    
    public void put(Name qname, int qtype, Message response, long ttlMs) {
        if (cache.size() >= maxEntries) {
            // Simple eviction: remove oldest entries (not LRU but good enough)
            cleanupExpiredEntries();
            if (cache.size() >= maxEntries) {
                return; // Skip caching if still full
            }
        }
        
        CacheKey key = new CacheKey(qname.toString(), qtype);
        long expiryTime = System.currentTimeMillis() + (ttlMs > 0 ? ttlMs : defaultTtlMs);
        cache.put(key, new CacheEntry(response.clone(), expiryTime));
    }
    
    public void invalidate(Name zone) {
        String zoneStr = zone.toString();
        cache.entrySet().removeIf(entry -> 
            entry.getKey().qname.endsWith(zoneStr) || entry.getKey().qname.equals(zoneStr));
    }
    
    public void clear() {
        cache.clear();
    }
    
    public int size() {
        return cache.size();
    }
    
    private void cleanupExpiredEntries() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expiryTime <= now);
    }
    
    private static class CacheKey {
        final String qname;
        final int qtype;
        
        CacheKey(String qname, int qtype) {
            this.qname = qname.toLowerCase();
            this.qtype = qtype;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof CacheKey)) return false;
            CacheKey other = (CacheKey) obj;
            return qtype == other.qtype && qname.equals(other.qname);
        }
        
        @Override
        public int hashCode() {
            return qname.hashCode() * 31 + qtype;
        }
    }
    
    private static class CacheEntry {
        final Message response;
        final long expiryTime;
        
        CacheEntry(Message response, long expiryTime) {
            this.response = response;
            this.expiryTime = expiryTime;
        }
    }
}
