package p2p.quic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class QuicCertificateFilesTest {
    @Test
    void generatesDefaultDemoCertificateFilesWithoutOpenSsl() throws Exception {
        assumeTrue(System.getenv("QUIC_CERT_PATH") == null || System.getenv("QUIC_CERT_PATH").isBlank());
        assumeTrue(System.getenv("QUIC_KEY_PATH") == null || System.getenv("QUIC_KEY_PATH").isBlank());

        Path certPath = Path.of("target/dev-certs/quic-cert.pem");
        Path keyPath = Path.of("target/dev-certs/quic-key.pem");
        Files.deleteIfExists(certPath);
        Files.deleteIfExists(keyPath);

        try (InputStream certificate = QuicCertificateFiles.certificate();
             InputStream privateKey = QuicCertificateFiles.privateKey()) {
            assertTrue(certificate.read() >= 0);
            assertTrue(privateKey.read() >= 0);
        }

        assertEquals("-----BEGIN CERTIFICATE-----", Files.readAllLines(certPath).get(0));
        assertEquals("-----BEGIN RSA PRIVATE KEY-----", Files.readAllLines(keyPath).get(0));
    }
}
