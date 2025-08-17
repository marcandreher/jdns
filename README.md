# JDNS - Java DNS Server

A modern, feature-rich authoritative DNS server written in Java.

## Features

### Core DNS Functionality ✅
- **Authoritative DNS server** with UDP/TCP support (RFC 1035 compliant)
- **Multiple record types**: A, AAAA, CNAME, MX, TXT, NS, SOA
- **JSON-based zone configuration** for easy management
- **Proper CNAME chain handling**
- **Additional records (glue)** for NS/MX targets
- **UDP truncation handling** (sets TC flag for responses >512 bytes)
- **Longest-suffix zone matching**

### Security & Performance 🔒
- **Rate limiting** to prevent DNS amplification attacks
- **Query metrics and monitoring**
- **Graceful shutdown** with data persistence
- **Concurrent request handling**
- **Input validation** and error handling

### Management & Monitoring 📊
- **REST API** for server management and monitoring
- **Real-time metrics** (QPS, success/failure rates, protocol stats)
- **Health check endpoints**
- **Zone reload** without server restart
- **Structured logging** with SLF4J/Logback

## Quick Start

### 1. Configuration
Copy `.env.example` to `.env` and adjust settings:
```bash
cp .env.example .env
```

### 2. Build and Run
```bash
mvn clean package
java -jar target/jdns-1.0.jar
```

### 3. Test DNS Resolution
```bash
dig @localhost -p 53 example.local
```

### 4. Access Management API
```bash
# Health check
curl http://localhost:8080/health

# View metrics
curl http://localhost:8080/metrics

# List zones
curl http://localhost:8080/zones

# Reload zones
curl -X POST http://localhost:8080/reload
```

## Configuration

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `JDNS_PORT` | 53 | DNS server port (UDP/TCP) |
| `JDNS_MGMT_PORT` | 8080 | Management API port |
| `JDNS_DATA_DIR` | .data | Zone files directory |
| `JDNS_MAX_QPS` | 100 | Max queries per second per IP |
| `JDNS_RATE_LIMIT` | true | Enable rate limiting |

## Zone Configuration

Zones are stored as JSON files in the data directory (e.g., `.data/example_local_.zone.json`):

```json
{
  "origin": "example.local.",
  "defaultTtl": 300,
  "admin": "hostmaster.example.local.",
  "ns": ["ns1.example.local."],
  "serial": 2025081701,
  "records": [
    {
      "name": "@",
      "type": "A",
      "ttl": 60,
      "data": "10.10.10.10"
    },
    {
      "name": "www",
      "type": "CNAME",
      "data": "example.local."
    },
    {
      "name": "@",
      "type": "MX",
      "priority": 10,
      "data": "mail.example.local."
    }
  ]
}
```

## Management API Endpoints

### Health & Monitoring
- `GET /health` - Server health status
- `GET /metrics` - Query statistics and performance metrics
- `POST /metrics/reset` - Reset metrics counters

### Zone Management  
- `GET /zones` - List all loaded zones
- `GET /zones/{origin}` - Get specific zone configuration
- `POST /zones/{origin}/reload` - Rebuild zone index
- `POST /reload` - Reload all zones from disk

## Logging

Logs are written to:
- **Console**: Colored output for development
- **Files**: `logs/jdns-YYYY-MM-DD.log` (30-day retention)

## Security Features

### Rate Limiting
- Per-IP query rate limiting (default: 100 QPS)
- Configurable time windows
- Automatic cleanup of old client statistics
- Returns REFUSED for rate-limited queries

### Input Validation
- DNS message parsing validation
- Zone data validation during loading
- Proper error handling and logging

## Recommended Improvements for Production

### High Priority 🚨
1. **DNSSEC Support** - Digital signature validation
2. **Access Control Lists** - IP-based query restrictions  
3. **Zone Transfer (AXFR/IXFR)** - Secondary server support
4. **Query Caching** - Improve performance for repeated queries

### Medium Priority ⚠️
1. **Prometheus Metrics** - Industry-standard monitoring
2. **Configuration Validation** - Startup-time validation
3. **Connection Pooling** - Better TCP handling
4. **Audit Logging** - Security event tracking

### Nice to Have ✨
1. **Web UI** - Graphical zone management
2. **Database Backend** - Replace JSON files with database
3. **Clustering** - Multi-server deployment support
4. **Let's Encrypt Integration** - Automatic certificate management

## Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   DNS Clients   │───▶│   DnsListener    │───▶│ AuthoritativeEngine │
│ (dig, nslookup) │    │  (UDP/TCP:53)    │    │   (Query Logic)     │
└─────────────────┘    └──────────────────┘    └─────────────────┘
                                │                        │
                                ▼                        ▼
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│ Management API  │    │   RateLimiter    │    │   ZoneStore     │
│ (HTTP:8080)     │    │   (Security)     │    │ (JSON Files)    │
└─────────────────┘    └──────────────────┘    └─────────────────┘
        │                        │                        │
        ▼                        ▼                        ▼
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   DnsMetrics    │    │     Logging      │    │  Zone Indexing  │
│ (Monitoring)    │    │   (SLF4J)        │    │   (DNS Tree)    │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

## License

This project is licensed under the MIT License.
