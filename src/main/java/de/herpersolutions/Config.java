package de.herpersolutions;

import java.nio.file.Path;
import java.nio.file.Paths;

import io.github.cdimascio.dotenv.Dotenv;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Config {
    int port;
    int managementPort;
    Path dataDir;
    int maxQueriesPerSecond;
    boolean rateLimitEnabled;

    static Config fromArgs(Dotenv dotenv) {
        int port = Integer.parseInt(dotenv.get("JDNS_PORT", "53"));
        int managementPort = Integer.parseInt(dotenv.get("JDNS_MGMT_PORT", "8080"));
        Path dataDir = Paths.get(dotenv.get("JDNS_DATA_DIR", ".data"));
        int maxQueriesPerSecond = Integer.parseInt(dotenv.get("JDNS_MAX_QPS", "100"));
        boolean rateLimitEnabled = Boolean.parseBoolean(dotenv.get("JDNS_RATE_LIMIT", "true"));
        return new Config(port, managementPort, dataDir, maxQueriesPerSecond, rateLimitEnabled);
    }
}
