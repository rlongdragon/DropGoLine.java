package p2p.ice;

import java.util.List;

public record IceDescription(
        String ufrag,
        String password,
        boolean controlling,
        List<IceCandidatePayload> candidates
) {
}
