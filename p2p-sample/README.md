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
- Reusable `p2p.api` facade for group membership, messaging, file offers, callbacks, and error callbacks
- Reusable `p2p.session` stream protocol for text, file offers, file chunks, notices, and keepalives
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

## Group Chat App With Optional File Save

The group chat sample keeps the same signaling, ICE, and QUIC connection stack.
It follows the original C# chat flow: one peer creates a group, other peers join
by group code, the signaling server introduces group peers, and clients build
direct QUIC links between peers. Text is also sent through server relay as a
backup path; relay messages from peers that already have a direct link are ignored.

Normal input is broadcast as text. `/msg <username> <message>` sends a private
message to one connected peer. `/file <path>` advertises a file offer to every
direct peer, and `/file <path> <username>` advertises it only to one peer. The
peer must type `/save <id>` before the file is sent.

Terminal 1, signaling server:

```sh
java -jar target/java-p2p.jar --server.port=18080
```

Terminal 2, start Bob's chat client:

```sh
SIGNALING_PORT=18080 STUN_SERVER= P2P_UDP_PREFERRED_PORT=50000 mvn exec:java \
  -Dexec.mainClass=sample.chat.P2pChatCli
```

Then answer the prompts:

```text
Enter your name: bob
Enter Server IP (default 127.0.0.1:18080):
1. Create Group
2. Join Group
1
```

If the signaling server is on another port, either set `SIGNALING_PORT` before
starting the client or type `127.0.0.1:8080` at the Server IP prompt.

Terminal 3, start Alice's chat client:

```sh
SIGNALING_PORT=18080 STUN_SERVER= P2P_UDP_PREFERRED_PORT=50010 mvn exec:java \
  -Dexec.mainClass=sample.chat.P2pChatCli
```

Then join with Bob's generated group code:

```text
Enter your name: alice
Enter Server IP (default 127.0.0.1:18080):
1. Create Group
2. Join Group
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
/msg alice hello privately
/file ./some-file.txt
/file ./some-file.txt alice
/save 1a2b3c4d
/quit
```

## High-Level P2P API

Other Java code can use the same group, direct-link, and relay stack
without depending on the chat CLI:

```java
try (P2p p2p = P2p.connect("alice", "ws://127.0.0.1:18080/signal", Path.of("./downloads"))) {
    String groupId = p2p.createGroup();
    P2pSessionInstance group = p2p.currentGroup();

    group.createReceivedListener(event -> {
        switch (event.type()) {
            case MESSAGE -> System.out.println(event.from() + ": " + event.message());
            case FILE_OFFER -> group.save(event.offerId());
            case FILE_SAVED -> System.out.println("saved " + event.file());
            default -> {
            }
        }
    }, (peerId, reason) -> System.out.println("connection notice for " + peerId + ": " + reason));

    group.send("hello");
    group.send("private hello", null, "bob");
    group.send(null, Path.of("./some-file.txt"), "bob");
}
```

Join an existing group:

```java
P2pSessionInstance group = p2p.joinGroup("JCMD");
Set<String> members = group.showMembers();
```

The chat CLI now uses this API for group `create` and `join`. The lower-level
`listen` and `connect` commands remain available for debugging direct streams.

Package roles:

- `p2p.api`: public API for other Java programs.
- `p2p.session`: reusable stream-level text/file protocol used by the API and debug chat commands.
- `p2p.signaling`, `p2p.ice`, and `p2p.quic`: reusable networking stack.
- `sample.chat`: console chat sample app, one consumer of the reusable P2P stack.
- `p2p.peer`: direct file-transfer CLI sample, another consumer of the reusable stack.

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

The translated TCP chat prototype still exists under `src/main/java/legacy/chat`.
The new Spring Boot application entry point is `p2p.P2pApplication`.

## Remaining Work

1. Configure coturn on the VPS and set `TURN_SERVER`, `TURN_USERNAME`, and `TURN_PASSWORD`.
2. Persist transfer state if you want resume after process restart.
3. Replace local demo QUIC certificates before using this outside a demo environment.
