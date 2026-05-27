package p2p.ice;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.yaml.snakeyaml.Yaml;

final class Ice4jConfigBridge {
    private static final AtomicBoolean APPLIED = new AtomicBoolean(false);

    private Ice4jConfigBridge() {
    }

    static void applyFromApplicationYaml() {
        if (!APPLIED.compareAndSet(false, true)) {
            return;
        }

        try (InputStream input = Ice4jConfigBridge.class.getResourceAsStream("/application.yml")) {
            if (input == null) {
                return;
            }

            Object loaded = new Yaml().load(input);
            if (!(loaded instanceof Map<?, ?> root)) {
                return;
            }

            Object ice4j = root.get("ice4j");
            if (ice4j instanceof Map<?, ?> values) {
                applyMap("ice4j", values);
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            // If the optional YAML bridge fails, ice4j falls back to its built-in reference.conf.
        }
    }

    private static void applyMap(String prefix, Map<?, ?> values) {
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key) || entry.getValue() == null) {
                continue;
            }

            String property = prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                applyMap(property, nested);
            } else if (System.getProperty(property) == null) {
                System.setProperty(property, String.valueOf(value));
            }
        }
    }
}
