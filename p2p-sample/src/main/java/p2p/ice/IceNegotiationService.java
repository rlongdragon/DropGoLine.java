package p2p.ice;

import java.beans.PropertyChangeEvent;
import java.net.DatagramSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.Candidate;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.NominationStrategy;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.ice.harvest.StunCandidateHarvester;
import org.ice4j.ice.harvest.TurnCandidateHarvester;
import org.ice4j.security.LongTermCredential;

import org.springframework.stereotype.Service;

@Service
public class IceNegotiationService {
    private static final int DEFAULT_MIN_PORT = 50000;
    private static final int DEFAULT_MAX_PORT = 51000;

    public IceSession createSession(String localPeerId, boolean controlling, IceServerConfig config) throws Exception {
        Ice4jConfigBridge.applyFromApplicationYaml();

        Agent agent = new Agent();
        agent.setControlling(controlling);
        agent.setNominationStrategy(NominationStrategy.NOMINATE_FIRST_VALID);

        if (config.stunServer() != null && !config.stunServer().isBlank()) {
            agent.addCandidateHarvester(new StunCandidateHarvester(
                    new TransportAddress(config.stunServer(), config.stunPort(), Transport.UDP)));
        }
        if (config.turnServer() != null && !config.turnServer().isBlank()
                && config.turnUsername() != null && !config.turnUsername().isBlank()
                && config.turnPassword() != null && !config.turnPassword().isBlank()) {
            agent.addCandidateHarvester(new TurnCandidateHarvester(
                    new TransportAddress(config.turnServer(), config.turnPort(), Transport.UDP),
                    new LongTermCredential(config.turnUsername(), config.turnPassword())));
        }

        IceMediaStream stream = agent.createMediaStream("data");
        UdpPortRange udpPorts = udpPortRange();
        agent.createComponent(stream, Transport.UDP, udpPorts.preferredPort(),
                udpPorts.minPort(), udpPorts.maxPort());

        List<IceCandidatePayload> candidates = describeCandidates(stream.getComponent(Component.RTP));
        IceDescription localDescription = new IceDescription(
                agent.getLocalUfrag(),
                agent.getLocalPassword(),
                controlling,
                candidates);
        return new IceSession(localPeerId, agent, stream, localDescription);
    }

    public void setRemoteDescription(IceSession session, IceDescription remoteDescription) {
        session.stream().setRemoteUfrag(remoteDescription.ufrag());
        session.stream().setRemotePassword(remoteDescription.password());

        Component component = session.stream().getComponent(Component.RTP);
        for (IceCandidatePayload payload : remoteDescription.candidates()) {
            TransportAddress address = new TransportAddress(payload.address(), payload.port(),
                    Transport.parse(payload.transport()));
            CandidateType type = CandidateType.parse(payload.type());
            component.addRemoteCandidate(new RemoteCandidate(
                    address,
                    component,
                    type,
                    payload.foundation(),
                    payload.priority(),
                    null));
        }
    }

    @SuppressWarnings("deprecation")
    public IceConnection establish(IceSession session, Duration timeout) throws Exception {
        CompletableFuture<IceProcessingState> finished = new CompletableFuture<>();
        session.agent().addStateChangeListener((PropertyChangeEvent event) -> {
            if (Agent.PROPERTY_ICE_PROCESSING_STATE.equals(event.getPropertyName())) {
                IceProcessingState state = (IceProcessingState) event.getNewValue();
                if (state.isOver() || state.isEstablished()) {
                    finished.complete(state);
                }
            }
        });

        session.agent().startConnectivityEstablishment();
        IceProcessingState state = finished.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!state.isEstablished()) {
            throw new IllegalStateException("ICE failed: " + state);
        }

        CandidatePair selectedPair = session.stream().getComponent(Component.RTP).getSelectedPair();
        if (selectedPair == null) {
            throw new IllegalStateException("ICE completed without a selected candidate pair");
        }
        TransportAddress remoteAddress = selectedPair.getRemoteCandidate().getTransportAddress();
        return new IceConnection(
                selectedPair.getDatagramSocket(),
                remoteAddress.getHostAddress(),
                remoteAddress.getPort());
    }

    public void free(IceSession session) {
        session.agent().free();
    }

    private List<IceCandidatePayload> describeCandidates(Component component) {
        List<IceCandidatePayload> result = new ArrayList<>();
        for (LocalCandidate candidate : component.getLocalCandidates()) {
            result.add(describeCandidate(component, candidate));
        }
        return result;
    }

    private IceCandidatePayload describeCandidate(Component component, Candidate<?> candidate) {
        TransportAddress address = candidate.getTransportAddress();
        return new IceCandidatePayload(
                candidate.getFoundation(),
                component.getComponentID(),
                candidate.getTransport().toString(),
                candidate.getPriority(),
                address.getHostAddress(),
                address.getPort(),
                candidate.getType().toString());
    }

    private static UdpPortRange udpPortRange() {
        int minPort = udpPort("P2P_UDP_MIN_PORT", DEFAULT_MIN_PORT);
        int maxPort = udpPort("P2P_UDP_MAX_PORT", DEFAULT_MAX_PORT);
        if (minPort > maxPort) {
            throw new IllegalArgumentException("P2P_UDP_MIN_PORT must be less than or equal to P2P_UDP_MAX_PORT");
        }
        int preferredPort = udpPort("P2P_UDP_PREFERRED_PORT", minPort);
        if (preferredPort < minPort || preferredPort > maxPort) {
            throw new IllegalArgumentException(
                    "P2P_UDP_PREFERRED_PORT must be between P2P_UDP_MIN_PORT and P2P_UDP_MAX_PORT");
        }
        return new UdpPortRange(preferredPort, minPort, maxPort);
    }

    private static int udpPort(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        int port = Integer.parseInt(value);
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException(name + " must be between 1024 and 65535");
        }
        return port;
    }

    private record UdpPortRange(
            int preferredPort,
            int minPort,
            int maxPort
    ) {
    }

    public record IceSession(
            String localPeerId,
            Agent agent,
            IceMediaStream stream,
            IceDescription localDescription
    ) {
    }

    public record IceConnection(
            DatagramSocket socket,
            String remoteAddress,
            int remotePort
    ) {
    }
}
