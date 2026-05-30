package p2p.peer;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import com.fasterxml.jackson.databind.ObjectMapper;

import p2p.ice.IceDescription;
import p2p.ice.IceNegotiationService;
import p2p.ice.IceNegotiationService.IceConnection;
import p2p.ice.IceNegotiationService.IceSession;
import p2p.ice.IceServerConfig;
import p2p.quic.QuicChannel;
import p2p.quic.QuicCertificateFiles;
import p2p.quic.QuicTransportService;
import p2p.signaling.SignalMessage;
import p2p.transfer.ChecksumService;
import p2p.transfer.FileTransferProtocol;
import p2p.transfer.FileTransferService;

public class P2pPeerCli {
    private static final Duration SIGNAL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration ICE_TIMEOUT = Duration.ofSeconds(45);

    private final IceNegotiationService ice = new IceNegotiationService();
    private final QuicTransportService quic = new QuicTransportService();
    private final FileTransferService transfer = new FileTransferService(
            new FileTransferProtocol(new ChecksumService()));
    private final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }

        P2pPeerCli cli = new P2pPeerCli();
        switch (args[0]) {
            case "listen" -> {
                if (args.length < 3) {
                    usage();
                    return;
                }
                cli.listen(args[1], Path.of(args[2]), args.length >= 4 ? args[3] : defaultSignalUrl());
            }
            case "send" -> {
                if (args.length < 4) {
                    usage();
                    return;
                }
                cli.send(args[1], args[2], Path.of(args[3]), args.length >= 5 ? args[4] : defaultSignalUrl());
            }
            default -> usage();
        }
    }

    private void listen(String peerId, Path outputDirectory, String signalingUrl) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
            System.out.println("[listen] waiting for offer as " + peerId);
            SignalMessage offerSignal = signal.waitFor("offer", SIGNAL_TIMEOUT);
            IceDescription offer = objectMapper.treeToValue(offerSignal.payload(), IceDescription.class);

            IceSession session = ice.createSession(peerId, false, iceConfig());
            ice.setRemoteDescription(session, offer);
            signal.send("answer", offerSignal.from(), session.localDescription());

            System.out.println("[listen] establishing ICE path");
            IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
            System.out.println("[listen] ICE selected " + iceConnection.remoteAddress() + ":" + iceConnection.remotePort());

            try (InputStream cert = QuicCertificateFiles.certificate();
                 InputStream key = QuicCertificateFiles.privateKey()) {
                CountDownLatch done = new CountDownLatch(1);
                quic.accept(iceConnection.socket(), cert, key, (input, output) -> {
                    Path received = transfer.receive(input, output, outputDirectory);
                    System.out.println("[listen] received " + received);
                    done.countDown();
                });
                System.out.println("[listen] QUIC server ready for one or more transfers");
                done.await();
            } finally {
                ice.free(session);
            }
        }
    }

    private void send(String peerId, String targetPeerId, Path file, String signalingUrl) throws Exception {
        try (PeerSignalClient signal = new PeerSignalClient(signalingUrl, peerId)) {
            IceSession session = ice.createSession(peerId, true, iceConfig());
            signal.send("offer", targetPeerId, session.localDescription());
            IceDescription answer = signal.waitForPayload("answer", IceDescription.class, SIGNAL_TIMEOUT);
            ice.setRemoteDescription(session, answer);

            System.out.println("[send] establishing ICE path");
            IceConnection iceConnection = ice.establish(session, ICE_TIMEOUT);
            System.out.println("[send] ICE selected " + iceConnection.remoteAddress() + ":" + iceConnection.remotePort());

            try (QuicChannel channel = quic.connect(
                    iceConnection.socket(),
                    iceConnection.remoteAddress(),
                    iceConnection.remotePort())) {
                transfer.send(file, channel);
                System.out.println("[send] transferred " + file);
            } finally {
                ice.free(session);
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

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  java ... p2p.peer.P2pPeerCli listen <peerId> <outputDir> [ws://host:8080/signal]");
        System.out.println("  java ... p2p.peer.P2pPeerCli send <peerId> <targetPeerId> <file> [ws://host:8080/signal]");
    }
}
