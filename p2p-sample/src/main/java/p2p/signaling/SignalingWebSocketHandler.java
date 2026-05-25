package p2p.signaling;

import java.net.URI;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class SignalingWebSocketHandler extends TextWebSocketHandler {
    private final PeerRegistry peerRegistry;
    private final ObjectMapper objectMapper;

    public SignalingWebSocketHandler(PeerRegistry peerRegistry, ObjectMapper objectMapper) {
        this.peerRegistry = peerRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String peerId = peerIdFrom(session.getUri())
                .orElseThrow(() -> new IllegalArgumentException("Missing peerId query parameter"));
        peerRegistry.register(peerId, session);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "registered");
        payload.put("peerId", peerId);
        peerRegistry.send(session, objectMapper.writeValueAsString(payload));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        SignalMessage signal = objectMapper.readValue(message.getPayload(), SignalMessage.class);
        if (signal.to() == null || signal.to().isBlank()) {
            sendError(session, "Missing target peer");
            return;
        }

        String from = peerRegistry.peerId(session).orElse(signal.from());
        SignalMessage forwarded = new SignalMessage(signal.type(), from, signal.to(), signal.payload());

        Optional<WebSocketSession> target = peerRegistry.find(signal.to());
        if (target.isEmpty()) {
            sendError(session, "Target peer is offline: " + signal.to());
            return;
        }

        try {
            peerRegistry.send(target.get(), objectMapper.writeValueAsString(forwarded));
        } catch (Exception e) {
            peerRegistry.unregister(target.get());
            sendError(session, "Target peer is offline: " + signal.to());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        peerRegistry.unregister(session);
    }

    private Optional<String> peerIdFrom(URI uri) {
        if (uri == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("peerId"))
                .filter(value -> !value.isBlank());
    }

    private void sendError(WebSocketSession session, String error) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "error");
        payload.put("message", error);
        peerRegistry.send(session, objectMapper.writeValueAsString(payload));
    }
}
