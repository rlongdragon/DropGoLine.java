package p2p.signaling;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
    private final GroupRegistry groupRegistry;
    private final ObjectMapper objectMapper;

    public SignalingWebSocketHandler(PeerRegistry peerRegistry, GroupRegistry groupRegistry, ObjectMapper objectMapper) {
        this.peerRegistry = peerRegistry;
        this.groupRegistry = groupRegistry;
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
        if ("create-group".equals(signal.type()) || "create-room".equals(signal.type())) {
            createGroup(session, signal);
            return;
        }
        if ("join-group".equals(signal.type()) || "join-room".equals(signal.type())) {
            joinGroup(session, signal);
            return;
        }
        if ("group-relay".equals(signal.type()) || "room-relay".equals(signal.type())) {
            relayGroupMessage(session, signal);
            return;
        }
        if ("group-private-relay".equals(signal.type()) || "room-private-relay".equals(signal.type())) {
            relayPrivateGroupMessage(session, signal);
            return;
        }
        if (signal.type() != null
                && (signal.type().startsWith("group-file-") || signal.type().startsWith("room-file-"))) {
            relayGroupFileMessage(session, signal);
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
            GroupRegistry.Removal removal = groupRegistry.removePeer(peerId);
            notifyPeerLeft(removal);
        });
        peerRegistry.unregister(session);
    }

    private void createGroup(WebSocketSession session, SignalMessage signal) throws Exception {
        String requestedGroupId = groupId(signal);
        String peerId = peerRegistry.peerId(session).orElse(signal.from());
        String groupId;
        try {
            groupId = groupRegistry.create(requestedGroupId, peerId);
        } catch (IllegalArgumentException e) {
            sendError(session, e.getMessage());
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("groupId", groupId);
        payload.put("roomId", groupId);
        payload.put("peerId", peerId);
        sendSignal(session, replyType(signal.type(), "group-created", "room-created"), payload);
    }

    private void joinGroup(WebSocketSession session, SignalMessage signal) throws Exception {
        String groupId = groupId(signal);
        String joinerPeerId = peerRegistry.peerId(session).orElse(signal.from());
        List<String> existingPeers;
        try {
            existingPeers = groupRegistry.join(groupId, joinerPeerId);
        } catch (IllegalArgumentException e) {
            sendError(session, e.getMessage());
            return;
        }

        ObjectNode joinedPayload = objectMapper.createObjectNode();
        joinedPayload.put("groupId", groupId);
        joinedPayload.put("roomId", groupId);
        joinedPayload.putPOJO("peers", existingPeers);
        sendSignal(session, replyType(signal.type(), "group-joined", "room-joined"), joinedPayload);

        for (String existingPeer : existingPeers) {
            Optional<WebSocketSession> target = peerRegistry.find(existingPeer);
            if (target.isEmpty()) {
                groupRegistry.removePeer(existingPeer);
                continue;
            }
            ObjectNode peerJoinedPayload = objectMapper.createObjectNode();
            peerJoinedPayload.put("groupId", groupId);
            peerJoinedPayload.put("roomId", groupId);
            peerJoinedPayload.put("peerId", joinerPeerId);
            sendSignal(target.get(), "group-peer-joined", peerJoinedPayload);
        }
    }

    private void relayGroupMessage(WebSocketSession session, SignalMessage signal) throws Exception {
        String from = peerRegistry.peerId(session).orElse(signal.from());
        String groupId = groupRegistry.groupOf(from);
        if (groupId == null) {
            sendError(session, "Peer is not in a group");
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("groupId", groupId);
        payload.put("roomId", groupId);
        JsonNode message = signal.payload() != null && signal.payload().has("message")
                ? signal.payload().get("message")
                : objectMapper.nullNode();
        payload.set("message", message);

        for (String peerId : groupRegistry.peersInSameGroup(from)) {
            Optional<WebSocketSession> target = peerRegistry.find(peerId);
            if (target.isPresent()) {
                sendSignal(target.get(), signal.type().startsWith("room-") ? "room-relay" : "group-relay", payload, from);
            } else {
                groupRegistry.removePeer(peerId);
            }
        }
    }

    private void relayPrivateGroupMessage(WebSocketSession session, SignalMessage signal) throws Exception {
        String from = peerRegistry.peerId(session).orElse(signal.from());
        String groupId = groupRegistry.groupOf(from);
        if (groupId == null) {
            sendError(session, "Peer is not in a group");
            return;
        }

        JsonNode payloadNode = signal.payload();
        String targetPeerId = payloadNode == null ? "" : payloadNode.path("to").asText("");
        if (targetPeerId.isBlank()) {
            sendError(session, "Missing private message target");
            return;
        }
        if (!groupRegistry.peersInSameGroup(from).contains(targetPeerId)) {
            sendError(session, "Peer is not in your group: " + targetPeerId);
            return;
        }

        Optional<WebSocketSession> target = peerRegistry.find(targetPeerId);
        if (target.isEmpty()) {
            groupRegistry.removePeer(targetPeerId);
            sendError(session, "Target peer is offline: " + targetPeerId);
            return;
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("groupId", groupId);
        payload.put("roomId", groupId);
        JsonNode message = payloadNode != null && payloadNode.has("message")
                ? payloadNode.get("message")
                : objectMapper.nullNode();
        payload.set("message", message);
        sendSignal(target.get(), signal.type().startsWith("room-") ? "room-private-relay" : "group-private-relay",
                payload, from);
    }

    private void relayGroupFileMessage(WebSocketSession session, SignalMessage signal) throws Exception {
        String from = peerRegistry.peerId(session).orElse(signal.from());
        String groupId = groupRegistry.groupOf(from);
        if (groupId == null) {
            sendError(session, "Peer is not in a group");
            return;
        }

        JsonNode payloadNode = signal.payload();
        String targetPeerId = payloadNode == null ? "" : payloadNode.path("to").asText("");
        if (targetPeerId.isBlank()) {
            if (!"group-file-offer".equals(signal.type()) && !"room-file-offer".equals(signal.type())) {
                sendError(session, "Missing file relay target");
                return;
            }
            for (String peerId : groupRegistry.peersInSameGroup(from)) {
                forwardGroupFileMessage(peerId, signal, from, groupId, session);
            }
            return;
        }

        if (!groupRegistry.peersInSameGroup(from).contains(targetPeerId)) {
            sendError(session, "Peer is not in your group: " + targetPeerId);
            return;
        }
        forwardGroupFileMessage(targetPeerId, signal, from, groupId, session);
    }

    private void forwardGroupFileMessage(String targetPeerId, SignalMessage signal, String from, String groupId,
                                        WebSocketSession sourceSession) throws Exception {
        Optional<WebSocketSession> target = peerRegistry.find(targetPeerId);
        if (target.isEmpty()) {
            groupRegistry.removePeer(targetPeerId);
            sendError(sourceSession, "Target peer is offline: " + targetPeerId);
            return;
        }

        ObjectNode payload = signal.payload() == null || signal.payload().isNull()
                ? objectMapper.createObjectNode()
                : signal.payload().deepCopy();
        payload.put("groupId", groupId);
        payload.put("roomId", groupId);
        payload.remove("to");
        sendSignal(target.get(), signal.type().replaceFirst("^room-", "group-"), payload, from);
    }

    private String groupId(SignalMessage signal) {
        if (signal.payload() == null) {
            return "";
        }
        if (signal.payload().get("groupId") != null) {
            return signal.payload().get("groupId").asText("");
        }
        if (signal.payload().get("roomId") != null) {
            return signal.payload().get("roomId").asText("");
        }
        return "";
    }

    private void sendSignal(WebSocketSession session, String type, ObjectNode payload) throws Exception {
        sendSignal(session, type, payload, "signal");
    }

    private void sendSignal(WebSocketSession session, String type, ObjectNode payload, String from) throws Exception {
        SignalMessage signal = new SignalMessage(type, from, peerRegistry.peerId(session).orElse(null), payload);
        peerRegistry.send(session, objectMapper.writeValueAsString(signal));
    }

    private void notifyPeerLeft(GroupRegistry.Removal removal) {
        if (removal.groupId() == null) {
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("groupId", removal.groupId());
        payload.put("roomId", removal.groupId());
        payload.put("peerId", removal.peerId());
        for (String remainingPeer : removal.remainingPeers()) {
            peerRegistry.find(remainingPeer).ifPresent(session -> {
                try {
                    sendSignal(session, "group-peer-left", payload);
                } catch (Exception ignored) {
                    peerRegistry.unregister(session);
                    groupRegistry.removePeer(remainingPeer);
                }
            });
        }
    }

    private String replyType(String requestType, String groupType, String roomType) {
        return requestType != null && requestType.startsWith("room-") ? roomType : groupType;
    }

    private Optional<String> peerIdFrom(URI uri) {
        if (uri == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("peerId"))
                .map(this::decodeRepeated)
                .filter(value -> !value.isBlank());
    }

    private String decodeRepeated(String value) {
        String decoded = value;
        for (int i = 0; i < 3; i++) {
            String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
            if (next.equals(decoded)) {
                return next;
            }
            decoded = next;
        }
        return decoded;
    }

    private void sendError(WebSocketSession session, String error) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("message", error);
        sendSignal(session, "error", payload);
    }
}
