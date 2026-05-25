package p2p.signaling;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class PeerRegistry {
    private final Map<String, WebSocketSession> sessionsByPeerId = new ConcurrentHashMap<>();
    private final Map<String, String> peerIdsBySessionId = new ConcurrentHashMap<>();

    public void register(String peerId, WebSocketSession session) {
        WebSocketSession previous = sessionsByPeerId.put(peerId, session);
        if (previous != null && !previous.getId().equals(session.getId())) {
            peerIdsBySessionId.remove(previous.getId());
            try {
                previous.close();
            } catch (IOException ignored) {
                // The old session is being replaced, so close failures are not actionable.
            }
        }
        peerIdsBySessionId.put(session.getId(), peerId);
    }

    public Optional<WebSocketSession> find(String peerId) {
        return Optional.ofNullable(sessionsByPeerId.get(peerId))
                .filter(WebSocketSession::isOpen);
    }

    public Optional<String> peerId(WebSocketSession session) {
        return Optional.ofNullable(peerIdsBySessionId.get(session.getId()));
    }

    public void unregister(WebSocketSession session) {
        String peerId = peerIdsBySessionId.remove(session.getId());
        if (peerId != null) {
            sessionsByPeerId.remove(peerId, session);
        }
    }

    public void send(WebSocketSession session, String json) throws IOException {
        if (!session.isOpen()) {
            unregister(session);
            throw new IOException("peer session is closed");
        }
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
    }
}
