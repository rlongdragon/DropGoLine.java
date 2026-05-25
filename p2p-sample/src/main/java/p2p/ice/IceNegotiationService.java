package p2p.ice;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class IceNegotiationService {
    public IceSession createSession(String localPeerId, IceServerConfig config) {
        return new IceSession(localPeerId, config, List.of());
    }

    public record IceSession(
            String localPeerId,
            IceServerConfig config,
            List<IceCandidatePayload> localCandidates
    ) {
    }
}
