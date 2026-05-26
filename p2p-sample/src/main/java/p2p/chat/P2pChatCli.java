package p2p.chat;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

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
        quietLibraryLogs();
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
            ChatConsole.error(userMessage(e));
        }
    }

    private void runInteractive() throws Exception {
        Scanner scanner = new Scanner(System.in);
        System.out.println("=== P2P Chat Client (Smart Hybrid) ===");

        System.out.print("Enter your name: ");
        String peerId = scanner.nextLine().trim();
        if (peerId.isBlank()) {
            ChatConsole.error("name is required");
            return;
        }

        System.out.print("Enter Server IP (default 127.0.0.1:18080): ");
        String serverIp = scanner.nextLine().trim();
        if (serverIp.isBlank()) {
            serverIp = "127.0.0.1:18080";
        }

        String signalingUrl = signalUrlFromServerIp(serverIp);
        Path downloadDirectory = Path.of(env("CHAT_DOWNLOAD_DIR", "./downloads"));
        System.out.println("1. Create Room");
        System.out.println("2. Join Room");
        String choice = scanner.nextLine().trim();

        if ("1".equals(choice)) {
            ChatConsole.system("creating room... waiting for code.");
            createRoom(peerId, "auto", downloadDirectory, signalingUrl, scanner);
            return;
        }

        if ("2".equals(choice)) {
            System.out.print("Enter Code: ");
            String roomId = scanner.nextLine().trim();
            if (roomId.isBlank()) {
                ChatConsole.error("room code is required");
                return;
            }
            joinRoom(peerId, roomId, downloadDirectory, signalingUrl, scanner);
            return;
        }

        ChatConsole.error("unknown choice: " + choice);
    }

    private void createRoom(String peerId, String roomId, Path downloadDirectory, String signalingUrl) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
            signal.sendToServer("create-room", Map.of("roomId", roomId));
            SignalMessage created = signal.waitFor("room-created", SIGNAL_TIMEOUT);
            String actualRoomId = created.payload().path("roomId").asText(roomId);
            ChatConsole.system("room created: " + actualRoomId + " as " + peerId);

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
            ChatConsole.system("room created: " + actualRoomId + " as " + peerId);

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
            ChatConsole.system("joined room " + actualRoomId + " as " + peerId);

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
            ChatConsole.system("joined room " + actualRoomId + " as " + peerId);

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
        ChatConsole.system("waiting for offer as " + peerId);
        SignalMessage offerSignal = signal.waitFor("chat-offer", SIGNAL_TIMEOUT);
        IceDescription offer = objectMapper.treeToValue(offerSignal.payload(), IceDescription.class);

        IceSession session = ice.createSession(peerId, false, iceConfig());
        ice.setRemoteDescription(session, offer);
        signal.send("chat-answer", offerSignal.from(), session.localDescription());

        ChatConsole.system("establishing ICE path");
        IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
        ChatConsole.system("ICE selected " + iceConnection.remoteAddress() + ":" + iceConnection.remotePort());

        CountDownLatch accepted = new CountDownLatch(1);
        AtomicReference<ChatSession> chatRef = new AtomicReference<>();
        try (InputStream cert = QuicCertificateFiles.certificate();
             InputStream key = QuicCertificateFiles.privateKey()) {
            quic.accept(iceConnection.socket(), cert, key, (input, output) -> {
                ChatSession chat = new ChatSession(peerId, offerSignal.from(), input, output, downloadDirectory);
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

            ChatConsole.system("waiting for QUIC chat stream");
            accepted.await();
            ChatConsole.system("connected. Type text, /file <path>, /save <id>, or /quit.");
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

        ChatConsole.system("establishing ICE path");
        IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
        ChatConsole.system("ICE selected " + iceConnection.remoteAddress() + ":" + iceConnection.remotePort());

        try (QuicChannel channel = quic.connect(
                iceConnection.socket(),
                iceConnection.remoteAddress(),
                iceConnection.remotePort());
             QuicChannel.TransferStream stream = channel.openStream()) {
            ChatSession chat = new ChatSession(peerId, targetPeerId, stream.input(), stream.output(), downloadDirectory);
            chat.sendHello();
            chat.startReader();
            ChatConsole.system("connected. Type text, /file <path>, /save <id>, or /quit.");
            runDirectConsole(chat);
        } finally {
            ice.free(session);
        }
    }

    private void runDirectConsole(ChatSession chat) throws Exception {
        try (Scanner scanner = new Scanner(System.in);
             ChatInput input = new ChatInput(scanner)) {
            while (true) {
                ChatConsole.prompt();
                String line = input.readLine();
                if (line == null) {
                    ChatConsole.acceptedInput();
                    return;
                }
                ChatConsole.acceptedInput();
                if (line.equals("/quit")) {
                    chat.close();
                    return;
                }
                try {
                    if (line.equals("/help")) {
                        ChatConsole.help();
                        continue;
                    }
                    if (line.equals("/file") || line.startsWith("/file ")) {
                        String path = argument(line, "/file");
                        if (path.isBlank()) {
                            ChatConsole.file("usage: /file <path>");
                            continue;
                        }
                        chat.offerFile(Path.of(path));
                        continue;
                    }
                    if (line.equals("/save") || line.startsWith("/save ")) {
                        String id = argument(line, "/save");
                        if (id.isBlank()) {
                            ChatConsole.file("usage: /save <offer-id>");
                            continue;
                        }
                        chat.requestFile(id);
                        continue;
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    if (line.startsWith("/")) {
                        ChatConsole.error("unknown command: " + firstToken(line) + ". Type /help");
                        continue;
                    }
                    chat.sendText(line);
                } catch (Exception e) {
                    ChatConsole.error(userMessage(e));
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
        private final Map<String, Path> relayLocalOffers = new ConcurrentHashMap<>();
        private final Map<String, RelayRemoteOffer> relayRemoteOffers = new ConcurrentHashMap<>();
        private final Map<String, RelayIncomingFile> relayIncomingFiles = new ConcurrentHashMap<>();
        private final Set<String> connectingPeers = ConcurrentHashMap.newKeySet();
        private volatile boolean running = true;
        private static final int RELAY_FILE_CHUNK_SIZE = 3 * 1024;

        private RoomChatApp(PeerSignalClient signal, String peerId, Path downloadDirectory) {
            this.signal = signal;
            this.peerId = peerId;
            this.downloadDirectory = downloadDirectory;
        }

        private void start() {
            Thread signalThread = new Thread(this::signalLoop, "room-chat-signal-" + peerId);
            signalThread.setDaemon(true);
            signalThread.start();
            ChatConsole.system("room app ready. Type text, /file <path>, /save <id>, or /quit.");
        }

        private void runConsole(Scanner scanner) throws Exception {
            try (ChatInput input = new ChatInput(scanner)) {
                while (running) {
                    ChatConsole.prompt();
                    String line = input.readLine();
                    if (line == null) {
                        ChatConsole.acceptedInput();
                        return;
                    }
                    ChatConsole.acceptedInput();
                    if (line.equals("/quit")) {
                        close();
                        return;
                    }
                    try {
                        if (line.equals("/help")) {
                            ChatConsole.help();
                            continue;
                        }
                        if (line.equals("/file") || line.startsWith("/file ")) {
                            FileCommand command = parseFileCommand(argument(line, "/file"));
                            if (command == null) {
                                ChatConsole.file("usage: /file <path> [username]");
                                continue;
                            }
                            offerFile(command.path(), command.targetPeerId());
                            continue;
                        }
                        if (line.equals("/msg") || line.startsWith("/msg ")) {
                            PrivateMessage command = parsePrivateMessage(argument(line, "/msg"));
                            if (command == null) {
                                ChatConsole.notice("usage: /msg <username> <message>");
                                continue;
                            }
                            sendPrivateText(command.peerId(), command.message());
                            continue;
                        }
                        if (line.equals("/save") || line.startsWith("/save ")) {
                            String id = argument(line, "/save");
                            if (id.isBlank()) {
                                ChatConsole.file("usage: /save <offer-id>");
                                continue;
                            }
                            requestFile(id);
                            continue;
                        }
                        if (line.isBlank()) {
                            continue;
                        }
                        if (line.startsWith("/")) {
                            ChatConsole.error("unknown command: " + firstToken(line) + ". Type /help");
                            continue;
                        }
                        sendText(line);
                    } catch (Exception e) {
                        ChatConsole.error(userMessage(e));
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
                                ChatConsole.system(joinedPeer + " joined the room; waiting for direct offer");
                            }
                        }
                        case "room-peer-left" -> {
                            String leftPeer = message.payload().path("peerId").asText();
                            if (!leftPeer.isBlank()) {
                                removePeer(leftPeer);
                                ChatConsole.system(leftPeer + " left the room");
                            }
                        }
                        case "chat-offer" -> acceptPeer(message);
                        case "chat-answer" -> completeAnswer(message);
                        case "room-relay" -> receiveRelay(message);
                        case "room-private-relay" -> receivePrivateRelay(message);
                        case "room-file-offer" -> receiveRelayFileOffer(message);
                        case "room-file-request" -> receiveRelayFileRequest(message);
                        case "room-file-start" -> receiveRelayFileStart(message);
                        case "room-file-chunk" -> receiveRelayFileChunk(message);
                        case "room-file-end" -> receiveRelayFileEnd(message);
                        case "room-file-notice" -> ChatConsole.file(message.from() + ": "
                                + message.payload().path("message").asText(""));
                        case "error" -> ChatConsole.error("signal: " + signalErrorText(message));
                        default -> {
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    if (running) {
                        ChatConsole.error("signal: " + e.getMessage());
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

                    ChatConsole.system("establishing direct path to " + remotePeerId);
                    IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
                    try (QuicChannel channel = quic.connect(
                            iceConnection.socket(),
                            iceConnection.remoteAddress(),
                            iceConnection.remotePort());
                         QuicChannel.TransferStream stream = channel.openStream()) {
                        ChatSession chat = new ChatSession(peerId, remotePeerId, stream.input(), stream.output(),
                                downloadDirectory, () -> sessionsByPeer.remove(remotePeerId));
                        chat.sendHello();
                        chat.startReader();
                        sessionsByPeer.put(remotePeerId, chat);
                        ChatConsole.system("direct link established with " + remotePeerId);
                        chat.waitUntilClosed();
                    }
                } catch (Exception e) {
                    if (running && connectingPeers.contains(remotePeerId)) {
                        ChatConsole.notice("direct link to " + remotePeerId
                                + " unavailable; text will use relay fallback: " + userMessage(e));
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

                    ChatConsole.system("establishing direct path to " + remotePeerId);
                    IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
                    CountDownLatch accepted = new CountDownLatch(1);
                    try (InputStream cert = QuicCertificateFiles.certificate();
                         InputStream key = QuicCertificateFiles.privateKey()) {
                        quic.accept(iceConnection.socket(), cert, key, (input, output) -> {
                            ChatSession chat = new ChatSession(peerId, remotePeerId, input, output, downloadDirectory,
                                    () -> sessionsByPeer.remove(remotePeerId));
                            chat.expectHello();
                            chat.startReader();
                            chatRef.set(chat);
                            sessionsByPeer.put(remotePeerId, chat);
                            ChatConsole.system("direct link established with " + remotePeerId);
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
                        ChatConsole.notice("direct link from " + remotePeerId
                                + " unavailable; text will use relay fallback: " + userMessage(e));
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
                ChatConsole.relay(sender, content);
            }
        }

        private void receivePrivateRelay(SignalMessage message) {
            String content = message.payload().path("message").asText("");
            if (!content.isBlank()) {
                ChatConsole.privateRelay(message.from(), content);
            }
        }

        private void receiveRelayFileOffer(SignalMessage message) {
            JsonNode payload = message.payload();
            String id = payload.path("id").asText("");
            String fileName = sanitizeFileName(payload.path("fileName").asText(""));
            long size = payload.path("size").asLong(-1);
            String sender = message.from();
            if (id.isBlank() || fileName.isBlank() || size < 0 || sender == null || sender.isBlank()) {
                ChatConsole.file("ignored invalid file offer from " + sender);
                return;
            }
            relayRemoteOffers.put(id, new RelayRemoteOffer(sender, fileName, size));
            ChatConsole.file(sender + " offered " + id + " " + fileName + " (" + size + " bytes, relay)");
            ChatConsole.file("type /save " + id + " to download");
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
                    ChatConsole.error("file notice to " + requester + " failed: " + userMessage(e));
                }
                return;
            }
            executor.submit(() -> {
                try {
                    sendRelayFile(requester, id, path);
                } catch (Exception e) {
                    try {
                        sendRelayFileNotice(requester, "file relay failed for " + id + ": " + userMessage(e));
                    } catch (Exception ignored) {
                    }
                    ChatConsole.error("file relay to " + requester + " failed: " + userMessage(e));
                }
            });
        }

        private void receiveRelayFileStart(SignalMessage message) throws Exception {
            JsonNode payload = message.payload();
            String id = payload.path("id").asText("");
            String fileName = sanitizeFileName(payload.path("fileName").asText(""));
            long size = payload.path("size").asLong(-1);
            if (id.isBlank() || fileName.isBlank() || size < 0) {
                ChatConsole.file("ignored invalid file start from " + message.from());
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
            ChatConsole.file("receiving " + fileName + " from " + message.from() + " by relay");
        }

        private void receiveRelayFileChunk(SignalMessage message) throws Exception {
            JsonNode payload = message.payload();
            String id = payload.path("id").asText("");
            RelayIncomingFile incoming = relayIncomingFiles.get(id);
            if (incoming == null || !incoming.sender().equals(message.from())) {
                ChatConsole.file("ignored unexpected file chunk " + id + " from " + message.from());
                return;
            }
            byte[] chunk = Base64.getDecoder().decode(payload.path("data").asText(""));
            if (chunk.length > RELAY_FILE_CHUNK_SIZE) {
                throw new IllegalStateException("relay file chunk too large: " + chunk.length);
            }
            incoming.write(chunk);
        }

        private void receiveRelayFileEnd(SignalMessage message) throws Exception {
            String id = message.payload().path("id").asText("");
            RelayIncomingFile incoming = relayIncomingFiles.remove(id);
            if (incoming == null || !incoming.sender().equals(message.from())) {
                ChatConsole.file("ignored unexpected file end " + id + " from " + message.from());
                return;
            }
            incoming.close();
            if (incoming.received() != incoming.size()) {
                ChatConsole.error("file size mismatch for " + id + ": expected "
                        + incoming.size() + ", got " + incoming.received());
                return;
            }
            relayRemoteOffers.remove(id);
            ChatConsole.file("saved " + incoming.target());
        }

        private void removePeer(String remotePeerId) {
            ChatSession session = sessionsByPeer.remove(remotePeerId);
            if (session != null) {
                session.close();
            }
            pendingAnswers.remove(remotePeerId);
            connectingPeers.remove(remotePeerId);
        }

        private void sendText(String text) throws Exception {
            for (Map.Entry<String, ChatSession> entry : sessionsByPeer.entrySet()) {
                try {
                    entry.getValue().sendText(text);
                } catch (Exception e) {
                    sessionsByPeer.remove(entry.getKey());
                    ChatConsole.error("direct message to " + entry.getKey() + " failed: " + userMessage(e));
                }
            }
            signal.sendToServer("room-relay", Map.of("message", text));
        }

        private void sendPrivateText(String remotePeerId, String text) throws Exception {
            ChatSession session = sessionsByPeer.get(remotePeerId);
            if (session != null) {
                try {
                    session.sendText("(private) " + text);
                    ChatConsole.notice("private message sent to " + remotePeerId);
                    return;
                } catch (Exception e) {
                    sessionsByPeer.remove(remotePeerId);
                    ChatConsole.notice("direct private message to " + remotePeerId
                            + " failed; retrying by relay: " + userMessage(e));
                }
            }
            signal.sendToServer("room-private-relay", Map.of("to", remotePeerId, "message", text));
            ChatConsole.notice("private message relayed to " + remotePeerId);
        }

        private void offerFile(Path path, String remotePeerId) throws Exception {
            if (!Files.isRegularFile(path)) {
                ChatConsole.error("file does not exist: " + path);
                return;
            }
            offerRelayFile(path, remotePeerId);
        }

        private void offerRelayFile(Path path, String remotePeerId) throws Exception {
            String id = UUID.randomUUID().toString().substring(0, 8);
            relayLocalOffers.put(id, path);
            Map<String, Object> payload = remotePeerId == null || remotePeerId.isBlank()
                    ? Map.of("id", id, "fileName", path.getFileName().toString(), "size", Files.size(path))
                    : Map.of("id", id, "fileName", path.getFileName().toString(), "size", Files.size(path),
                            "to", remotePeerId);
            signal.sendToServer("room-file-offer", payload);
            if (remotePeerId == null || remotePeerId.isBlank()) {
                ChatConsole.file("offered " + path + " as " + id + " by relay");
            } else {
                ChatConsole.file("offered " + path + " as " + id + " to " + remotePeerId + " by relay");
            }
        }

        private void requestFile(String id) throws Exception {
            RelayRemoteOffer relayOffer = relayRemoteOffers.get(id);
            if (relayOffer != null) {
                signal.sendToServer("room-file-request", Map.of("to", relayOffer.sender(), "id", id));
                ChatConsole.file("requested " + id + " (" + relayOffer.fileName() + ") by relay");
                return;
            }
            if (sessionsByPeer.isEmpty()) {
                ChatConsole.file("unknown file offer: " + id);
                return;
            }
            for (Map.Entry<String, ChatSession> entry : sessionsByPeer.entrySet()) {
                try {
                    entry.getValue().requestFile(id);
                } catch (Exception e) {
                    sessionsByPeer.remove(entry.getKey());
                    ChatConsole.error("file request to " + entry.getKey() + " failed: " + userMessage(e));
                }
            }
        }

        private void sendRelayFile(String remotePeerId, String id, Path path) throws Exception {
            long size = Files.size(path);
            signal.sendToServer("room-file-start", Map.of(
                    "to", remotePeerId,
                    "id", id,
                    "fileName", path.getFileName().toString(),
                    "size", size));

            byte[] buffer = new byte[RELAY_FILE_CHUNK_SIZE];
            try (InputStream fileInput = Files.newInputStream(path)) {
                int read;
                while ((read = fileInput.read(buffer)) >= 0) {
                    byte[] chunk = read == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, read);
                    signal.sendToServer("room-file-chunk", Map.of(
                            "to", remotePeerId,
                            "id", id,
                            "data", Base64.getEncoder().encodeToString(chunk)));
                }
            }

            signal.sendToServer("room-file-end", Map.of("to", remotePeerId, "id", id));
            ChatConsole.file("sent " + path + " for " + id + " to " + remotePeerId + " by relay");
        }

        private void sendRelayFileNotice(String remotePeerId, String message) throws Exception {
            signal.sendToServer("room-file-notice", Map.of("to", remotePeerId, "message", message));
        }

        private void close() {
            running = false;
            for (ChatSession session : sessionsByPeer.values()) {
                session.close();
            }
            executor.shutdownNow();
        }
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        return Path.of(fileName).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
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
        String port = env("SIGNALING_PORT", "18080");
        return "ws://" + serverIp + ":" + port + "/signal";
    }

    private static String argument(String line, String command) {
        return line.length() <= command.length() ? "" : line.substring(command.length()).trim();
    }

    private static String firstToken(String line) {
        String trimmed = line.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private static void waitForConsoleInput() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(200);
    }

    private static PrivateMessage parsePrivateMessage(String value) {
        String[] parts = value.trim().split("\\s+", 2);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return new PrivateMessage(parts[0], parts[1]);
    }

    private static FileCommand parseFileCommand(String value) {
        String[] parts = value.trim().split("\\s+", 2);
        if (parts.length == 0 || parts[0].isBlank()) {
            return null;
        }
        String target = parts.length == 2 && !parts[1].isBlank() ? parts[1].trim() : null;
        if (target != null && target.contains(" ")) {
            return null;
        }
        return new FileCommand(Path.of(parts[0]), target);
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

    private record PrivateMessage(String peerId, String message) {
    }

    private record FileCommand(Path path, String targetPeerId) {
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  java ... p2p.chat.P2pChatCli create <peerId> <roomId|auto> <downloadDir> [ws://host:8080/signal]");
        System.out.println("  java ... p2p.chat.P2pChatCli join <peerId> <roomId> <downloadDir> [ws://host:8080/signal]");
        System.out.println("  java ... p2p.chat.P2pChatCli listen <peerId> <downloadDir> [ws://host:8080/signal]");
        System.out.println("  java ... p2p.chat.P2pChatCli connect <peerId> <targetPeerId> <downloadDir> [ws://host:8080/signal]");
    }

    private static void quietLibraryLogs() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.SEVERE);
        for (Handler handler : root.getHandlers()) {
            handler.setLevel(Level.SEVERE);
        }
        Logger.getLogger("org.jitsi").setLevel(Level.OFF);
        Logger.getLogger("org.jitsi.utils").setLevel(Level.OFF);
        Logger.getLogger("org.ice4j").setLevel(Level.OFF);
    }
}
