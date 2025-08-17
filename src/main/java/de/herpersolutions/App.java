package de.herpersolutions;

import java.io.IOException;
import java.net.UnknownHostException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.TextParseException;

import de.herpersolutions.Zones.ZoneStore;
import de.herpersolutions.engine.AuthoritativeEngine;
import de.herpersolutions.engine.DnsListener;
import io.github.cdimascio.dotenv.Dotenv;

/**
 * JDNS Standalone Server
 *
 */
public class App 
{
    private static Logger logger = LoggerFactory.getLogger(App.class);
    private static Dotenv dotenv;
    
    public static void main( String[] args )
    {
        logger.info( "JDNS Standalone Server" );
        try {
            dotenv = Dotenv.load();
        } catch (Exception e) {
            logger.error("Failed to load .env file", e);
        }
            
        Config cfg = Config.fromArgs(dotenv);
        ZoneStore store = new ZoneStore(cfg.dataDir);

        try {
            store.loadAll();
        } catch (IOException e) {
            logger.error("Failed to load zone data", e);
            return;
        }

        AuthoritativeEngine engine;
        try {
            engine = new AuthoritativeEngine(store);
        } catch (TextParseException | UnknownHostException e) {
            logger.error("Failed to create AuthoritativeEngine", e);
            return;
        }
        DnsListener listener = new DnsListener(cfg.port, engine);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down DNS server...");
            listener.close();
            try { store.saveAll(); } catch (Exception e) { logger.error("Failed to save zone data", e); }
        }));

        logger.info("JDNS Server listening on UDP/TCP {}, zones={}, dataDir={}",
                cfg.port, store.zones.size(), cfg.dataDir.toAbsolutePath());

        // Keep running forever
        try {
            listener.run();
        } catch (IOException e) {
            logger.error("Failed to run DNSListener", e);
        }
    }
}
