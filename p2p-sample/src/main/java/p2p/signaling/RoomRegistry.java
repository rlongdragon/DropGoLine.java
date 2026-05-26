package p2p.signaling;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class RoomRegistry {
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private final SecureRandom random = new SecureRandom();
    private final Map<String, List<String>> peersByRoomId = new ConcurrentHashMap<>();
    private final Map<String, String> roomByPeerId = new ConcurrentHashMap<>();

    public String create(String requestedRoomId, String peerId) {
        String roomId = normalizeRoomId(requestedRoomId);
        if (roomId.isBlank()) {
            roomId = generateRoomId();
        }
        List<String> peers = new ArrayList<>();
        peers.add(peerId);
        if (peersByRoomId.putIfAbsent(roomId, peers) != null) {
            throw new IllegalArgumentException("Room already exists: " + roomId);
        }
        roomByPeerId.put(peerId, roomId);
        return roomId;
    }

    public List<String> join(String roomId, String peerId) {
        String normalized = normalizeRoomId(roomId);
        List<String> peers = peersByRoomId.get(normalized);
        if (peers == null) {
            throw new IllegalArgumentException("Room not found: " + normalized);
        }
        List<String> existingPeers;
        synchronized (peers) {
            existingPeers = peers.stream()
                    .filter(existing -> !existing.equals(peerId))
                    .toList();
            if (!peers.contains(peerId)) {
                peers.add(peerId);
            }
        }
        roomByPeerId.put(peerId, normalized);
        return existingPeers;
    }

    public List<String> peersInSameRoom(String peerId) {
        String roomId = roomByPeerId.get(peerId);
        if (roomId == null) {
            return List.of();
        }
        List<String> peers = peersByRoomId.get(roomId);
        if (peers == null) {
            return List.of();
        }
        synchronized (peers) {
            return peers.stream()
                    .filter(existing -> !existing.equals(peerId))
                    .toList();
        }
    }

    public String roomOf(String peerId) {
        return roomByPeerId.get(peerId);
    }

    public Removal removePeer(String peerId) {
        String roomId = roomByPeerId.remove(peerId);
        if (roomId == null) {
            return Removal.empty(peerId);
        }
        List<String> peers = peersByRoomId.get(roomId);
        if (peers == null) {
            return new Removal(peerId, roomId, List.of());
        }
        boolean empty;
        List<String> remainingPeers;
        synchronized (peers) {
            peers.remove(peerId);
            empty = peers.isEmpty();
            remainingPeers = List.copyOf(peers);
        }
        if (empty) {
            peersByRoomId.remove(roomId);
        }
        return new Removal(peerId, roomId, remainingPeers);
    }

    public record Removal(String peerId, String roomId, List<String> remainingPeers) {
        public static Removal empty(String peerId) {
            return new Removal(peerId, null, List.of());
        }
    }

    private String generateRoomId() {
        while (true) {
            StringBuilder code = new StringBuilder(4);
            for (int i = 0; i < 4; i++) {
                code.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
            String roomId = code.toString();
            if (!peersByRoomId.containsKey(roomId)) {
                return roomId;
            }
        }
    }

    private String normalizeRoomId(String roomId) {
        if (roomId == null || roomId.isBlank() || "auto".equalsIgnoreCase(roomId.trim())) {
            return "";
        }
        return roomId.trim().toUpperCase();
    }
}
