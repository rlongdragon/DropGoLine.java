package sample.chat;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import p2p.api.P2p;
import p2p.api.P2pEvent;
import p2p.api.P2pSessionInstance;
import p2p.ice.IceDescription;
import p2p.ice.IceNegotiationService;
import p2p.ice.IceNegotiationService.IceConnection;
import p2p.ice.IceNegotiationService.IceSession;
import p2p.ice.IceServerConfig;
import p2p.peer.PeerSignalClient;
import p2p.quic.QuicChannel;
import p2p.quic.QuicCertificateFiles;
import p2p.quic.QuicTransportService;
import p2p.session.P2pSession;
import p2p.signaling.SignalMessage;

public class P2pChatCli {
    private static final Duration SIGNAL_TIMEOUT = Duration.ofMinutes(5);
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
                    cli.createGroup(args[1], args[2], Path.of(args[3]), args.length >= 5 ? args[4] : defaultSignalUrl());
                }
                case "join" -> {
                    if (args.length < 4) {
                        usage();
                        return;
                    }
                    cli.joinGroup(args[1], args[2], Path.of(args[3]), args.length >= 5 ? args[4] : defaultSignalUrl());
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
        System.out.println("1. Create Group");
        System.out.println("2. Join Group");
        String choice = scanner.nextLine().trim();

        if ("1".equals(choice)) {
            ChatConsole.system("creating group... waiting for code.");
            createGroup(peerId, "auto", downloadDirectory, signalingUrl, scanner);
            return;
        }

        if ("2".equals(choice)) {
            System.out.print("Enter Code: ");
            String groupId = scanner.nextLine().trim();
            if (groupId.isBlank()) {
                ChatConsole.error("group code is required");
                return;
            }
            joinGroup(peerId, groupId, downloadDirectory, signalingUrl, scanner);
            return;
        }

        ChatConsole.error("unknown choice: " + choice);
    }

    private void createGroup(String peerId, String groupId, Path downloadDirectory, String signalingUrl) throws Exception {
        try (P2p p2p = P2p.connect(peerId, signalingUrl, downloadDirectory)) {
            String actualGroupId = p2p.createGroup(groupId);
            ChatConsole.system("group created: " + actualGroupId + " as " + peerId);
            P2pSessionInstance app = p2p.currentGroup();
            attachApiConsole(app);
            runApiConsole(app, new Scanner(System.in));
        }
    }

    private void createGroup(String peerId, String groupId, Path downloadDirectory, String signalingUrl,
                            Scanner scanner) throws Exception {
        try (P2p p2p = P2p.connect(peerId, signalingUrl, downloadDirectory)) {
            String actualGroupId = p2p.createGroup(groupId);
            ChatConsole.system("group created: " + actualGroupId + " as " + peerId);
            P2pSessionInstance app = p2p.currentGroup();
            attachApiConsole(app);
            runApiConsole(app, scanner);
        }
    }

    private void joinGroup(String peerId, String groupId, Path downloadDirectory, String signalingUrl) throws Exception {
        try (P2p p2p = P2p.connect(peerId, signalingUrl, downloadDirectory)) {
            P2pSessionInstance app = p2p.joinGroup(groupId);
            String actualGroupId = app.groupId();
            ChatConsole.system("joined group " + actualGroupId + " as " + peerId);
            attachApiConsole(app);
            runApiConsole(app, new Scanner(System.in));
        }
    }

    private void joinGroup(String peerId, String groupId, Path downloadDirectory, String signalingUrl,
                          Scanner scanner) throws Exception {
        try (P2p p2p = P2p.connect(peerId, signalingUrl, downloadDirectory)) {
            P2pSessionInstance app = p2p.joinGroup(groupId);
            String actualGroupId = app.groupId();
            ChatConsole.system("joined group " + actualGroupId + " as " + peerId);
            attachApiConsole(app);
            runApiConsole(app, scanner);
        }
    }

    private void attachApiConsole(P2pSessionInstance app) {
        app.createReceivedListener(event -> {
            if (event.type() == P2pEvent.Type.MESSAGE) {
                if (event.direct()) {
                    ChatConsole.incoming(event.from(), event.message());
                } else {
                    ChatConsole.relay(event.from(), event.message());
                }
                return;
            }
            if (event.type() == P2pEvent.Type.FILE_OFFER) {
                ChatConsole.file(event.from() + " offered " + event.offerId() + " " + event.fileName()
                        + " (" + event.fileSize() + " bytes" + (event.direct() ? "" : ", relay") + ")");
                ChatConsole.file("type /save " + event.offerId() + " to download");
                return;
            }
            if (event.type() == P2pEvent.Type.FILE_SAVED) {
                ChatConsole.file("saved " + event.file());
                return;
            }
            if (event.type() == P2pEvent.Type.NOTICE) {
                ChatConsole.notice(event.message());
                return;
            }
            if (event.type() == P2pEvent.Type.PEER_JOINED) {
                ChatConsole.system(event.from() + " joined the group");
                return;
            }
            if (event.type() == P2pEvent.Type.PEER_LEFT) {
                ChatConsole.system(event.from() + " left the group");
            }
        }, (remotePeerId, reason) -> ChatConsole.notice("connection notice for " + remotePeerId + ": " + reason));
    }

    private void runApiConsole(P2pSessionInstance app, Scanner scanner) throws Exception {
        ChatConsole.system("group app ready. Type text, /file <path>, /save <id>, or /quit.");
        try (ChatInput input = new ChatInput(scanner)) {
            while (true) {
                ChatConsole.prompt();
                String line = input.readLine();
                if (line == null) {
                    ChatConsole.acceptedInput();
                    return;
                }
                ChatConsole.acceptedInput();
                if (line.equals("/quit")) {
                    app.close();
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
                        app.send(null, command.path(), command.targetPeerId());
                        continue;
                    }
                    if (line.equals("/msg") || line.startsWith("/msg ")) {
                        PrivateMessage command = parsePrivateMessage(argument(line, "/msg"));
                        if (command == null) {
                            ChatConsole.notice("usage: /msg <username> <message>");
                            continue;
                        }
                        app.send(command.message(), null, command.peerId());
                        continue;
                    }
                    if (line.equals("/save") || line.startsWith("/save ")) {
                        String id = argument(line, "/save");
                        if (id.isBlank()) {
                            ChatConsole.file("usage: /save <offer-id>");
                            continue;
                        }
                        app.save(id);
                        continue;
                    }
                    if (line.isBlank()) {
                        continue;
                    }
                    if (line.startsWith("/")) {
                        ChatConsole.error("unknown command: " + firstToken(line) + ". Type /help");
                        continue;
                    }
                    app.send(line);
                } catch (Exception e) {
                    ChatConsole.error(userMessage(e));
                }
            }
        } finally {
            app.close();
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
        AtomicReference<P2pSession> chatRef = new AtomicReference<>();
        try (InputStream cert = QuicCertificateFiles.certificate();
             InputStream key = QuicCertificateFiles.privateKey()) {
            quic.accept(iceConnection.socket(), cert, key, (input, output) -> {
                P2pSession chat = new P2pSession(peerId, offerSignal.from(), input, output, downloadDirectory,
                        () -> {
                        }, consoleSessionListener());
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
            P2pSession chat = chatRef.get();
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
            P2pSession chat = new P2pSession(peerId, targetPeerId, stream.input(), stream.output(), downloadDirectory,
                    () -> {
                    }, consoleSessionListener());
            chat.sendHello();
            chat.startReader();
            ChatConsole.system("connected. Type text, /file <path>, /save <id>, or /quit.");
            runDirectConsole(chat);
        } finally {
            ice.free(session);
        }
    }

    private void runDirectConsole(P2pSession chat) throws Exception {
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
                        FileCommand command = parseFileCommand(argument(line, "/file"));
                        if (command == null || command.targetPeerId() != null) {
                            ChatConsole.file("usage: /file <path>");
                            continue;
                        }
                        chat.offerFile(command.path());
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

    private P2pSession.Listener consoleSessionListener() {
        return new P2pSession.Listener() {
            @Override
            public void onText(String fromPeerId, String text) {
                ChatConsole.incoming(fromPeerId, text);
            }

            @Override
            public void onFileOffer(String fromPeerId, String id, String fileName, long size) {
                ChatConsole.file(fromPeerId + " offered " + id + " " + fileName + " (" + size + " bytes)");
                ChatConsole.file("type /save " + id + " to download");
            }

            @Override
            public void onFileSaved(String fromPeerId, String id, Path target) {
                ChatConsole.file("saved " + target);
            }

            @Override
            public void onNotice(String fromPeerId, String message) {
                ChatConsole.notice(message);
            }

            @Override
            public void onDisconnected(String peerId) {
                ChatConsole.system(peerId + " disconnected");
            }

            @Override
            public void onError(String peerId, Exception error) {
                ChatConsole.error(userMessage(error));
            }
        };
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
        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return null;
        }

        ParsedPath parsed = parsePathToken(trimmed);
        if (parsed == null) {
            return null;
        }

        String target = parsed.remainder().isBlank() ? null : parsed.remainder();
        if (target != null && target.contains(" ")) {
            return null;
        }
        return new FileCommand(Path.of(parsed.path()), target);
    }

    private static ParsedPath parsePathToken(String value) {
        if (value.startsWith("\"")) {
            StringBuilder path = new StringBuilder();
            boolean escaping = false;
            for (int i = 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (escaping) {
                    path.append(c);
                    escaping = false;
                    continue;
                }
                if (c == '\\' && i + 1 < value.length() && value.charAt(i + 1) == '"') {
                    escaping = true;
                    continue;
                }
                if (c == '"') {
                    String remainder = value.substring(i + 1).trim();
                    return path.isEmpty() ? null : new ParsedPath(path.toString(), remainder);
                }
                path.append(c);
            }
            return null;
        }

        String[] parts = value.split("\\s+", 2);
        String path = parts[0].trim();
        String remainder = parts.length == 2 ? parts[1].trim() : "";
        return path.isBlank() ? null : new ParsedPath(path, remainder);
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

    private record ParsedPath(String path, String remainder) {
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  java ... sample.chat.P2pChatCli create <peerId> <groupId|auto> <downloadDir> [ws://host:8080/signal]");
        System.out.println("  java ... sample.chat.P2pChatCli join <peerId> <groupId> <downloadDir> [ws://host:8080/signal]");
        System.out.println("  java ... sample.chat.P2pChatCli listen <peerId> <downloadDir> [ws://host:8080/signal]");
        System.out.println("  java ... sample.chat.P2pChatCli connect <peerId> <targetPeerId> <downloadDir> [ws://host:8080/signal]");
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
