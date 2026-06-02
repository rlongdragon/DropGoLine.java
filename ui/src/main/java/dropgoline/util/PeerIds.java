package dropgoline.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class PeerIds {
    public static final String SUFFIX_SEPARATOR = "-[]";

    private PeerIds() {
    }

    public static String canonicalize(String peerId) {
        if (peerId == null) {
            return "";
        }
        String decoded = peerId;
        for (int i = 0; i < 3; i++) {
            String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            if (next.equals(decoded)) {
                return next;
            }
            decoded = next;
        }
        return decoded;
    }

    public static String displayName(String peerId) {
        String decoded = canonicalize(peerId);
        int separator = decoded.lastIndexOf(SUFFIX_SEPARATOR);
        if (separator > 0) {
            return decoded.substring(0, separator);
        }
        int legacySeparator = decoded.lastIndexOf('#');
        if (legacySeparator > 0) {
            return decoded.substring(0, legacySeparator);
        }
        return decoded;
    }
}
