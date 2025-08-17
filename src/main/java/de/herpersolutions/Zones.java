package de.herpersolutions;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.Name;
import org.xbill.DNS.TextParseException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

public class Zones {
    public static Logger logger = LoggerFactory.getLogger(Zones.class);

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JsonZone {
        String origin; // e.g. "example.com."
        long defaultTtl = 300; // seconds
        String admin = "hostmaster.example.com."; // RNAME in SOA
        List<String> ns = new ArrayList<>(); // e.g. ["ns1.example.com.", "ns2.example.com."]
        long serial = 1;
        long refresh = 3600, retry = 900, expire = 1209600, minimum = 300;
        List<JsonRecord> records = new ArrayList<>();

        public Name originName() throws TextParseException {
            return Name.fromString(origin, Name.root);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JsonRecord {
        String name; // relative or FQDN, e.g. "@", "www", "api.example.com."
        String type; // A, AAAA, CNAME, MX, TXT, NS, SOA etc. (SOA/NS usually set from zone)
        long ttl = -1; // if -1, use zone default
        String data; // For A/AAAA/CNAME/TXT/NS: the rdata string
        Integer priority; // MX preference (if type == MX)
    }

    /* ------------------------ STORE ------------------------ */
    public static class ZoneStore {
        final Path dir;
        final Gson gson;
        public final Map<String, JsonZone> zones = new ConcurrentHashMap<>(); // key = origin (lowercase)

        ZoneStore(Path dir) {
            this.dir = dir;
            this.gson = new GsonBuilder().setPrettyPrinting().create();
        }

        void loadAll() throws IOException {
            if (!Files.exists(dir))
                Files.createDirectories(dir);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.zone.json")) {
                for (Path p : ds) {
                    try (Reader r = Files.newBufferedReader(p)) {
                        JsonZone z = gson.fromJson(r, JsonZone.class);
                        if (z != null && z.origin != null) {
                            zones.put(normalize(z.origin), z);
                        }
                    } catch (Exception e) {
                        logger.error("Failed to load zone file: " + p + ": " + e.getMessage());
                    }
                }
            }
            if (zones.isEmpty()) {
                // Bootstrap a demo zone if none exists
                JsonZone demo = new JsonZone();
                demo.origin = "example.local.";
                demo.admin = "hostmaster.example.local.";
                demo.ns = Arrays.asList("ns1.example.local.");
                demo.defaultTtl = 300;
                demo.serial = Instant.now().getEpochSecond();
                demo.records.add(new JsonRecord("@", "A", 60, "10.10.10.10", null));
                demo.records.add(new JsonRecord("www", "CNAME", 300, "example.local.", null));
                zones.put(normalize(demo.origin), demo);
            }
        }

        void saveAll() throws IOException {
            for (JsonZone z : zones.values())
                saveZone(z);
        }

        synchronized void saveZone(JsonZone z) throws IOException {
            Path p = dir.resolve(z.origin.replace('.', '_') + ".zone.json");
            try (BufferedWriter w = Files.newBufferedWriter(p)) {
                gson.toJson(z, w);
            }
        }

        public JsonZone getZoneByName(Name qname) {
            // Longest-suffix match
            String name = qname.toString(true);
            if (!name.endsWith(".")) name = name + ".";
            String best = null;
            for (String origin : zones.keySet()) {
                if (name.endsWith(origin)) {
                    if (best == null || origin.length() > best.length())
                        best = origin;
                }
            }
            return best != null ? zones.get(best) : null;
        }

        static String normalize(String n) {
            return n.endsWith(".") ? n.toLowerCase() : (n + ".").toLowerCase();
        }
    }
}
