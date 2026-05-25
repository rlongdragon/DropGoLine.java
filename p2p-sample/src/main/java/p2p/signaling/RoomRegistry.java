package p2p.signaling;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class RoomRegistry {
    private final Map<String, String> hostsByRoomId = new ConcurrentHashMap<>();

    public boolean create(String roomId, String hostPeerId) {
        return hostsByRoomId.putIfAbsent(roomId, hostPeerId) == null;
    }

    public Optional<String> host(String roomId) {
        return Optional.ofNullable(hostsByRoomId.get(roomId));
    }

    public void removePeer(String peerId) {
        hostsByRoomId.entrySet().removeIf(entry -> entry.getValue().equals(peerId));
    }
}
