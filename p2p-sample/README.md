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

## Local QUIC Certificate

Do not commit private keys. Generate local demo certificate files under ignored
`target/dev-certs` before running a peer that accepts QUIC connections:

```sh
mkdir -p target/dev-certs
openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
  -keyout target/dev-certs/quic-key.pem \
  -out target/dev-certs/quic-cert.pem \
  -subj "/CN=localhost"
```

You can also point to your own files:

```sh
QUIC_CERT_PATH=/secure/quic-cert.pem QUIC_KEY_PATH=/secure/quic-key.pem ...
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

## Room Chat App With Optional File Save

The room chat sample keeps the same signaling, ICE, and QUIC connection stack.
It follows the original C# chat flow: one peer creates a room, other peers join
by room code, the signaling server introduces room peers, and clients build
direct QUIC links between peers. Text is also sent through server relay as a
fallback; relay messages from peers that already have a direct link are ignored.

Normal input is sent as text. `/file <path>` only advertises a file offer over
direct QUIC chat sessions. The peer must type `/save <id>` before the file is
sent.

Terminal 1, signaling server:

```sh
java -jar target/java-p2p.jar --server.port=18080
```

Terminal 2, start Bob's chat client:

```sh
SIGNALING_PORT=18080 STUN_SERVER= P2P_UDP_PREFERRED_PORT=50000 mvn exec:java \
  -Dexec.mainClass=p2p.chat.P2pChatCli
```

Then answer the prompts:

```text
Enter your name: bob
Enter Server IP (default 127.0.0.1):
1. Create Room
2. Join Room
1
```

If the signaling server is not on the default port, either set
`SIGNALING_PORT=18080` before starting the client or type
`127.0.0.1:18080` at the Server IP prompt.

Terminal 3, start Alice's chat client:

```sh
SIGNALING_PORT=18080 STUN_SERVER= P2P_UDP_PREFERRED_PORT=50010 mvn exec:java \
  -Dexec.mainClass=p2p.chat.P2pChatCli
```

Then join with Bob's generated room code:

```text
Enter your name: alice
Enter Server IP (default 127.0.0.1):
1. Create Room
2. Join Room
2
Enter Code: JCMD
```

Downloads go to `./downloads` by default. Override with
`CHAT_DOWNLOAD_DIR=./alice-downloads`.

Parameterized commands are still available for debugging:
`create`, `join`, `listen`, and `connect`.

Chat commands:

```text
hello
/file ./some-file.txt
/save 1a2b3c4d
/quit
```

File security rule: the receiver never sends a filesystem path to the sender.
`/save` sends only an opaque offer id. The sender serves a file only if that id
exists in its local history from an earlier `/file <path>` command in the same
process.

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
3. Replace local demo QUIC certificates before using this outside a demo environment.
