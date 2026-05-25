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
java -jar target/java-p2p.jar --server.port=18080
```

The signaling server exposes:

- `GET /health`
- `WS /signal?peerId=<peer-id>`

Check that it is running before starting peers:

```sh
curl http://127.0.0.1:18080/health
```

## Transfer a File

Use three terminals.

Terminal 1, signaling server:

```sh
java -jar target/java-p2p.jar --server.port=18080
```

Terminal 2, receiver:

```sh
mvn exec:java \
  -Dexec.mainClass=p2p.peer.P2pPeerCli \
  -Dexec.args="listen bob ./received ws://127.0.0.1:18080/signal"
```

Terminal 3, sender:

```sh
mvn exec:java \
  -Dexec.mainClass=p2p.peer.P2pPeerCli \
  -Dexec.args="send alice bob ./some-file.txt ws://127.0.0.1:18080/signal"
```

The peer CLI uses UDP ports `50000-51000` for ICE candidates by default.
Override this range when needed. When running two peers on the same host, give
the second process a different preferred port inside the same range:

```sh
P2P_UDP_MIN_PORT=52000 P2P_UDP_MAX_PORT=53000 P2P_UDP_PREFERRED_PORT=52010 mvn exec:java \
  -Dexec.mainClass=p2p.peer.P2pPeerCli \
  -Dexec.args="listen bob ./received ws://127.0.0.1:18080/signal"
```

For a local-only test, disable the default public STUN server:

```sh
STUN_SERVER= mvn exec:java \
  -Dexec.mainClass=p2p.peer.P2pPeerCli \
  -Dexec.args="send alice bob ./some-file.txt ws://127.0.0.1:18080/signal"
```

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

## Remaining Work

1. Configure coturn on the VPS and set `TURN_SERVER`, `TURN_USERNAME`, and `TURN_PASSWORD`.
2. Persist transfer state if you want resume after process restart.
3. Replace the bundled sample QUIC certificate before using this outside a demo environment.
