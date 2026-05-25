package p2p.signaling;

import com.fasterxml.jackson.databind.JsonNode;

public record SignalMessage(
        String type,
        String from,
        String to,
        JsonNode payload
) {
}
