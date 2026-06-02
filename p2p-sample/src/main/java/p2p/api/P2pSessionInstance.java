package p2p.api;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import p2p.session.P2pSession;
import p2p.ice.IceDescription;
import p2p.ice.IceNegotiationService;
import p2p.ice.IceNegotiationService.IceConnection;
import p2p.ice.IceNegotiationService.IceSession;
import p2p.ice.IceServerConfig;
import p2p.peer.PeerSignalClient;
import p2p.quic.QuicChannel;
import p2p.quic.QuicCertificateFiles;
import p2p.quic.QuicTransportService;
import p2p.signaling.SignalMessage;

public final class P2pSessionInstance implements AutoCloseable {
    private static final Duration SIGNAL_POLL = Duration.ofMillis(500);
    private static final Duration SIGNAL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration ICE_TIMEOUT = Duration.ofSeconds(45);
    private static final int RELAY_FILE_CHUNK_SIZE = 3 * 1024;

    private final PeerSignalClient signal;
    private final String peerId;
    private final String groupId;
    private final Path downloadDirectory;
    private final IceNegotiationService ice = new IceNegotiationService();
    private final QuicTransportService quic = new QuicTransportService();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, P2pSession> sessionsByPeer = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<IceDescription>> pendingAnswers = new ConcurrentHashMap<>();
    private final Map<String, Path> relayLocalOffers = new ConcurrentHashMap<>();
    private final Map<String, RelayRemoteOffer> relayRemoteOffers = new ConcurrentHashMap<>();
    private final Map<String, RelayIncomingFile> relayIncomingFiles = new ConcurrentHashMap<>();
    private final Set<String> knownMembers = ConcurrentHashMap.newKeySet();
    private final Set<String> connectingPeers = ConcurrentHashMap.newKeySet();
    private volatile P2pReceivedListener receivedListener = event -> {
    };
    private volatile P2pErrorCallback errorCallback = (remotePeerId, reason) -> {
    };
    private volatile boolean running = true;

    P2pSessionInstance(PeerSignalClient signal, String peerId, String groupId, Path downloadDirectory) {
        this.signal = signal;
        this.peerId = peerId;
        this.groupId = groupId;
        this.downloadDirectory = downloadDirectory;
    }

    public void start() {
        start(Set.of());
    }

    public void start(Set<String> existingPeers) {
        knownMembers.addAll(existingPeers);
        Thread signalThread = new Thread(this::signalLoop, "p2p-group-signal-" + peerId);
        signalThread.setDaemon(true);
        signalThread.start();
        for (String existingPeer : existingPeers) {
            connectToPeer(existingPeer);
        }
    }

    public String groupId() {
        return groupId;
    }

    @Deprecated
    public String roomId() {
        return groupId();
    }

    public Set<String> showMembers() {
        return Set.copyOf(knownMembers);
    }

    public Set<String> showMenbers() {
        return showMembers();
    }

    public void onReceived(P2pReceivedListener listener) {
        onReceived(listener, null);
    }

    public void onReceived(P2pReceivedListener listener, P2pErrorCallback onError) {
        receivedListener = listener == null ? event -> {
        } : listener;
        errorCallback = onError == null ? (remotePeerId, reason) -> {
        } : onError;
    }

    public void createReceivedListener(P2pReceivedListener listener) {
        onReceived(listener);
    }

    public void createReceivedListener(P2pReceivedListener listener, P2pErrorCallback onError) {
        onReceived(listener, onError);
    }

    public void createRecviedListener(P2pReceivedListener listener, P2pErrorCallback onError) {
        onReceived(listener, onError);
    }

    public void send(String message) throws Exception {
        send(message, null, null);
    }

    public void send(Path file) throws Exception {
        send(null, file, null);
    }

    public void send(String message, Path file, String targetPeerId) throws Exception {
        if (message != null && !message.isBlank()) {
            if (targetPeerId == null || targetPeerId.isBlank()) {
                sendText(message);
            } else {
                sendPrivateText(targetPeerId, message);
            }
        }
        if (file != null) {
            offerFile(file, targetPeerId);
        }
    }

    public void save(String offerId) throws Exception {
        requestFile(offerId);
    }

