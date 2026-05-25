# Java P2P

This project is being migrated from the original TCP chat prototype into a P2P
file-transfer stack.

## Current Stack

- Java 17+
- Maven
- Spring Boot WebSocket signaling server
- ice4j dependency for ICE/STUN/TURN NAT traversal
- kwik dependency for QUIC transport
- Netty transport dependency for event-driven network IO support
- Custom file-transfer protocol package for chunking, checksums, ACK state, and resume metadata
- PM2 for process hosting

## Build

```sh
mvn package
```

## Run Signaling Server

```sh
java -jar target/java-p2p-0.1.0-SNAPSHOT.jar
```

The signaling server exposes:

- `GET /health`
- `WS /signal?peerId=<peer-id>`

Example signaling message:

```json
{
  "type": "ice-candidate",
  "to": "bob",
  "payload": {
    "address": "203.0.113.10",
    "port": 50000,
    "transport": "udp"
  }
}
```

The server fills `from` from the sender's registered `peerId` and forwards the
message to the target peer.

## Legacy Prototype

The translated TCP chat prototype still exists under `src/main/java/chat`.
The new Spring Boot application entry point is `p2p.P2pApplication`.

## Next Implementation Steps

1. Replace `IceNegotiationService` scaffold with concrete ice4j candidate gathering and connectivity checks.
2. Replace `QuicTransportService` scaffold with concrete kwik client/server connection creation over the ICE-selected UDP path.
3. Connect `FileTransferProtocol` to QUIC streams and persist `TransferState` for reconnect/resume.
4. Configure coturn on the VPS and copy the credentials into `application.yml` or environment variables.
