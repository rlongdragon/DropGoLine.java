package p2p.chat;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import p2p.ice.IceDescription;
import p2p.ice.IceNegotiationService;
import p2p.ice.IceNegotiationService.IceConnection;
import p2p.ice.IceNegotiationService.IceSession;
import p2p.ice.IceServerConfig;
import p2p.peer.PeerSignalClient;
import p2p.quic.QuicChannel;
import p2p.quic.QuicTransportService;
import p2p.signaling.SignalMessage;

public class P2pChatCli {
    private static final Duration SIGNAL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration ICE_TIMEOUT = Duration.ofSeconds(45);

    private final IceNegotiationService ice = new IceNegotiationService();
    private final QuicTransportService quic = new QuicTransportService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            usage();
            return;
        }

        P2pChatCli cli = new P2pChatCli();
        switch (args[0]) {
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
    }

    private void listen(String peerId, Path downloadDirectory, String signalingUrl) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
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
            try (InputStream cert = resource("/certs/quic-cert.pem");
                 InputStream key = resource("/certs/quic-key.pem")) {
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
                runConsole(chatRef.get());
            } finally {
                ChatSession chat = chatRef.get();
                if (chat != null) {
                    chat.close();
                }
                ice.free(session);
            }
        }
    }

    private void connect(String peerId, String targetPeerId, Path downloadDirectory, String signalingUrl) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
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
                runConsole(chat);
            } finally {
                ice.free(session);
            }
        }
    }

    private void runConsole(ChatSession chat) throws Exception {
        try (Scanner scanner = new Scanner(System.in)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.equals("/quit")) {
                    chat.close();
                    return;
                }
                if (line.startsWith("/file ")) {
                    chat.offerFile(Path.of(line.substring("/file ".length()).trim()));
                    continue;
                }
                if (line.startsWith("/save ")) {
                    chat.requestFile(line.substring("/save ".length()).trim());
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                chat.sendText(line);
            }
        }
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

    private static InputStream resource(String path) {
        InputStream stream = P2pChatCli.class.getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("missing resource " + path);
        }
        return stream;
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  java ... p2p.chat.P2pChatCli listen <peerId> <downloadDir> [ws://host:8080/signal]");
        System.out.println("  java ... p2p.chat.P2pChatCli connect <peerId> <targetPeerId> <downloadDir> [ws://host:8080/signal]");
    }
}
