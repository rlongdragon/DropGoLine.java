package p2p.chat;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

public class P2pChatCli {
    private static final Duration SIGNAL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration SIGNAL_POLL = Duration.ofMillis(500);
    private static final Duration ICE_TIMEOUT = Duration.ofSeconds(45);

    private final IceNegotiationService ice = new IceNegotiationService();
    private final QuicTransportService quic = new QuicTransportService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        P2pChatCli cli = new P2pChatCli();
        try {
            if (args.length == 0) {
                cli.runInteractive();
                return;
            }

            switch (args[0]) {
                case "create" -> {
                    if (args.length < 4) {
                        usage();
                        return;
                    }
                    cli.createRoom(args[1], args[2], Path.of(args[3]), args.length >= 5 ? args[4] : defaultSignalUrl());
                }
                case "join" -> {
                    if (args.length < 4) {
                        usage();
                        return;
                    }
                    cli.joinRoom(args[1], args[2], Path.of(args[3]), args.length >= 5 ? args[4] : defaultSignalUrl());
                }
                case "listen" -> {
                    if (args.length < 3) {
                        usage();
                        return;
                    }
                    cli.listen(args[1], Path.of(args[2]), args.length >= 4 ? args[3] : defaultSignalUrl());
                }
                case "connect" -> {
                    if (args.length < 4) {
                        usage();
                        return;
                    }
                    cli.connect(args[1], args[2], Path.of(args[3]), args.length >= 5 ? args[4] : defaultSignalUrl());
                }
                default -> usage();
            }
        } catch (Exception e) {
            System.out.println("[error] " + userMessage(e));
        }
    }

    private void runInteractive() throws Exception {
        Scanner scanner = new Scanner(System.in);
        System.out.println("=== P2P Chat Client (Smart Hybrid) ===");

        System.out.print("Enter your name: ");
        String peerId = scanner.nextLine().trim();
        if (peerId.isBlank()) {
            System.out.println("[error] name is required");
            return;
        }

        System.out.print("Enter Server IP (default 127.0.0.1): ");
        String serverIp = scanner.nextLine().trim();
        if (serverIp.isBlank()) {
            serverIp = "127.0.0.1";
        }

        String signalingUrl = signalUrlFromServerIp(serverIp);
        Path downloadDirectory = Path.of(env("CHAT_DOWNLOAD_DIR", "./downloads"));
        System.out.println("1. Create Room");
        System.out.println("2. Join Room");
        String choice = scanner.nextLine().trim();

        if ("1".equals(choice)) {
            System.out.println("Creating room... waiting for code.");
            createRoom(peerId, "auto", downloadDirectory, signalingUrl, scanner);
            return;
        }

        if ("2".equals(choice)) {
            System.out.print("Enter Code: ");
            String roomId = scanner.nextLine().trim();
            if (roomId.isBlank()) {
                System.out.println("[error] room code is required");
                return;
            }
            joinRoom(peerId, roomId, downloadDirectory, signalingUrl, scanner);
            return;
        }

        System.out.println("[error] unknown choice: " + choice);
    }

    private void createRoom(String peerId, String roomId, Path downloadDirectory, String signalingUrl) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
            signal.sendToServer("create-room", Map.of("roomId", roomId));
            SignalMessage created = signal.waitFor("room-created", SIGNAL_TIMEOUT);
            String actualRoomId = created.payload().path("roomId").asText(roomId);
            System.out.println("[room] created " + actualRoomId + " as " + peerId);

            RoomChatApp app = new RoomChatApp(signal, peerId, downloadDirectory);
            app.start();
            app.runConsole(new Scanner(System.in));
        }
    }

    private void createRoom(String peerId, String roomId, Path downloadDirectory, String signalingUrl,
                            Scanner scanner) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
            signal.sendToServer("create-room", Map.of("roomId", roomId));
            SignalMessage created = signal.waitFor("room-created", SIGNAL_TIMEOUT);
            String actualRoomId = created.payload().path("roomId").asText(roomId);
            System.out.println("[room] created " + actualRoomId + " as " + peerId);

            RoomChatApp app = new RoomChatApp(signal, peerId, downloadDirectory);
            app.start();
            app.runConsole(scanner);
        }
    }

    private void joinRoom(String peerId, String roomId, Path downloadDirectory, String signalingUrl) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
            signal.sendToServer("join-room", Map.of("roomId", roomId));
            SignalMessage joined = signal.waitFor("room-joined", SIGNAL_TIMEOUT);
            String actualRoomId = joined.payload().path("roomId").asText(roomId);
            System.out.println("[room] joined " + actualRoomId + " as " + peerId);

            RoomChatApp app = new RoomChatApp(signal, peerId, downloadDirectory);
            app.start();
            for (String existingPeer : peersFrom(joined.payload().path("peers"))) {
                app.connectToPeer(existingPeer);
            }
            app.runConsole(new Scanner(System.in));
        }
    }

    private void joinRoom(String peerId, String roomId, Path downloadDirectory, String signalingUrl,
                          Scanner scanner) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
            signal.sendToServer("join-room", Map.of("roomId", roomId));
            SignalMessage joined = signal.waitFor("room-joined", SIGNAL_TIMEOUT);
            String actualRoomId = joined.payload().path("roomId").asText(roomId);
            System.out.println("[room] joined " + actualRoomId + " as " + peerId);

            RoomChatApp app = new RoomChatApp(signal, peerId, downloadDirectory);
            app.start();
            for (String existingPeer : peersFrom(joined.payload().path("peers"))) {
                app.connectToPeer(existingPeer);
            }
            app.runConsole(scanner);
        }
    }

    private void listen(String peerId, Path downloadDirectory, String signalingUrl) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
            acceptChat(signal, peerId, downloadDirectory);
        }
    }

    private void connect(String peerId, String targetPeerId, Path downloadDirectory, String signalingUrl) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
            connectChat(signal, peerId, targetPeerId, downloadDirectory);
        }
    }

    private void acceptChat(PeerSignalClient signal, String peerId, Path downloadDirectory) throws Exception {
        System.out.println("[chat] waiting for offer as " + peerId);
        SignalMessage offerSignal = signal.waitFor("chat-offer", SIGNAL_TIMEOUT);
        IceDescription offer = objectMapper.treeToValue(offerSignal.payload(), IceDescription.class);

        IceSession session = ice.createSession(peerId, false, iceConfig());
        ice.setRemoteDescription(session, offer);
        signal.send("chat-answer", offerSignal.from(), session.localDescription());

        System.out.println("[chat] establishing ICE path");
        IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
        System.out.println("[chat] ICE selected " + iceConnection.remoteAddress() + ":" + iceConnection.remotePort());

        CountDownLatch accepted = new CountDownLatch(1);
        AtomicReference<ChatSession> chatRef = new AtomicReference<>();
        try (InputStream cert = QuicCertificateFiles.certificate();
             InputStream key = QuicCertificateFiles.privateKey()) {
            quic.accept(iceConnection.socket(), cert, key, (input, output) -> {
                ChatSession chat = new ChatSession(peerId, input, output, downloadDirectory);
                chat.expectHello();
                chat.startReader();
                chatRef.set(chat);
                accepted.countDown();
                try {
                    chat.waitUntilClosed();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            System.out.println("[chat] waiting for QUIC chat stream");
            accepted.await();
            System.out.println("[chat] connected. Type text, /file <path>, /save <id>, or /quit.");
            runDirectConsole(chatRef.get());
        } finally {
            ChatSession chat = chatRef.get();
            if (chat != null) {
                chat.close();
            }
            ice.free(session);
        }
    }

    private void connectChat(PeerSignalClient signal, String peerId, String targetPeerId, Path downloadDirectory) throws Exception {
        IceSession session = ice.createSession(peerId, true, iceConfig());
        signal.send("chat-offer", targetPeerId, session.localDescription());
        IceDescription answer = signal.waitForPayload("chat-answer", IceDescription.class, SIGNAL_TIMEOUT);
        ice.setRemoteDescription(session, answer);

        System.out.println("[chat] establishing ICE path");
        IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
        System.out.println("[chat] ICE selected " + iceConnection.remoteAddress() + ":" + iceConnection.remotePort());

        try (QuicChannel channel = quic.connect(
                iceConnection.socket(),
                iceConnection.remoteAddress(),
                iceConnection.remotePort());
             QuicChannel.TransferStream stream = channel.openStream()) {
            ChatSession chat = new ChatSession(peerId, stream.input(), stream.output(), downloadDirectory);
            chat.sendHello();
            chat.startReader();
            System.out.println("[chat] connected. Type text, /file <path>, /save <id>, or /quit.");
            runDirectConsole(chat);
        } finally {
            ice.free(session);
        }
    }

    private void runDirectConsole(ChatSession chat) throws Exception {
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.equals("/quit")) {
                    chat.close();
                    return;
                }
                try {
                    if (line.equals("/file") || line.startsWith("/file ")) {
                        String path = argument(line, "/file");
                        if (path.isBlank()) {
                            System.out.println("[file] usage: /file <path>");
                            continue;
                        }
                        chat.offerFile(Path.of(path));
                        continue;
                    }
                    if (line.equals("/save") || line.startsWith("/save ")) {
                        String id = argument(line, "/save");
                        if (id.isBlank()) {
                            System.out.println("[file] usage: /save <offer-id>");
                            continue;
                        }
                        chat.requestFile(id);
                        continue;
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    chat.sendText(line);
                } catch (Exception e) {
                    System.out.println("[error] " + userMessage(e));
                }
            }
        }
    }

    private final class RoomChatApp {
        private final PeerSignalClient signal;
        private final String peerId;
        private final Path downloadDirectory;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final Map<String, ChatSession> sessionsByPeer = new ConcurrentHashMap<>();
        private final Map<String, CompletableFuture<IceDescription>> pendingAnswers = new ConcurrentHashMap<>();
        private final Set<String> connectingPeers = ConcurrentHashMap.newKeySet();
        private volatile boolean running = true;

        private RoomChatApp(PeerSignalClient signal, String peerId, Path downloadDirectory) {
            this.signal = signal;
            this.peerId = peerId;
            this.downloadDirectory = downloadDirectory;
        }

        private void start() {
            Thread signalThread = new Thread(this::signalLoop, "room-chat-signal-" + peerId);
            signalThread.setDaemon(true);
            signalThread.start();
            System.out.println("[chat] room app ready. Type text, /file <path>, /save <id>, or /quit.");
        }

        private void runConsole(Scanner scanner) throws Exception {
            try {
                while (running && scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.equals("/quit")) {
                        close();
                        return;
                    }
                    try {
                        if (line.equals("/file") || line.startsWith("/file ")) {
                            String path = argument(line, "/file");
                            if (path.isBlank()) {
                                System.out.println("[file] usage: /file <path>");
                                continue;
                            }
                            offerFile(Path.of(path));
                            continue;
                        }
                        if (line.equals("/save") || line.startsWith("/save ")) {
                            String id = argument(line, "/save");
                            if (id.isBlank()) {
                                System.out.println("[file] usage: /save <offer-id>");
                                continue;
                            }
                            requestFile(id);
                            continue;
                        }
                        if (line.isBlank()) {
                            continue;
                        }
                        sendText(line);
                    } catch (Exception e) {
                        System.out.println("[error] " + userMessage(e));
                    }
                }
            } finally {
                close();
            }
        }

        private void signalLoop() {
            while (running) {
                try {
                    SignalMessage message = signal.nextSignal(SIGNAL_POLL);
                    if (message == null) {
                        continue;
                    }
                    switch (message.type()) {
                        case "room-peer-joined" -> {
                            String joinedPeer = message.payload().path("peerId").asText();
                            if (!joinedPeer.isBlank()) {
                                System.out.println("[room] " + joinedPeer + " joined; waiting for direct offer");
                            }
                        }
                        case "chat-offer" -> acceptPeer(message);
                        case "chat-answer" -> completeAnswer(message);
                        case "room-relay" -> receiveRelay(message);
                        case "error" -> System.out.println("[signal] " + message.payload());
                        default -> {
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    if (running) {
                        System.out.println("[signal] " + e.getMessage());
                    }
                }
            }
        }

        private void connectToPeer(String remotePeerId) {
            if (remotePeerId == null || remotePeerId.isBlank() || remotePeerId.equals(peerId)) {
                return;
            }
            if (sessionsByPeer.containsKey(remotePeerId) || !connectingPeers.add(remotePeerId)) {
                return;
            }
            executor.submit(() -> {
                IceSession session = null;
                try {
                    session = ice.createSession(peerId, true, iceConfig());
                    CompletableFuture<IceDescription> answerFuture = new CompletableFuture<>();
                    pendingAnswers.put(remotePeerId, answerFuture);
                    signal.send("chat-offer", remotePeerId, session.localDescription());
                    IceDescription answer = answerFuture.get(SIGNAL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                    ice.setRemoteDescription(session, answer);

                    System.out.println("[chat] establishing ICE path to " + remotePeerId);
                    IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
                    try (QuicChannel channel = quic.connect(
                            iceConnection.socket(),
                            iceConnection.remoteAddress(),
                            iceConnection.remotePort());
                         QuicChannel.TransferStream stream = channel.openStream()) {
                        ChatSession chat = new ChatSession(peerId, stream.input(), stream.output(), downloadDirectory);
                        chat.sendHello();
                        chat.startReader();
                        sessionsByPeer.put(remotePeerId, chat);
                        System.out.println("[System] Direct Link established with " + remotePeerId);
                        chat.waitUntilClosed();
                    }
                } catch (Exception e) {
                    if (running) {
                        System.out.println("[chat] direct link to " + remotePeerId + " failed: " + e.getMessage());
                    }
                } finally {
                    connectingPeers.remove(remotePeerId);
                    pendingAnswers.remove(remotePeerId);
                    sessionsByPeer.remove(remotePeerId);
                    if (session != null) {
                        ice.free(session);
                    }
                }
            });
        }

        private void acceptPeer(SignalMessage offerSignal) {
            String remotePeerId = offerSignal.from();
            if (remotePeerId == null || remotePeerId.isBlank() || remotePeerId.equals(peerId)) {
                return;
            }
            executor.submit(() -> {
                IceSession session = null;
                AtomicReference<ChatSession> chatRef = new AtomicReference<>();
                try {
                    IceDescription offer = objectMapper.treeToValue(offerSignal.payload(), IceDescription.class);
                    session = ice.createSession(peerId, false, iceConfig());
                    ice.setRemoteDescription(session, offer);
                    signal.send("chat-answer", remotePeerId, session.localDescription());

                    System.out.println("[chat] establishing ICE path to " + remotePeerId);
                    IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
                    CountDownLatch accepted = new CountDownLatch(1);
                    try (InputStream cert = QuicCertificateFiles.certificate();
                         InputStream key = QuicCertificateFiles.privateKey()) {
                        quic.accept(iceConnection.socket(), cert, key, (input, output) -> {
                            ChatSession chat = new ChatSession(peerId, input, output, downloadDirectory);
                            chat.expectHello();
                            chat.startReader();
                            chatRef.set(chat);
                            sessionsByPeer.put(remotePeerId, chat);
                            System.out.println("[System] Direct Link established with " + remotePeerId);
                            accepted.countDown();
                            try {
                                chat.waitUntilClosed();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                        accepted.await();
                        ChatSession chat = chatRef.get();
                        if (chat != null) {
                            chat.waitUntilClosed();
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        System.out.println("[chat] direct link from " + remotePeerId + " failed: " + e.getMessage());
                    }
                } finally {
                    ChatSession chat = chatRef.get();
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
                System.out.println("[" + sender + "](Relay): " + content);
            }
        }

        private void sendText(String text) throws Exception {
            for (ChatSession session : sessionsByPeer.values()) {
                session.sendText(text);
            }
            signal.sendToServer("room-relay", Map.of("message", text));
        }

        private void offerFile(Path path) throws Exception {
            if (sessionsByPeer.isEmpty()) {
                System.out.println("[file] no direct peers connected yet");
                return;
            }
            for (ChatSession session : sessionsByPeer.values()) {
                session.offerFile(path);
            }
        }

        private void requestFile(String id) throws Exception {
            if (sessionsByPeer.isEmpty()) {
                System.out.println("[file] no direct peers connected yet");
                return;
            }
            for (ChatSession session : sessionsByPeer.values()) {
                session.requestFile(id);
            }
        }

        private void close() {
            running = false;
            for (ChatSession session : sessionsByPeer.values()) {
                session.close();
            }
            executor.shutdownNow();
        }
    }

    private static Set<String> peersFrom(JsonNode peersNode) {
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

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String envAllowBlank(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static String defaultSignalUrl() {
        return env("SIGNALING_URL", "ws://127.0.0.1:8080/signal");
    }

    private static String signalUrlFromServerIp(String serverIp) {
        if (serverIp.startsWith("ws://") || serverIp.startsWith("wss://")) {
            return serverIp.endsWith("/signal") ? serverIp : serverIp + "/signal";
        }
        if (serverIp.contains(":")) {
            return "ws://" + serverIp + "/signal";
        }
        String port = env("SIGNALING_PORT", "8080");
        return "ws://" + serverIp + ":" + port + "/signal";
    }

    private static String argument(String line, String command) {
        return line.length() <= command.length() ? "" : line.substring(command.length()).trim();
    }

    private static String userMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  java ... p2p.chat.P2pChatCli create <peerId> <roomId|auto> <downloadDir> [ws://host:8080/signal]");
        System.out.println("  java ... p2p.chat.P2pChatCli join <peerId> <roomId> <downloadDir> [ws://host:8080/signal]");
        System.out.println("  java ... p2p.chat.P2pChatCli listen <peerId> <downloadDir> [ws://host:8080/signal]");
        System.out.println("  java ... p2p.chat.P2pChatCli connect <peerId> <targetPeerId> <downloadDir> [ws://host:8080/signal]");
    }
}
