package p2p.api;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import p2p.transfer.FileChecksums;

@SuppressWarnings("BroadCatchBlock")
public final class P2pSessionInstance implements AutoCloseable {
    private static final Duration SIGNAL_POLL = Duration.ofMillis(500);
    private static final Set<String> SIGNAL_LOOP_EXCLUDED = Set.of(
            "group-created", "group-joined", "room-created", "room-joined");
    private static final Duration SIGNAL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration ICE_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DIRECT_ACCEPT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DIRECT_RECONNECT_WINDOW = Duration.ofSeconds(30);
    private static final int RELAY_FILE_CHUNK_SIZE = 32 * 1024;
    private static final Duration RELAY_CHUNK_TIMEOUT = Duration.ofSeconds(15);

    private final PeerSignalClient signal;
    private final String peerId;
    private final String groupId;
    private final Path downloadDirectory;
    private final IceNegotiationService ice = new IceNegotiationService();
    private final QuicTransportService quic = new QuicTransportService();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, P2pSession> sessionsByPeer = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<IceDescription>> pendingAnswers = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> pendingDirectReady = new ConcurrentHashMap<>();
    private final Map<String, RelayLocalOffer> relayLocalOffers = new ConcurrentHashMap<>();
    private final Map<String, RelayRemoteOffer> relayRemoteOffers = new ConcurrentHashMap<>();
    private final Map<String, RelayIncomingFile> relayIncomingFiles = new ConcurrentHashMap<>();
    private final Map<String, Long> relayLastChunkNanos = new ConcurrentHashMap<>();
    private final Map<String, Integer> relayWatchdogVersion = new ConcurrentHashMap<>();
    private final Map<String, Integer> reconnectAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> reconnectDeadlines = new ConcurrentHashMap<>();
    private final Set<String> knownMembers = ConcurrentHashMap.newKeySet();
    private final Set<String> connectingPeers = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<P2pEvent> deferredEvents = new ConcurrentLinkedQueue<>();
    private volatile P2pReceivedListener receivedListener = event -> {
    };
    private volatile P2pErrorCallback errorCallback = (remotePeerId, reason) -> {
    };
    private volatile boolean receivedListenerAttached;
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
        receivedListenerAttached = true;
        P2pEvent event;
        while ((event = deferredEvents.poll()) != null) {
            receivedListener.onReceived(event);
        }
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
        System.out.println("[DropGoLine][P2pSessionInstance] send requested peer=" + peerId
                + ", target=" + targetPeerId + ", hasMessage=" + (message != null && !message.isBlank())
                + ", file=" + file);
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
        System.out.println("[DropGoLine][P2pSessionInstance] save requested peer=" + peerId
                + ", offerId=" + offerId);
        requestFile(offerId);
    }

    public void connectToPeer(String remotePeerId) {
        System.out.println("[DropGoLine][P2pSessionInstance] connectToPeer requested local=" + peerId
                + ", remote=" + remotePeerId);
        if (remotePeerId == null || remotePeerId.isBlank() || remotePeerId.equals(peerId)) {
            System.out.println("[DropGoLine][P2pSessionInstance] connectToPeer skipped invalid/self remote=" + remotePeerId);
            return;
        }
        if (!shouldInitiateDirect(remotePeerId)) {
            System.out.println("[DropGoLine][P2pSessionInstance] connectToPeer skipped by glare tie-break local="
                    + peerId + ", remote=" + remotePeerId);
            knownMembers.add(remotePeerId);
            return;
        }
        if (!connectingPeers.add(remotePeerId)) {
            System.out.println("[DropGoLine][P2pSessionInstance] connectToPeer skipped already connecting remote=" + remotePeerId);
            return;
        }
        knownMembers.add(remotePeerId);
        executor.submit(() -> {
            IceSession session = null;
            AtomicReference<P2pSession> chatRef = new AtomicReference<>();
            try {
                session = ice.createSession(peerId, true, iceConfig());
                CompletableFuture<IceDescription> answerFuture = new CompletableFuture<>();
                CompletableFuture<Void> readyFuture = new CompletableFuture<>();
                pendingAnswers.put(remotePeerId, answerFuture);
                pendingDirectReady.put(remotePeerId, readyFuture);
                System.out.println("[DropGoLine][P2pSessionInstance] sending chat-offer remote=" + remotePeerId);
                signal.send("chat-offer", remotePeerId, session.localDescription());
                IceDescription answer = answerFuture.get(SIGNAL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                System.out.println("[DropGoLine][P2pSessionInstance] received chat-answer remote=" + remotePeerId);
                ice.setRemoteDescription(session, answer);

                IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
                System.out.println("[DropGoLine][P2pSessionInstance] ICE established remote=" + remotePeerId
                        + ", address=" + iceConnection.remoteAddress() + ", port=" + iceConnection.remotePort());
                waitForDirectReady(remotePeerId, readyFuture);
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
                    reconnectAttempts.remove(remotePeerId);
                    reconnectDeadlines.remove(remotePeerId);
                    System.out.println("[DropGoLine][P2pSessionInstance] direct QUIC session ready remote=" + remotePeerId);
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
                pendingDirectReady.remove(remotePeerId);
                sessionsByPeer.remove(remotePeerId);
                connectingPeers.remove(remotePeerId);
                if (session != null) {
                    ice.free(session);
                }
                scheduleReconnect(remotePeerId);
            }
        });
    }

    private void signalLoop() {
        System.out.println("[DropGoLine][P2pSessionInstance] signalLoop start group=" + groupId
                + ", thread=" + Thread.currentThread().getName());
        while (running) {
            try {
                SignalMessage message = signal.nextSignal(SIGNAL_POLL, SIGNAL_LOOP_EXCLUDED);
                if (message == null) {
                    continue;
                }
                if (!running) {
                    break;
                }
                switch (message.type()) {
                    case "group-peer-joined", "room-peer-joined" -> {
                        String joinedPeer = message.payload().path("peerId").asText();
                        if (!joinedPeer.isBlank()) {
                            System.out.println("[DropGoLine][P2pSessionInstance] peer joined event peer=" + joinedPeer
                                    + ", group=" + groupId);
                            knownMembers.add(joinedPeer);
                            emit(new P2pEvent(P2pEvent.Type.PEER_JOINED, joinedPeer,
                                    null, null, null, -1, -1, null, false));
                            connectToPeer(joinedPeer);
                        }
                    }
                    case "group-peer-left", "room-peer-left" -> {
                        String leftPeer = message.payload().path("peerId").asText();
                        if (!leftPeer.isBlank()) {
                            System.out.println("[DropGoLine][P2pSessionInstance] peer left event peer=" + leftPeer
                                    + ", group=" + groupId);
                            removePeer(leftPeer);
                            knownMembers.remove(leftPeer);
                            emit(new P2pEvent(P2pEvent.Type.PEER_LEFT, leftPeer,
                                    null, null, null, -1, -1, null, false));
                        }
                    }
                    case "chat-offer" -> acceptPeer(message);
                    case "chat-answer" -> completeAnswer(message);
                    case "chat-ready" -> completeDirectReady(message);
                    case "group-relay", "room-relay" -> receiveRelay(message);
                    case "group-private-relay", "room-private-relay" -> receivePrivateRelay(message);
                    case "group-file-offer", "room-file-offer" -> receiveRelayFileOffer(message);
                    case "group-file-request", "room-file-request" -> receiveRelayFileRequest(message);
                    case "group-file-start", "room-file-start" -> receiveRelayFileStart(message);
                    case "group-file-chunk", "room-file-chunk" -> receiveRelayFileChunk(message);
                    case "group-file-end", "room-file-end" -> receiveRelayFileEnd(message);
                    case "group-file-resume", "room-file-resume" -> receiveRelayFileResume(message);
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
        System.out.println("[DropGoLine][P2pSessionInstance] signalLoop exit group=" + groupId
                + ", thread=" + Thread.currentThread().getName());
    }

    private void emit(P2pEvent event) {
        if (receivedListenerAttached) {
            receivedListener.onReceived(event);
        } else {
            deferredEvents.add(event);
            if (receivedListenerAttached && deferredEvents.remove(event)) {
                receivedListener.onReceived(event);
            }
        }
    }

    private void acceptPeer(SignalMessage offerSignal) {
        String remotePeerId = offerSignal.from();
        System.out.println("[DropGoLine][P2pSessionInstance] acceptPeer offer from=" + remotePeerId);
        if (remotePeerId == null || remotePeerId.isBlank() || remotePeerId.equals(peerId)) {
            System.out.println("[DropGoLine][P2pSessionInstance] acceptPeer skipped invalid/self remote=" + remotePeerId);
            return;
        }
        if (sessionsByPeer.containsKey(remotePeerId)) {
            System.out.println("[DropGoLine][P2pSessionInstance] acceptPeer skipped existing direct session remote="
                    + remotePeerId);
            return;
        }
        if (shouldInitiateDirect(remotePeerId)) {
            System.out.println("[DropGoLine][P2pSessionInstance] acceptPeer skipped by glare tie-break; local side is caller remote="
                    + remotePeerId);
            knownMembers.add(remotePeerId);
            scheduleReconnect(remotePeerId);
            return;
        }
        if (!connectingPeers.add(remotePeerId)) {
            System.out.println("[DropGoLine][P2pSessionInstance] acceptPeer skipped already accepting remote="
                    + remotePeerId);
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
                System.out.println("[DropGoLine][P2pSessionInstance] sending chat-answer remote=" + remotePeerId);
                signal.send("chat-answer", remotePeerId, session.localDescription());

                IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
                System.out.println("[DropGoLine][P2pSessionInstance] ICE accepted remote=" + remotePeerId
                        + ", address=" + iceConnection.remoteAddress() + ", port=" + iceConnection.remotePort());
                CompletableFuture<P2pSession> acceptedChat = new CompletableFuture<>();
                CompletableFuture<Exception> streamFailure = new CompletableFuture<>();
                try (InputStream cert = QuicCertificateFiles.certificate();
                     InputStream key = QuicCertificateFiles.privateKey()) {
                    quic.accept(iceConnection.socket(), cert, key, (input, output) -> {
                        P2pSession chat = new P2pSession(peerId, remotePeerId, input, output, downloadDirectory,
                                () -> sessionsByPeer.remove(remotePeerId), listenerFor(remotePeerId));
                        try {
                            chat.expectHello();
                            chat.startReader();
                            chatRef.set(chat);
                            sessionsByPeer.put(remotePeerId, chat);
                            reconnectAttempts.remove(remotePeerId);
                            reconnectDeadlines.remove(remotePeerId);
                            acceptedChat.complete(chat);
                            System.out.println("[DropGoLine][P2pSessionInstance] accepted direct QUIC session remote=" + remotePeerId);
                            chat.waitUntilClosed();
                        } catch (Exception e) {
                            streamFailure.complete(e);
                            chat.close();
                            if (e instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                            throw asIOException("direct QUIC stream failed", e);
                        }
                    });
                    System.out.println("[DropGoLine][P2pSessionInstance] direct QUIC accept ready remote=" + remotePeerId);
                    signal.send("chat-ready", remotePeerId, Map.of());
                    P2pSession chat = waitForAcceptedChat(acceptedChat, streamFailure);
                    try {
                        chat.waitUntilClosed();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
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
                connectingPeers.remove(remotePeerId);
                if (session != null) {
                    ice.free(session);
                }
                scheduleReconnect(remotePeerId);
            }
        });
    }

    private P2pSession waitForAcceptedChat(CompletableFuture<P2pSession> acceptedChat,
                                           CompletableFuture<Exception> streamFailure) throws Exception {
        try {
            Object result = CompletableFuture.anyOf(acceptedChat, streamFailure)
                    .get(DIRECT_ACCEPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (result instanceof P2pSession chat) {
                return chat;
            }
            if (result instanceof Exception error) {
                throw new IOException("direct QUIC accept failed: " + userMessage(error), error);
            }
            throw new IOException("direct QUIC accept failed");
        } catch (TimeoutException e) {
            throw new IOException("direct QUIC accept timed out after "
                    + DIRECT_ACCEPT_TIMEOUT.toSeconds() + " seconds", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IOException("direct QUIC accept failed", cause);
        }
    }

    private void waitForDirectReady(String remotePeerId, CompletableFuture<Void> readyFuture) throws Exception {
        try {
            readyFuture.get(DIRECT_ACCEPT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            System.out.println("[DropGoLine][P2pSessionInstance] direct QUIC remote ready=" + remotePeerId);
        } catch (TimeoutException e) {
            throw new IOException("direct QUIC peer did not become ready after "
                    + DIRECT_ACCEPT_TIMEOUT.toSeconds() + " seconds", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IOException("direct QUIC peer readiness failed", cause);
        }
    }

    private static IOException asIOException(String message, Exception error) {
        if (error instanceof IOException ioException) {
            return ioException;
        }
        return new IOException(message + ": " + userMessage(error), error);
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
            public void onFileProgress(String fromPeerId, String id, String fileName, long size, long bytesReceived) {
                receivedListener.onReceived(P2pEvent.fileProgress(fromPeerId, id, fileName, size, bytesReceived, true));
            }

            @Override
            public void onNotice(String fromPeerId, String message) {
                receivedListener.onReceived(P2pEvent.notice(fromPeerId, message, true));
            }

            @Override
            public void onDisconnected(String peerId) {
                removePeer(remotePeerId);
                scheduleReconnect(remotePeerId);
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

    private void completeDirectReady(SignalMessage message) {
        CompletableFuture<Void> ready = pendingDirectReady.get(message.from());
        if (ready != null) {
            ready.complete(null);
        }
    }

    private boolean shouldInitiateDirect(String remotePeerId) {
        return canonicalPeerId(peerId).compareTo(canonicalPeerId(remotePeerId)) < 0;
    }

    private static String canonicalPeerId(String value) {
        if (value == null) {
            return "";
        }
        String decoded = value;
        for (int i = 0; i < 3; i++) {
            try {
                String next = URLDecoder.decode(decoded, StandardCharsets.UTF_8);
                if (next.equals(decoded)) {
                    return next;
                }
                decoded = next;
            } catch (IllegalArgumentException e) {
                return decoded;
            }
        }
        return decoded;
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
        String checksum = payload.path("checksum").asText("");
        String sender = message.from();
        if (id.isBlank() || fileName.isBlank() || size < 0 || checksum.isBlank()
                || sender == null || sender.isBlank()) {
            System.out.println("[DropGoLine][P2pSessionInstance] relay file offer ignored from=" + sender
                    + ", id=" + id + ", file=" + fileName + ", size=" + size);
            return;
        }
        System.out.println("[DropGoLine][P2pSessionInstance] relay file offer received from=" + sender
                + ", id=" + id + ", file=" + fileName + ", size=" + size);
        relayRemoteOffers.put(id, new RelayRemoteOffer(sender, fileName, size, checksum));
        receivedListener.onReceived(P2pEvent.fileOffer(sender, id, fileName, size, false));
    }

    private void receiveRelayFileRequest(SignalMessage message) {
        String id = message.payload().path("id").asText("");
        long offset = message.payload().path("offset").asLong(0);
        String requester = message.from();
        System.out.println("[DropGoLine][P2pSessionInstance] relay file request received from=" + requester
                + ", id=" + id);
        if (id.isBlank() || requester == null || requester.isBlank()) {
            System.out.println("[DropGoLine][P2pSessionInstance] relay file request ignored from=" + requester
                    + ", id=" + id);
            return;
        }
        RelayLocalOffer offer = relayLocalOffers.get(id);
        if (offer == null) {
            System.out.println("[DropGoLine][P2pSessionInstance] relay file request missing local offer id=" + id);
            try {
                sendRelayFileNotice(requester, "file offer not found: " + id);
            } catch (Exception e) {
                errorCallback.onError(requester, userMessage(e));
            }
            return;
        }
        executor.submit(() -> {
            try {
                sendRelayFile(requester, id, offer, offset);
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
        String checksum = payload.path("checksum").asText("");
        long offset = payload.path("offset").asLong(0);
        if (id.isBlank() || fileName.isBlank() || size < 0 || checksum.isBlank()) {
            System.out.println("[DropGoLine][P2pSessionInstance] relay file start ignored from=" + message.from()
                    + ", id=" + id + ", file=" + fileName + ", size=" + size);
            return;
        }
        if (offset < 0 || offset > size) {
            throw new IllegalStateException("invalid relay file resume offset for " + id + ": " + offset);
        }
        Files.createDirectories(downloadDirectory);
        Path target = downloadDirectory.resolve(fileName);
        Path partial = partialPath(target);
        long existing = Files.isRegularFile(partial) ? Files.size(partial) : 0;
        if (offset > 0 && existing != offset) {
            throw new IllegalStateException("relay file resume offset mismatch for " + id
                    + ": expected local partial " + existing + ", got " + offset);
        }
        RelayIncomingFile previous = relayIncomingFiles.remove(id);
        if (previous != null) {
            previous.close();
        }
        int watchdogVersion = relayWatchdogVersion.merge(id, 1, Integer::sum);
        relayLastChunkNanos.put(id, System.nanoTime());
        relayIncomingFiles.put(id, new RelayIncomingFile(message.from(), fileName, target, partial, size, checksum,
                offset, offset > 0
                ? new BufferedOutputStream(Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.APPEND), 256 * 1024)
                : new BufferedOutputStream(Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING), 256 * 1024)));
        scheduleRelayWatchdog(id, watchdogVersion);
        System.out.println("[DropGoLine][P2pSessionInstance] relay file receive start from=" + message.from()
                + ", id=" + id + ", target=" + target + ", size=" + size + ", offset=" + offset);
    }

    private void receiveRelayFileChunk(SignalMessage message) throws Exception {
        String id = message.payload().path("id").asText("");
        RelayIncomingFile incoming = relayIncomingFiles.get(id);
        if (incoming == null || !incoming.sender().equals(message.from())) {
            System.out.println("[DropGoLine][P2pSessionInstance] relay file chunk ignored from=" + message.from()
                    + ", id=" + id + ", hasIncoming=" + (incoming != null));
            return;
        }
        byte[] chunk = Base64.getDecoder().decode(message.payload().path("data").asText(""));
        if (chunk.length > RELAY_FILE_CHUNK_SIZE) {
            throw new IllegalStateException("relay file chunk too large: " + chunk.length);
        }
        incoming.write(chunk);
        relayLastChunkNanos.put(id, System.nanoTime());
        receivedListener.onReceived(P2pEvent.fileProgress(message.from(), id, incoming.fileName(),
                incoming.size(), incoming.received(), false));
        System.out.println("[DropGoLine][P2pSessionInstance] relay file chunk received from=" + message.from()
                + ", id=" + id + ", bytes=" + chunk.length
                + ", received=" + incoming.received() + "/" + incoming.size());
    }

    private void receiveRelayFileEnd(SignalMessage message) throws Exception {
        String id = message.payload().path("id").asText("");
        RelayIncomingFile incoming = relayIncomingFiles.remove(id);
        if (incoming == null || !incoming.sender().equals(message.from())) {
            System.out.println("[DropGoLine][P2pSessionInstance] relay file end ignored from=" + message.from()
                    + ", id=" + id + ", hasIncoming=" + (incoming != null));
            return;
        }
        relayLastChunkNanos.remove(id);
        relayWatchdogVersion.remove(id);
        incoming.close();
        System.out.println("[DropGoLine][P2pSessionInstance] relay file receive end from=" + message.from()
                + ", id=" + id + ", target=" + incoming.target()
                + ", received=" + incoming.received() + "/" + incoming.size());
        if (incoming.received() != incoming.size()) {
            errorCallback.onError(message.from(), "file size mismatch for " + id);
            return;
        }
        String actualChecksum = FileChecksums.sha256(incoming.partial());
        if (!actualChecksum.equalsIgnoreCase(incoming.checksum())) {
            errorCallback.onError(message.from(), "file checksum mismatch for " + id + ": expected "
                    + incoming.checksum() + ", got " + actualChecksum);
            return;
        }
        moveCompletedFile(incoming.partial(), incoming.target());
        relayRemoteOffers.remove(id);
        receivedListener.onReceived(P2pEvent.fileSaved(message.from(), id, incoming.target(), false));
    }

    private void receiveRelayFileResume(SignalMessage message) {
        String id = message.payload().path("id").asText("");
        long offset = message.payload().path("offset").asLong(0);
        String requester = message.from();
        RelayLocalOffer offer = relayLocalOffers.get(id);
        if (id.isBlank() || requester == null || requester.isBlank() || offer == null) {
            return;
        }
        executor.submit(() -> {
            try {
                sendRelayFile(requester, id, offer, offset);
            } catch (Exception e) {
                errorCallback.onError(requester, userMessage(e));
            }
        });
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
        System.out.println("[DropGoLine][P2pSessionInstance] offerFile requested local=" + peerId
                + ", target=" + remotePeerId + ", path=" + path
                + ", directSessions=" + sessionsByPeer.keySet());
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
        long size = Files.size(path);
        String checksum = FileChecksums.sha256(path);
        relayLocalOffers.put(id, new RelayLocalOffer(path, path.getFileName().toString(), size, checksum));
        Map<String, Object> payload = remotePeerId == null || remotePeerId.isBlank()
                ? Map.of("id", id, "fileName", path.getFileName().toString(), "size", size,
                "checksum", checksum)
                : Map.of("id", id, "fileName", path.getFileName().toString(), "size", size,
                "checksum", checksum, "to", remotePeerId);
        System.out.println("[DropGoLine][P2pSessionInstance] sending relay file offer id=" + id
                + ", target=" + remotePeerId + ", file=" + path.getFileName()
                + ", size=" + size);
        signal.sendToServer("group-file-offer", payload);
        receivedListener.onReceived(P2pEvent.notice(peerId,
                "offered " + path + " as " + id + " to " + remotePeerId + " by relay fallback", false));
    }

    private void requestFile(String id) throws Exception {
        System.out.println("[DropGoLine][P2pSessionInstance] requestFile id=" + id
                + ", relayOffer=" + relayRemoteOffers.containsKey(id)
                + ", directSessions=" + sessionsByPeer.keySet());
        RelayRemoteOffer relayOffer = relayRemoteOffers.get(id);
        if (relayOffer != null) {
            long offset = relayResumeOffset(relayOffer);
            System.out.println("[DropGoLine][P2pSessionInstance] requesting relay file id=" + id
                    + ", sender=" + relayOffer.sender() + ", offset=" + offset);
            signal.sendToServer("group-file-request", Map.of(
                    "to", relayOffer.sender(),
                    "id", id,
                    "offset", offset));
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

    private void sendRelayFile(String remotePeerId, String id, RelayLocalOffer offer, long offset) throws Exception {
        if (Files.size(offer.path()) != offer.size()) {
            sendRelayFileNotice(remotePeerId, "file changed after offer; share it again: " + id);
            return;
        }
        if (offset < 0 || offset > offer.size()) {
            sendRelayFileNotice(remotePeerId, "invalid resume offset for " + id + ": " + offset);
            return;
        }
        System.out.println("[DropGoLine][P2pSessionInstance] sendRelayFile start remote=" + remotePeerId
                + ", id=" + id + ", path=" + offer.path()
                + ", size=" + offer.size() + ", offset=" + offset);
        signal.sendToServer("group-file-start", Map.of(
                "to", remotePeerId,
                "id", id,
                "fileName", offer.fileName(),
                "size", offer.size(),
                "checksum", offer.checksum(),
                "offset", offset));

        byte[] buffer = new byte[RELAY_FILE_CHUNK_SIZE];
        try (InputStream fileInput = Files.newInputStream(offer.path())) {
            skipFully(fileInput, offset);
            int read;
            int chunkIndex = 0;
            long sent = 0;
            while ((read = fileInput.read(buffer)) >= 0) {
                byte[] chunk = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
                signal.sendToServer("group-file-chunk", Map.of(
                        "to", remotePeerId,
                        "id", id,
                        "data", Base64.getEncoder().encodeToString(chunk)));
                sent += read;
                receivedListener.onReceived(P2pEvent.fileProgress(remotePeerId, id, offer.fileName(),
                        offer.size(), offset + sent, false));
                System.out.println("[DropGoLine][P2pSessionInstance] sendRelayFile chunk remote=" + remotePeerId
                        + ", id=" + id + ", chunk=" + chunkIndex
                        + ", bytes=" + read + ", sent=" + (offset + sent) + "/" + offer.size());
                chunkIndex++;
                if (chunkIndex % 20 == 0) {
                    Thread.sleep(5);
                }
            }
        }
        signal.sendToServer("group-file-end", Map.of("to", remotePeerId, "id", id));
        System.out.println("[DropGoLine][P2pSessionInstance] sendRelayFile complete remote=" + remotePeerId
                + ", id=" + id + ", path=" + offer.path());
    }

    private void scheduleRelayWatchdog(String id, int version) {
        reconnectExecutor.schedule(() -> {
            if (!Integer.valueOf(version).equals(relayWatchdogVersion.get(id))) {
                return;
            }
            Long lastTime = relayLastChunkNanos.get(id);
            if (lastTime == null) {
                return;
            }
            if (System.nanoTime() - lastTime < RELAY_CHUNK_TIMEOUT.toNanos()) {
                scheduleRelayWatchdog(id, version);
                return;
            }
            RelayIncomingFile incoming = relayIncomingFiles.remove(id);
            if (incoming == null) {
                relayLastChunkNanos.remove(id);
                relayWatchdogVersion.remove(id);
                return;
            }
            long offset;
            try {
                incoming.close();
                offset = Files.size(incoming.partial());
            } catch (IOException e) {
                relayLastChunkNanos.remove(id);
                relayWatchdogVersion.remove(id);
                errorCallback.onError(incoming.sender(), "relay watchdog flush failed: " + userMessage(e));
                return;
            }
            System.out.println("[DropGoLine][P2pSessionInstance] relay stall detected, sending resume id=" + id
                    + ", offset=" + offset + ", sender=" + incoming.sender());
            try {
                signal.sendToServer("group-file-resume", Map.of(
                        "to", incoming.sender(),
                        "id", id,
                        "offset", offset));
                relayLastChunkNanos.put(id, System.nanoTime());
            } catch (Exception e) {
                relayLastChunkNanos.remove(id);
                relayWatchdogVersion.remove(id);
                errorCallback.onError(incoming.sender(), "relay resume failed: " + userMessage(e));
            }
        }, RELAY_CHUNK_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
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
        pendingDirectReady.remove(remotePeerId);
        connectingPeers.remove(remotePeerId);
        reconnectAttempts.remove(remotePeerId);
        reconnectDeadlines.remove(remotePeerId);
    }

    private void scheduleReconnect(String remotePeerId) {
        if (!running || remotePeerId == null || remotePeerId.isBlank() || remotePeerId.equals(peerId)) {
            return;
        }
        if (!shouldInitiateDirect(remotePeerId)) {
            return;
        }
        if (!knownMembers.contains(remotePeerId) || sessionsByPeer.containsKey(remotePeerId)
                || connectingPeers.contains(remotePeerId)) {
            return;
        }
        long now = System.nanoTime();
        long deadline = reconnectDeadlines.computeIfAbsent(remotePeerId,
                ignored -> now + DIRECT_RECONNECT_WINDOW.toNanos());
        if (now >= deadline) {
            reconnectAttempts.remove(remotePeerId);
            reconnectDeadlines.remove(remotePeerId);
            receivedListener.onReceived(P2pEvent.notice(remotePeerId,
                    "direct link was not restored within 30 seconds; ask this peer to rejoin if relay also stops",
                    false));
            return;
        }
        int attempt = reconnectAttempts.merge(remotePeerId, 1, (oldValue, one) -> Math.min(oldValue + 1, 8));
        long delaySeconds = Math.min(10, 1L << Math.min(attempt, 3));
        long remainingSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(deadline - now));
        delaySeconds = Math.min(delaySeconds, remainingSeconds);
        reconnectExecutor.schedule(() -> {
            if (running && knownMembers.contains(remotePeerId) && !sessionsByPeer.containsKey(remotePeerId)) {
                connectToPeer(remotePeerId);
            }
        }, delaySeconds, TimeUnit.SECONDS);
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
        relayLastChunkNanos.clear();
        relayWatchdogVersion.clear();
        reconnectExecutor.shutdownNow();
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

    private long relayResumeOffset(RelayRemoteOffer offer) throws java.io.IOException {
        Path partial = partialPath(downloadDirectory.resolve(offer.fileName()));
        if (!Files.isRegularFile(partial)) {
            return 0;
        }
        long offset = Files.size(partial);
        return offset >= offer.size() ? 0 : offset;
    }

    private static Path partialPath(Path target) {
        Path fileName = target.getFileName();
        String partialName = (fileName == null ? "download" : fileName.toString()) + ".part";
        Path parent = target.getParent();
        return parent == null ? Path.of(partialName) : parent.resolve(partialName);
    }

    private static void moveCompletedFile(Path partial, Path target) throws java.io.IOException {
        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void skipFully(InputStream input, long bytes) throws java.io.IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() == -1) {
                    throw new java.io.IOException("cannot skip to resume offset " + bytes);
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
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

    private record RelayLocalOffer(Path path, String fileName, long size, String checksum) {
    }

    private record RelayRemoteOffer(String sender, String fileName, long size, String checksum) {
    }

    private static final class RelayIncomingFile implements AutoCloseable {
        private final String sender;
        private final String fileName;
        private final Path target;
        private final Path partial;
        private final long size;
        private final String checksum;
        private final OutputStream output;
        private long received;

        private RelayIncomingFile(String sender, String fileName, Path target, Path partial, long size, String checksum,
                                  long received, OutputStream output) {
            this.sender = sender;
            this.fileName = fileName;
            this.target = target;
            this.partial = partial;
            this.size = size;
            this.checksum = checksum;
            this.received = received;
            this.output = output;
        }

        private String sender() {
            return sender;
        }

        private String fileName() {
            return fileName;
        }

        private Path target() {
            return target;
        }

        private Path partial() {
            return partial;
        }

        private long size() {
            return size;
        }

        private String checksum() {
            return checksum;
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
