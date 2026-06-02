package p2p.quic;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public final class QuicCertificateFiles {
    private static final String DEFAULT_CERT_PATH = "target/dev-certs/quic-cert.pem";
    private static final String DEFAULT_KEY_PATH = "target/dev-certs/quic-key.pem";
    private static final Object INIT_LOCK = new Object();

    private QuicCertificateFiles() {
    }

    public static InputStream certificate() throws IOException {
        ensureDefaultCertificateFiles();
        return open("QUIC_CERT_PATH", DEFAULT_CERT_PATH, "certificate");
    }

    public static InputStream privateKey() throws IOException {
        ensureDefaultCertificateFiles();
        return open("QUIC_KEY_PATH", DEFAULT_KEY_PATH, "private key");
    }

    private static InputStream open(String envName, String defaultPath, String label) throws IOException {
        Path path = Path.of(env(envName, defaultPath));
        if (!Files.isRegularFile(path)) {
            throw new IOException("missing QUIC " + label + " file: " + path);
        }
        return Files.newInputStream(path);
    }

    private static void ensureDefaultCertificateFiles() throws IOException {
        if (isConfigured("QUIC_CERT_PATH") || isConfigured("QUIC_KEY_PATH")) {
            return;
        }
        Path certPath = Path.of(DEFAULT_CERT_PATH);
        Path keyPath = Path.of(DEFAULT_KEY_PATH);
        if (Files.isRegularFile(certPath) && Files.isRegularFile(keyPath)) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (Files.isRegularFile(certPath) && Files.isRegularFile(keyPath)) {
                return;
            }
            generateDemoCertificate(certPath, keyPath);
        }
    }

    private static void generateDemoCertificate(Path certPath, Path keyPath) throws IOException {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, new SecureRandom());
            KeyPair keyPair = generator.generateKeyPair();

            Instant now = Instant.now();
            X500Name subject = new X500Name("CN=localhost");
            BigInteger serial = new BigInteger(160, new SecureRandom()).abs();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    subject,
                    serial,
                    Date.from(now.minus(1, ChronoUnit.DAYS)),
                    Date.from(now.plus(30, ChronoUnit.DAYS)),
                    subject,
                    keyPair.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
            X509Certificate certificate = new JcaX509CertificateConverter()
                    .getCertificate(builder.build(signer));

            Files.createDirectories(certPath.getParent());
            Files.createDirectories(keyPath.getParent());
            Files.writeString(certPath, pem(certificate));
            Files.writeString(keyPath, pem(keyPair.getPrivate()));
        } catch (Exception e) {
            throw new IOException("failed to generate local QUIC demo certificate", e);
        }
    }

    private static String pem(Object object) throws IOException {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(object);
        }
        return writer.toString();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean isConfigured(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }
}
