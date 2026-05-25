package p2p.ice;

public record IceCandidatePayload(
        String foundation,
        int componentId,
        String transport,
        long priority,
        String address,
        int port,
        String type
) {
}
