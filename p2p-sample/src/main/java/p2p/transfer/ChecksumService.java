package p2p.transfer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

@Service
public class ChecksumService {
    public String sha256(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public boolean matches(byte[] payload, String expectedSha256) {
        return sha256(payload).equalsIgnoreCase(expectedSha256);
    }
}
