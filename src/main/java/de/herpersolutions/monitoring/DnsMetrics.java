package de.herpersolutions.monitoring;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DNS query metrics and statistics
 */
public class DnsMetrics {
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong successfulQueries = new AtomicLong(0);
    private final AtomicLong failedQueries = new AtomicLong(0);
    private final AtomicLong nxdomainQueries = new AtomicLong(0);
    private final AtomicLong noDataQueries = new AtomicLong(0);
    private final AtomicLong rateLimitedQueries = new AtomicLong(0);
    private final AtomicLong udpQueries = new AtomicLong(0);
    private final AtomicLong tcpQueries = new AtomicLong(0);
    
    private volatile long startTime = System.currentTimeMillis();
    
    public void recordQuery() {
        totalQueries.incrementAndGet();
    }
    
    public void recordSuccess() {
        successfulQueries.incrementAndGet();
    }
    
    public void recordFailure() {
        failedQueries.incrementAndGet();
    }
    
    public void recordNxdomain() {
        nxdomainQueries.incrementAndGet();
    }
    
    public void recordNoData() {
        noDataQueries.incrementAndGet();
    }
    
    public void recordRateLimited() {
        rateLimitedQueries.incrementAndGet();
    }
    
    public void recordUdp() {
        udpQueries.incrementAndGet();
    }
    
    public void recordTcp() {
        tcpQueries.incrementAndGet();
    }
    
    public String getStatsJson() {
        long uptime = System.currentTimeMillis() - startTime;
        long total = totalQueries.get();
        double qps = total > 0 ? (double) total / (uptime / 1000.0) : 0.0;
        
        return String.format("""
            {
              "uptime_ms": %d,
              "queries_per_second": %.2f,
              "total_queries": %d,
              "successful_queries": %d,
              "failed_queries": %d,
              "nxdomain_queries": %d,
              "nodata_queries": %d,
              "rate_limited_queries": %d,
              "udp_queries": %d,
              "tcp_queries": %d
            }""",
            uptime, qps, total, successfulQueries.get(), failedQueries.get(),
            nxdomainQueries.get(), noDataQueries.get(), rateLimitedQueries.get(),
            udpQueries.get(), tcpQueries.get());
    }
    
    public void reset() {
        totalQueries.set(0);
        successfulQueries.set(0);
        failedQueries.set(0);
        nxdomainQueries.set(0);
        noDataQueries.set(0);
        rateLimitedQueries.set(0);
        udpQueries.set(0);
        tcpQueries.set(0);
        startTime = System.currentTimeMillis();
    }
}
