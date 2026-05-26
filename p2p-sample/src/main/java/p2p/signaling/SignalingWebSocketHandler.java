package p2p.signaling;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
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
        if ("room-relay".equals(signal.type())) {
            relayRoomMessage(session, signal);
            return;
        }
        if ("room-private-relay".equals(signal.type())) {
            relayPrivateRoomMessage(session, signal);
            return;
        }
        if (signal.type() != null && signal.type().startsWith("room-file-")) {
            relayRoomFileMessage(session, signal);
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
        peerRegistry.peerId(session).ifPresent(peerId -> {
            RoomRegistry.Removal removal = roomRegistry.removePeer(peerId);
            notifyPeerLeft(removal);
        });
        peerRegistry.unregister(session);
    }

    private void createRoom(WebSocketSession session, SignalMessage signal) throws Exception {
        String requestedRoomId = roomId(signal);
        String peerId = peerRegistry.peerId(session).orElse(signal.from());
        String roomId;
        try {
            roomId = roomRegistry.create(requestedRoomId, peerId);
        } catch (IllegalArgumentException e) {
            sendError(session, e.getMessage());
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
        List<String> existingPeers;
        try {
            existingPeers = roomRegistry.join(roomId, joinerPeerId);
        } catch (IllegalArgumentException e) {
            sendError(session, e.getMessage());
            return;
        }

        ObjectNode joinedPayload = objectMapper.createObjectNode();
        joinedPayload.put("roomId", roomId);
        joinedPayload.putPOJO("peers", existingPeers);
        sendSignal(session, "room-joined", joinedPayload);

        for (String existingPeer : existingPeers) {
            Optional<WebSocketSession> target = peerRegistry.find(existingPeer);
            if (target.isEmpty()) {
                roomRegistry.removePeer(existingPeer);
                continue;
            }
            ObjectNode peerJoinedPayload = objectMapper.createObjectNode();
            peerJoinedPayload.put("roomId", roomId);
            peerJoinedPayload.put("peerId", joinerPeerId);
            sendSignal(target.get(), "room-peer-joined", peerJoinedPayload);
        }
    }

    private void relayRoomMessage(WebSocketSession session, SignalMessage signal) throws Exception {
        String from = peerRegistry.peerId(session).orElse(signal.from());
        String roomId = roomRegistry.roomOf(from);
        if (roomId == null) {
            sendError(session, "Peer is not in a room");
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("roomId", roomId);
        JsonNode message = signal.payload() != null && signal.payload().has("message")
                ? signal.payload().get("message")
                : objectMapper.nullNode();
        payload.set("message", message);

        for (String peerId : roomRegistry.peersInSameRoom(from)) {
            Optional<WebSocketSession> target = peerRegistry.find(peerId);
            if (target.isPresent()) {
                sendSignal(target.get(), "room-relay", payload, from);
            } else {
                roomRegistry.removePeer(peerId);
            }
        }
    }

    private void relayPrivateRoomMessage(WebSocketSession session, SignalMessage signal) throws Exception {
        String from = peerRegistry.peerId(session).orElse(signal.from());
        String roomId = roomRegistry.roomOf(from);
        if (roomId == null) {
            sendError(session, "Peer is not in a room");
            return;
        }

        JsonNode payloadNode = signal.payload();
        String targetPeerId = payloadNode == null ? "" : payloadNode.path("to").asText("");
        if (targetPeerId.isBlank()) {
            sendError(session, "Missing private message target");
            return;
        }
        if (!roomRegistry.peersInSameRoom(from).contains(targetPeerId)) {
            sendError(session, "Peer is not in your room: " + targetPeerId);
            return;
        }

        Optional<WebSocketSession> target = peerRegistry.find(targetPeerId);
        if (target.isEmpty()) {
            roomRegistry.removePeer(targetPeerId);
            sendError(session, "Target peer is offline: " + targetPeerId);
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("roomId", roomId);
        JsonNode message = payloadNode != null && payloadNode.has("message")
                ? payloadNode.get("message")
                : objectMapper.nullNode();
        payload.set("message", message);
        sendSignal(target.get(), "room-private-relay", payload, from);
    }

    private void relayRoomFileMessage(WebSocketSession session, SignalMessage signal) throws Exception {
        String from = peerRegistry.peerId(session).orElse(signal.from());
        String roomId = roomRegistry.roomOf(from);
        if (roomId == null) {
            sendError(session, "Peer is not in a room");
            return;
        }

        JsonNode payloadNode = signal.payload();
        String targetPeerId = payloadNode == null ? "" : payloadNode.path("to").asText("");
        if (targetPeerId.isBlank()) {
            if (!"room-file-offer".equals(signal.type())) {
                sendError(session, "Missing file relay target");
                return;
            }
            for (String peerId : roomRegistry.peersInSameRoom(from)) {
                forwardRoomFileMessage(peerId, signal, from, roomId, session);
            }
            return;
        }

        if (!roomRegistry.peersInSameRoom(from).contains(targetPeerId)) {
            sendError(session, "Peer is not in your room: " + targetPeerId);
            return;
        }
        forwardRoomFileMessage(targetPeerId, signal, from, roomId, session);
    }

    private void forwardRoomFileMessage(String targetPeerId, SignalMessage signal, String from, String roomId,
                                        WebSocketSession sourceSession) throws Exception {
        Optional<WebSocketSession> target = peerRegistry.find(targetPeerId);
        if (target.isEmpty()) {
            roomRegistry.removePeer(targetPeerId);
            sendError(sourceSession, "Target peer is offline: " + targetPeerId);
            return;
        }

        ObjectNode payload = signal.payload() == null || signal.payload().isNull()
                ? objectMapper.createObjectNode()
                : signal.payload().deepCopy();
        payload.put("roomId", roomId);
        payload.remove("to");
        sendSignal(target.get(), signal.type(), payload, from);
    }

    private String roomId(SignalMessage signal) {
        if (signal.payload() == null || signal.payload().get("roomId") == null) {
            return "";
        }
        return signal.payload().get("roomId").asText("");
    }

    private void sendSignal(WebSocketSession session, String type, ObjectNode payload) throws Exception {
        sendSignal(session, type, payload, "signal");
    }

    private void sendSignal(WebSocketSession session, String type, ObjectNode payload, String from) throws Exception {
        SignalMessage signal = new SignalMessage(type, from, peerRegistry.peerId(session).orElse(null), payload);
        peerRegistry.send(session, objectMapper.writeValueAsString(signal));
    }

    private void notifyPeerLeft(RoomRegistry.Removal removal) {
        if (removal.roomId() == null) {
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("roomId", removal.roomId());
        payload.put("peerId", removal.peerId());
        for (String remainingPeer : removal.remainingPeers()) {
            peerRegistry.find(remainingPeer).ifPresent(session -> {
                try {
                    sendSignal(session, "room-peer-left", payload);
                } catch (Exception ignored) {
                    peerRegistry.unregister(session);
                    roomRegistry.removePeer(remainingPeer);
                }
            });
        }
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
        payload.put("message", error);
        sendSignal(session, "error", payload);
    }
}
