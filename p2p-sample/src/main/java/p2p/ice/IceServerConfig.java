package p2p.ice;

public record IceServerConfig(
        String stunServer,
        int stunPort,
        String turnServer,
        int turnPort,
        String turnUsername,
        String turnPassword
) {
}
