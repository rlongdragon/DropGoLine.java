package p2p.quic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class QuicCertificateFiles {
    private static final String DEFAULT_CERT_PATH = "target/dev-certs/quic-cert.pem";
    private static final String DEFAULT_KEY_PATH = "target/dev-certs/quic-key.pem";

    private QuicCertificateFiles() {
    }

    public static InputStream certificate() throws IOException {
        return open("QUIC_CERT_PATH", DEFAULT_CERT_PATH, "certificate");
    }

    public static InputStream privateKey() throws IOException {
        return open("QUIC_KEY_PATH", DEFAULT_KEY_PATH, "private key");
    }

    private static InputStream open(String envName, String defaultPath, String label) throws IOException {
        Path path = Path.of(env(envName, defaultPath));
        if (!Files.isRegularFile(path)) {
            throw new IOException("missing QUIC " + label + " file: " + path
                    + ". Generate local demo files with: mkdir -p target/dev-certs && "
                    + "openssl req -x509 -newkey rsa:2048 -nodes -days 30 "
                    + "-keyout target/dev-certs/quic-key.pem "
                    + "-out target/dev-certs/quic-cert.pem -subj \"/CN=localhost\"");
        }
        return Files.newInputStream(path);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
