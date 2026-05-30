package p2p;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class LoggingConfig {
    @PostConstruct
    public void configure() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (Handler handler : root.getHandlers()) {
            handler.setLevel(Level.INFO);
        }
        quiet("org.jitsi");
        quiet("org.jitsi.utils");
        quiet("org.ice4j");
    }

    private void quiet(String name) {
        Logger logger = Logger.getLogger(name);
        logger.setLevel(Level.WARNING);
    }
}
