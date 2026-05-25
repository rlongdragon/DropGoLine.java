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
    private final RoomRegistry roomRegistry;
    private final ObjectMapper objectMapper;

    public SignalingWebSocketHandler(PeerRegistry peerRegistry, RoomRegistry roomRegistry, ObjectMapper objectMapper) {
        this.peerRegistry = peerRegistry;
        this.roomRegistry = roomRegistry;
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
        if ("create-room".equals(signal.type())) {
            createRoom(session, signal);
            return;
        }
        if ("join-room".equals(signal.type())) {
            joinRoom(session, signal);
            return;
        }

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
        peerRegistry.peerId(session).ifPresent(roomRegistry::removePeer);
        peerRegistry.unregister(session);
    }

    private void createRoom(WebSocketSession session, SignalMessage signal) throws Exception {
        String roomId = roomId(signal);
        String peerId = peerRegistry.peerId(session).orElse(signal.from());
        if (roomId.isBlank()) {
            sendError(session, "Missing roomId");
            return;
        }
        if (!roomRegistry.create(roomId, peerId)) {
            sendError(session, "Room already exists: " + roomId);
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("roomId", roomId);
        payload.put("peerId", peerId);
        sendSignal(session, "room-created", payload);
    }

    private void joinRoom(WebSocketSession session, SignalMessage signal) throws Exception {
        String roomId = roomId(signal);
        String joinerPeerId = peerRegistry.peerId(session).orElse(signal.from());
        Optional<String> hostPeerId = roomRegistry.host(roomId);
        if (roomId.isBlank() || hostPeerId.isEmpty()) {
            sendError(session, "Room not found: " + roomId);
            return;
        }

        Optional<WebSocketSession> hostSession = peerRegistry.find(hostPeerId.get());
        if (hostSession.isEmpty()) {
            roomRegistry.removePeer(hostPeerId.get());
            sendError(session, "Room host is offline: " + roomId);
            return;
        }

        ObjectNode joinedPayload = objectMapper.createObjectNode();
        joinedPayload.put("roomId", roomId);
        joinedPayload.put("peerId", hostPeerId.get());
        sendSignal(session, "room-joined", joinedPayload);

        ObjectNode peerJoinedPayload = objectMapper.createObjectNode();
        peerJoinedPayload.put("roomId", roomId);
        peerJoinedPayload.put("peerId", joinerPeerId);
        sendSignal(hostSession.get(), "room-peer-joined", peerJoinedPayload);
    }

    private String roomId(SignalMessage signal) {
        if (signal.payload() == null || signal.payload().get("roomId") == null) {
            return "";
        }
        return signal.payload().get("roomId").asText("");
    }

    private void sendSignal(WebSocketSession session, String type, ObjectNode payload) throws Exception {
        SignalMessage signal = new SignalMessage(type, "signal", peerRegistry.peerId(session).orElse(null), payload);
        peerRegistry.send(session, objectMapper.writeValueAsString(signal));
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
