package p2p.ice;

public record IceCandidatePayload(
        String foundation,
        String component,
        String transport,
        long priority,
        String address,
        int port,
        String type
) {
}
