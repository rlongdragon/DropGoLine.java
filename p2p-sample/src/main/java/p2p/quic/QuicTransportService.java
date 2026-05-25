package p2p.quic;

import java.net.DatagramSocket;

import org.springframework.stereotype.Service;

@Service
public class QuicTransportService {
    public QuicChannel connect(DatagramSocket iceSocket) {
        throw new UnsupportedOperationException("kwik integration is the next implementation step");
    }

    public QuicChannel accept(DatagramSocket iceSocket) {
        throw new UnsupportedOperationException("kwik integration is the next implementation step");
    }
}