    public void connectToPeer(String remotePeerId) {
        if (remotePeerId == null || remotePeerId.isBlank() || remotePeerId.equals(peerId)) {
            return;
        }
        if (!connectingPeers.add(remotePeerId)) {
            return;
        }
        knownMembers.add(remotePeerId);
        executor.submit(() -> {
            IceSession session = null;
            AtomicReference<P2pSession> chatRef = new AtomicReference<>();
            try {
                session = ice.createSession(peerId, true, iceConfig());
                CompletableFuture<IceDescription> answerFuture = new CompletableFuture<>();
                pendingAnswers.put(remotePeerId, answerFuture);
                signal.send("chat-offer", remotePeerId, session.localDescription());
                IceDescription answer = answerFuture.get(SIGNAL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                ice.setRemoteDescription(session, answer);

                IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
                try (QuicChannel channel = quic.connect(
                        iceConnection.socket(),
                        iceConnection.remoteAddress(),
                        iceConnection.remotePort());
                     QuicChannel.TransferStream stream = channel.openStream()) {
                    P2pSession chat = new P2pSession(peerId, remotePeerId, stream.input(), stream.output(),
                            downloadDirectory, () -> sessionsByPeer.remove(remotePeerId), listenerFor(remotePeerId));
                    chat.sendHello();
                    chat.startReader();
                    chatRef.set(chat);
                    sessionsByPeer.put(remotePeerId, chat);
                    chat.waitUntilClosed();
                }
            } catch (Exception e) {
                if (running) {
                    errorCallback.onError(remotePeerId, userMessage(e));
                }
            } finally {
                P2pSession chat = chatRef.get();
                if (chat != null) {
                    chat.close();
                }
                pendingAnswers.remove(remotePeerId);
                sessionsByPeer.remove(remotePeerId);
                connectingPeers.remove(remotePeerId);
                if (session != null) {
                    ice.free(session);
                }
            }
        });
    }

    private void signalLoop() {
        while (running) {
            try {
                SignalMessage message = signal.nextSignal(SIGNAL_POLL);
                if (message == null) {
                    continue;
                }
                switch (message.type()) {
                    case "group-peer-joined", "room-peer-joined" -> {
                        String joinedPeer = message.payload().path("peerId").asText();
                        if (!joinedPeer.isBlank()) {
                            knownMembers.add(joinedPeer);
                            receivedListener.onReceived(new P2pEvent(P2pEvent.Type.PEER_JOINED, joinedPeer,
                                    null, null, null, -1, null, false));
                        }
                    }
                    case "group-peer-left", "room-peer-left" -> {
                        String leftPeer = message.payload().path("peerId").asText();
                        if (!leftPeer.isBlank()) {
                            removePeer(leftPeer);
                            knownMembers.remove(leftPeer);
                            receivedListener.onReceived(new P2pEvent(P2pEvent.Type.PEER_LEFT, leftPeer,
                                    null, null, null, -1, null, false));
                        }
                    }
                    case "chat-offer" -> acceptPeer(message);
                    case "chat-answer" -> completeAnswer(message);
                    case "group-relay", "room-relay" -> receiveRelay(message);
                    case "group-private-relay", "room-private-relay" -> receivePrivateRelay(message);
                    case "group-file-offer", "room-file-offer" -> receiveRelayFileOffer(message);
                    case "group-file-request", "room-file-request" -> receiveRelayFileRequest(message);
                    case "group-file-start", "room-file-start" -> receiveRelayFileStart(message);
                    case "group-file-chunk", "room-file-chunk" -> receiveRelayFileChunk(message);
                    case "group-file-end", "room-file-end" -> receiveRelayFileEnd(message);
                    case "group-file-notice", "room-file-notice" -> receivedListener.onReceived(P2pEvent.notice(message.from(),
                            message.payload().path("message").asText(""), false));
                    case "error" -> errorCallback.onError(message.from(), signalErrorText(message));
                    default -> {
                    }
                }
            } catch (Exception e) {
                if (running) {
                    errorCallback.onError("signal", userMessage(e));
                }
            }
        }
    }

    private void acceptPeer(SignalMessage offerSignal) {
        String remotePeerId = offerSignal.from();
        if (remotePeerId == null || remotePeerId.isBlank() || remotePeerId.equals(peerId)) {
            return;
        }
        knownMembers.add(remotePeerId);
        executor.submit(() -> {
            IceSession session = null;
            AtomicReference<P2pSession> chatRef = new AtomicReference<>();
            try {
                IceDescription offer = objectMapper.treeToValue(offerSignal.payload(), IceDescription.class);
                session = ice.createSession(peerId, false, iceConfig());
                ice.setRemoteDescription(session, offer);
                signal.send("chat-answer", remotePeerId, session.localDescription());

                IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
                try (InputStream cert = QuicCertificateFiles.certificate();
                     InputStream key = QuicCertificateFiles.privateKey()) {
                    quic.accept(iceConnection.socket(), cert, key, (input, output) -> {
                        P2pSession chat = new P2pSession(peerId, remotePeerId, input, output, downloadDirectory,
                                () -> sessionsByPeer.remove(remotePeerId), listenerFor(remotePeerId));
                        chat.expectHello();
                        chat.startReader();
                        chatRef.set(chat);
                        sessionsByPeer.put(remotePeerId, chat);
                        try {
                            chat.waitUntilClosed();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    while (running && chatRef.get() == null) {
                        TimeUnit.MILLISECONDS.sleep(100);
                    }
                    P2pSession chat = chatRef.get();
                    if (chat != null) {
                        chat.waitUntilClosed();
                    }
                }
            } catch (Exception e) {
                if (running) {
                    errorCallback.onError(remotePeerId, userMessage(e));
                }
            } finally {
                P2pSession chat = chatRef.get();
                if (chat != null) {
                    chat.close();
                }
                sessionsByPeer.remove(remotePeerId);
                if (session != null) {
                    ice.free(session);
                }
            }
        });
    }

    private P2pSession.Listener listenerFor(String remotePeerId) {
        return new P2pSession.Listener() {
            @Override
            public void onText(String fromPeerId, String text) {
                receivedListener.onReceived(P2pEvent.message(fromPeerId, text, true));
            }

            @Override
            public void onFileOffer(String fromPeerId, String id, String fileName, long size) {
                receivedListener.onReceived(P2pEvent.fileOffer(fromPeerId, id, fileName, size, true));
            }

            @Override
            public void onFileSaved(String fromPeerId, String id, Path target) {
                receivedListener.onReceived(P2pEvent.fileSaved(fromPeerId, id, target, true));
            }

            @Override
            public void onNotice(String fromPeerId, String message) {
                receivedListener.onReceived(P2pEvent.notice(fromPeerId, message, true));
            }

            @Override
            public void onDisconnected(String peerId) {
                removePeer(remotePeerId);
            }

            @Override
            public void onError(String peerId, Exception error) {
                errorCallback.onError(peerId, userMessage(error));
            }
        };
    }

    private void completeAnswer(SignalMessage message) throws Exception {
        CompletableFuture<IceDescription> answer = pendingAnswers.get(message.from());
        if (answer != null) {
            answer.complete(objectMapper.treeToValue(message.payload(), IceDescription.class));
        }
    }

    private void receiveRelay(SignalMessage message) {
        String sender = message.from();
        if (sender != null && sessionsByPeer.containsKey(sender)) {
            return;
        }
        String content = message.payload().path("message").asText("");
        if (!content.isBlank()) {
            receivedListener.onReceived(P2pEvent.message(sender, content, false));
        }
    }

    private void receivePrivateRelay(SignalMessage message) {
        String content = message.payload().path("message").asText("");
        if (!content.isBlank()) {
            receivedListener.onReceived(P2pEvent.message(message.from(), content, false));
        }
    }

    private void receiveRelayFileOffer(SignalMessage message) {
        JsonNode payload = message.payload();
        String id = payload.path("id").asText("");
        String fileName = sanitizeFileName(payload.path("fileName").asText(""));
        long size = payload.path("size").asLong(-1);
        String sender = message.from();
        if (id.isBlank() || fileName.isBlank() || size < 0 || sender == null || sender.isBlank()) {
            return;
        }
        relayRemoteOffers.put(id, new RelayRemoteOffer(sender, fileName, size));
        receivedListener.onReceived(P2pEvent.fileOffer(sender, id, fileName, size, false));
    }

    private void receiveRelayFileRequest(SignalMessage message) {
        String id = message.payload().path("id").asText("");
        String requester = message.from();
        if (id.isBlank() || requester == null || requester.isBlank()) {
            return;
        }
        Path path = relayLocalOffers.get(id);
        if (path == null) {
            try {
                sendRelayFileNotice(requester, "file offer not found: " + id);
            } catch (Exception e) {
                errorCallback.onError(requester, userMessage(e));
            }
            return;
        }
        executor.submit(() -> {
            try {
                sendRelayFile(requester, id, path);
            } catch (Exception e) {
                errorCallback.onError(requester, userMessage(e));
            }
        });
    }

    private void receiveRelayFileStart(SignalMessage message) throws Exception {
        JsonNode payload = message.payload();
        String id = payload.path("id").asText("");
        String fileName = sanitizeFileName(payload.path("fileName").asText(""));
        long size = payload.path("size").asLong(-1);
        if (id.isBlank() || fileName.isBlank() || size < 0) {
            return;
        }
        Files.createDirectories(downloadDirectory);
        Path target = downloadDirectory.resolve(fileName);
        RelayIncomingFile previous = relayIncomingFiles.remove(id);
        if (previous != null) {
            previous.close();
        }
        relayIncomingFiles.put(id, new RelayIncomingFile(message.from(), target, size,
                Files.newOutputStream(target)));
    }

    private void receiveRelayFileChunk(SignalMessage message) throws Exception {
        String id = message.payload().path("id").asText("");
        RelayIncomingFile incoming = relayIncomingFiles.get(id);
        if (incoming == null || !incoming.sender().equals(message.from())) {
            return;
        }
        byte[] chunk = Base64.getDecoder().decode(message.payload().path("data").asText(""));
        if (chunk.length > RELAY_FILE_CHUNK_SIZE) {
            throw new IllegalStateException("relay file chunk too large: " + chunk.length);
        }
        incoming.write(chunk);
    }

    private void receiveRelayFileEnd(SignalMessage message) throws Exception {
        String id = message.payload().path("id").asText("");
        RelayIncomingFile incoming = relayIncomingFiles.remove(id);
        if (incoming == null || !incoming.sender().equals(message.from())) {
            return;
        }
        incoming.close();
        if (incoming.received() != incoming.size()) {
            errorCallback.onError(message.from(), "file size mismatch for " + id);
            return;
        }
        relayRemoteOffers.remove(id);
        receivedListener.onReceived(P2pEvent.fileSaved(message.from(), id, incoming.target(), false));
    }

    private void sendText(String text) throws Exception {
        for (Map.Entry<String, P2pSession> entry : sessionsByPeer.entrySet()) {
            try {
                entry.getValue().sendText(text);
            } catch (Exception e) {
                sessionsByPeer.remove(entry.getKey());
                errorCallback.onError(entry.getKey(), userMessage(e));
            }
        }
        signal.sendToServer("group-relay", Map.of("message", text));
    }

    private void sendPrivateText(String remotePeerId, String text) throws Exception {
        P2pSession session = sessionsByPeer.get(remotePeerId);
        if (session != null) {
            try {
                session.sendText("(private) " + text);
                return;
            } catch (Exception e) {
                sessionsByPeer.remove(remotePeerId);
                errorCallback.onError(remotePeerId, userMessage(e));
            }
        }
        signal.sendToServer("group-private-relay", Map.of("to", remotePeerId, "message", text));
    }

    private void offerFile(Path path, String remotePeerId) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("file does not exist: " + path);
        }
        if (remotePeerId != null && !remotePeerId.isBlank()) {
            offerFileToPeer(path, remotePeerId);
            return;
        }

        Set<String> targets = new HashSet<>(knownMembers);
        targets.remove(peerId);
        targets.addAll(sessionsByPeer.keySet());
        if (targets.isEmpty()) {
            receivedListener.onReceived(P2pEvent.notice(peerId, "no peers in this group yet", false));
            return;
        }
        for (String target : targets) {
            offerFileToPeer(path, target);
        }
    }

    private void offerFileToPeer(Path path, String remotePeerId) throws Exception {
        P2pSession session = sessionsByPeer.get(remotePeerId);
        if (session != null) {
            try {
                session.offerFile(path);
                return;
            } catch (Exception e) {
                sessionsByPeer.remove(remotePeerId);
                errorCallback.onError(remotePeerId, "direct file offer failed; using relay fallback: " + userMessage(e));
            }
        }
        offerRelayFile(path, remotePeerId);
    }

    private void offerRelayFile(Path path, String remotePeerId) throws Exception {
        String id = UUID.randomUUID().toString().substring(0, 8);
        relayLocalOffers.put(id, path);
        Map<String, Object> payload = Map.of(
                "id", id,
                "fileName", path.getFileName().toString(),
                "size", Files.size(path),
                "to", remotePeerId);
        signal.sendToServer("group-file-offer", payload);
        receivedListener.onReceived(P2pEvent.notice(peerId,
                "offered " + path + " as " + id + " to " + remotePeerId + " by relay fallback", false));
    }

    private void requestFile(String id) throws Exception {
        RelayRemoteOffer relayOffer = relayRemoteOffers.get(id);
        if (relayOffer != null) {
            signal.sendToServer("group-file-request", Map.of("to", relayOffer.sender(), "id", id));
            return;
        }
        for (Map.Entry<String, P2pSession> entry : sessionsByPeer.entrySet()) {
            try {
                entry.getValue().requestFile(id);
            } catch (Exception e) {
                sessionsByPeer.remove(entry.getKey());
                errorCallback.onError(entry.getKey(), userMessage(e));
            }
        }
    }

    private void sendRelayFile(String remotePeerId, String id, Path path) throws Exception {
        long size = Files.size(path);
        signal.sendToServer("group-file-start", Map.of(
                "to", remotePeerId,
                "id", id,
                "fileName", path.getFileName().toString(),
                "size", size));

        byte[] buffer = new byte[RELAY_FILE_CHUNK_SIZE];
        try (InputStream fileInput = Files.newInputStream(path)) {
            int read;
            while ((read = fileInput.read(buffer)) >= 0) {
                byte[] chunk = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
                signal.sendToServer("group-file-chunk", Map.of(
                        "to", remotePeerId,
                        "id", id,
                        "data", Base64.getEncoder().encodeToString(chunk)));
            }
        }
        signal.sendToServer("group-file-end", Map.of("to", remotePeerId, "id", id));
    }

    private void sendRelayFileNotice(String remotePeerId, String message) throws Exception {
        signal.sendToServer("group-file-notice", Map.of("to", remotePeerId, "message", message));
    }

    private void removePeer(String remotePeerId) {
        P2pSession session = sessionsByPeer.remove(remotePeerId);
        if (session != null) {
            session.close();
        }
        pendingAnswers.remove(remotePeerId);
        connectingPeers.remove(remotePeerId);
    }

    @Override
    public void close() {
        running = false;
        for (P2pSession session : sessionsByPeer.values()) {
            session.close();
        }
        for (RelayIncomingFile incoming : relayIncomingFiles.values()) {
            try {
                incoming.close();
            } catch (Exception ignored) {
            }
        }
        executor.shutdownNow();
    }

    static Set<String> peersFrom(JsonNode peersNode) {
        Set<String> peers = new HashSet<>();
        if (peersNode == null || !peersNode.isArray()) {
            return peers;
        }
        peersNode.forEach(peer -> {
            if (peer.isTextual() && !peer.asText().isBlank()) {
                peers.add(peer.asText());
            }
        });
        return peers;
    }

    private static IceServerConfig iceConfig() {
        return new IceServerConfig(
                envAllowBlank("STUN_SERVER", "stun.l.google.com"),
                Integer.parseInt(env("STUN_PORT", "19302")),
                env("TURN_SERVER", ""),
                Integer.parseInt(env("TURN_PORT", "3478")),
                env("TURN_USERNAME", ""),
                env("TURN_PASSWORD", ""));
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String sanitized = Path.of(fileName).getFileName().toString()
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .trim();
        return sanitized.isBlank() ? "download" : sanitized;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String envAllowBlank(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static String userMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String signalErrorText(SignalMessage message) {
        JsonNode payload = message.payload();
        if (payload == null || payload.isNull()) {
            return "server rejected signal";
        }
        if (payload.has("message")) {
            return payload.path("message").asText("server rejected signal");
        }
        if (payload.isTextual()) {
            return payload.asText();
        }
        return payload.toString();
    }

    private record RelayRemoteOffer(String sender, String fileName, long size) {
    }

    private static final class RelayIncomingFile implements AutoCloseable {
        private final String sender;
        private final Path target;
        private final long size;
        private final OutputStream output;
        private long received;

        private RelayIncomingFile(String sender, Path target, long size, OutputStream output) {
            this.sender = sender;
            this.target = target;
            this.size = size;
            this.output = output;
        }

        private String sender() {
            return sender;
        }

        private Path target() {
            return target;
        }

        private long size() {
            return size;
        }

        private long received() {
            return received;
        }

        private void write(byte[] chunk) throws java.io.IOException {
            output.write(chunk);
            received += chunk.length;
        }

        @Override
        public void close() throws java.io.IOException {
            output.close();
        }
    }
}
