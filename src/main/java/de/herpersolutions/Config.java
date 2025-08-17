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
    Path dataDir;

    static Config fromArgs(Dotenv dotenv) {
        int port = Integer.parseInt(dotenv.get("JDNS_PORT", "53"));
        Path dataDir = Paths.get(dotenv.get("JDNS_DATA_DIR", ".data"));
        return new Config(port, dataDir);
    }
}
