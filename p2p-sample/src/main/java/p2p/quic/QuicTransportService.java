package p2p.quic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.URI;
import java.net.URISyntaxException;

import net.luminis.quic.QuicClientConnection;
import net.luminis.quic.QuicConnection;
import net.luminis.quic.QuicStream;
import net.luminis.quic.log.NullLogger;
import net.luminis.quic.server.ApplicationProtocolConnection;
import net.luminis.quic.server.ApplicationProtocolConnectionFactory;
import net.luminis.quic.server.ServerConnectionConfig;
import net.luminis.quic.server.ServerConnector;

import org.springframework.stereotype.Service;

@Service
public class QuicTransportService {
    public static final String APPLICATION_PROTOCOL = "dropgoline-file/1";
    private static final NullLogger LOGGER = new NullLogger();

    public QuicChannel connect(DatagramSocket iceSocket, String remoteAddress, int remotePort) throws IOException {
        QuicClientConnection connection = QuicClientConnection.newBuilder()
                .uri(quicUri(remoteAddress, remotePort))
                .applicationProtocol(APPLICATION_PROTOCOL)
                .socketFactory(address -> iceSocket)
                .logger(LOGGER)
                .noServerCertificateCheck()
                .build();
        connection.connect();
        return new KwikClientChannel(connection);
    }

    public ServerConnector accept(DatagramSocket iceSocket, InputStream certificate, InputStream privateKey,
                                  IncomingStreamHandler handler) throws Exception {
        ServerConnectionConfig config = ServerConnectionConfig.builder()
                .maxOpenPeerInitiatedBidirectionalStreams(32)
                .maxBidirectionalStreamBufferSize(4L * 1024L * 1024L)
                .maxConnectionBufferSize(32L * 1024L * 1024L)
                .build();

        ServerConnector connector = ServerConnector.builder()
                .withPort(iceSocket.getLocalPort())
                .withSocket(iceSocket)
                .withCertificate(certificate, privateKey)
                .withConfiguration(config)
                .withLogger(LOGGER)
                .build();
        connector.registerApplicationProtocol(APPLICATION_PROTOCOL, new FileProtocolFactory(handler));
        connector.start();
        return connector;
    }

    private URI quicUri(String remoteAddress, int remotePort) throws IOException {
        try {
            return new URI("https", null, remoteAddress, remotePort, null, null, null);
        } catch (URISyntaxException e) {
            throw new IOException("invalid QUIC remote address " + remoteAddress + ":" + remotePort, e);
        }
    }

    public interface IncomingStreamHandler {
        void handle(InputStream input, OutputStream output) throws IOException;
    }

    private record FileProtocolFactory(IncomingStreamHandler handler) implements ApplicationProtocolConnectionFactory {
        @Override
        public ApplicationProtocolConnection createConnection(String protocol, QuicConnection quicConnection) {
            return new ApplicationProtocolConnection() {
                @Override
                public void acceptPeerInitiatedStream(QuicStream quicStream) {
                    new Thread(() -> handleStream(quicStream), "quic-file-stream").start();
                }
            };
        }

        @Override
        public int maxConcurrentPeerInitiatedUnidirectionalStreams() {
            return 0;
        }

        @Override
        public int maxConcurrentPeerInitiatedBidirectionalStreams() {
            return 32;
        }

        private void handleStream(QuicStream quicStream) {
            try {
                handler.handle(quicStream.getInputStream(), quicStream.getOutputStream());
            } catch (IOException e) {
                quicStream.resetStream(1);
            }
        }
    }

    private record KwikClientChannel(QuicClientConnection connection) implements QuicChannel {
        @Override
        public TransferStream openStream() {
            return new KwikTransferStream(connection.createStream(true));
        }

        @Override
        public void close() {
            connection.closeAndWait();
        }
    }

    private record KwikTransferStream(QuicStream stream) implements QuicChannel.TransferStream {
        @Override
        public InputStream input() {
            return stream.getInputStream();
        }

        @Override
        public OutputStream output() {
            return stream.getOutputStream();
        }

        @Override
        public void close() throws IOException {
            stream.getOutputStream().close();
            stream.getInputStream().close();
        }
    }
}
