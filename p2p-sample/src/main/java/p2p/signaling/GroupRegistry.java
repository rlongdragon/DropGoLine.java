package p2p.signaling;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class GroupRegistry {
    private static final String CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private final SecureRandom random = new SecureRandom();
    private final Map<String, List<String>> peersByGroupId = new ConcurrentHashMap<>();
    private final Map<String, String> groupByPeerId = new ConcurrentHashMap<>();

    public String create(String requestedGroupId, String peerId) {
        String groupId = normalizeGroupId(requestedGroupId);
        if (groupId.isBlank()) {
            groupId = generateGroupId();
        }
        List<String> peers = new ArrayList<>();
        peers.add(peerId);
        if (peersByGroupId.putIfAbsent(groupId, peers) != null) {
            throw new IllegalArgumentException("Group already exists: " + groupId);
        }
        groupByPeerId.put(peerId, groupId);
        return groupId;
    }

    public List<String> join(String groupId, String peerId) {
        String normalized = normalizeGroupId(groupId);
        List<String> peers = peersByGroupId.get(normalized);
        if (peers == null) {
            throw new IllegalArgumentException("Group not found: " + normalized);
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
        groupByPeerId.put(peerId, normalized);
        return existingPeers;
    }

    public List<String> peersInSameGroup(String peerId) {
        String groupId = groupByPeerId.get(peerId);
        if (groupId == null) {
            return List.of();
        }
        List<String> peers = peersByGroupId.get(groupId);
        if (peers == null) {
            return List.of();
        }
        synchronized (peers) {
            return peers.stream()
                    .filter(existing -> !existing.equals(peerId))
                    .toList();
        }
    }

    public String groupOf(String peerId) {
        return groupByPeerId.get(peerId);
    }

    public Removal removePeer(String peerId) {
        String groupId = groupByPeerId.remove(peerId);
        if (groupId == null) {
            return Removal.empty(peerId);
        }
        List<String> peers = peersByGroupId.get(groupId);
        if (peers == null) {
            return new Removal(peerId, groupId, List.of());
        }
        boolean empty;
        List<String> remainingPeers;
        synchronized (peers) {
            peers.remove(peerId);
            empty = peers.isEmpty();
            remainingPeers = List.copyOf(peers);
        }
        if (empty) {
            peersByGroupId.remove(groupId);
        }
        return new Removal(peerId, groupId, remainingPeers);
    }

    public record Removal(String peerId, String groupId, List<String> remainingPeers) {
        public static Removal empty(String peerId) {
            return new Removal(peerId, null, List.of());
        }
    }

    private String generateGroupId() {
        while (true) {
            StringBuilder code = new StringBuilder(4);
            for (int i = 0; i < 4; i++) {
                code.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
            String groupId = code.toString();
            if (!peersByGroupId.containsKey(groupId)) {
                return groupId;
            }
        }
    }

    private String normalizeGroupId(String groupId) {
        if (groupId == null || groupId.isBlank() || "auto".equalsIgnoreCase(groupId.trim())) {
            return "";
        }
        return groupId.trim().toUpperCase();
    }
}
