# Java P2P

這個專案正在從原本的 TCP 聊天 prototype，逐步整理成可重複使用的 P2P
檔案傳輸與訊息通訊堆疊。

## 目前架構

- Java 17+
- Maven
- Spring Boot WebSocket signaling server
- ice4j：處理 ICE/STUN/TURN NAT traversal
- kwik：QUIC transport
- Netty transport：事件驅動網路 IO 支援
- 可重複使用的 `p2p.api` facade：group 成員管理、訊息、檔案 offer、callback、錯誤 callback
- 可重複使用的 `p2p.session` stream protocol：文字、檔案 offer、檔案 chunk、notice、keepalive
- 自訂檔案傳輸 protocol：chunk、checksum、ACK state、resume metadata
- PM2：process hosting

## 建置

```sh
mvn package
```

## 本機 QUIC 憑證

不要把 private key commit 進 repo。執行會接受 QUIC 連線的 peer 前，先在被忽略的
`target/dev-certs` 產生本機 demo 憑證：

```sh
mkdir -p target/dev-certs
openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
  -keyout target/dev-certs/quic-key.pem \
  -out target/dev-certs/quic-cert.pem \
  -subj "/CN=localhost"
```

也可以改用自己的憑證檔：

```sh
QUIC_CERT_PATH=/secure/quic-cert.pem QUIC_KEY_PATH=/secure/quic-key.pem ...
```

## 啟動 Signaling Server

```sh
java -jar target/java-p2p.jar --server.port=18080
```

Signaling server 提供：

- `GET /health`
- `WS /signal?peerId=<peer-id>`

啟動 peers 前，可以先確認 server 正常：

```sh
curl http://127.0.0.1:18080/health
```

## 傳送檔案

使用三個 terminal。

Terminal 1，signaling server：

```sh
java -jar target/java-p2p.jar --server.port=18080
```

Terminal 2，接收端：

```sh
mvn exec:java \
  -Dexec.mainClass=p2p.peer.P2pPeerCli \
  -Dexec.args="listen bob ./received ws://127.0.0.1:18080/signal"
```

Terminal 3，傳送端：

```sh
mvn exec:java \
  -Dexec.mainClass=p2p.peer.P2pPeerCli \
  -Dexec.args="send alice bob ./some-file.txt ws://127.0.0.1:18080/signal"
```

Peer CLI 預設使用 UDP port `50000-51000` 作為 ICE candidates。需要時可以覆寫範圍。
如果在同一台主機跑兩個 peer，第二個 process 請指定同一範圍內不同的 preferred port：

```sh
P2P_UDP_MIN_PORT=52000 P2P_UDP_MAX_PORT=53000 P2P_UDP_PREFERRED_PORT=52010 mvn exec:java \
  -Dexec.mainClass=p2p.peer.P2pPeerCli \
  -Dexec.args="listen bob ./received ws://127.0.0.1:18080/signal"
```

如果只做本機測試，可以關掉預設 public STUN server：

```sh
STUN_SERVER= mvn exec:java \
  -Dexec.mainClass=p2p.peer.P2pPeerCli \
  -Dexec.args="send alice bob ./some-file.txt ws://127.0.0.1:18080/signal"
```

## Group Chat App 與選擇性檔案接收

Group chat sample 使用同一套 signaling、ICE、QUIC 連線堆疊。流程延續原本 C# chat：
一個 peer 建立 group，其他 peer 用 group code 加入，signaling server 介紹 group 內的
peers，client 之間再建立直接 QUIC link。文字訊息也會透過 server relay 作為備援路徑；
如果某個 peer 已經有 direct link，來自 relay 的重複訊息會被忽略。

一般輸入會 broadcast 成文字。`/msg <username> <message>` 會傳 private message 給單一
connected peer。`/file <path>` 會把檔案 offer 廣播給所有 direct peer，
`/file <path> <username>` 只 offer 給單一 peer。接收端必須輸入 `/save <id>` 才會開始接收檔案。

Terminal 1，signaling server：

```sh
java -jar target/java-p2p.jar --server.port=18080
```

Terminal 2，啟動 Bob 的 chat client：

```sh
SIGNALING_PORT=18080 STUN_SERVER= P2P_UDP_PREFERRED_PORT=50000 mvn exec:java \
  -Dexec.mainClass=sample.chat.P2pChatCli
```

接著回答提示：

```text
Enter your name: bob
Enter Server IP (default 127.0.0.1:18080):
1. Create Group
2. Join Group
1
```

如果 signaling server 在其他 port，可以在啟動 client 前設定 `SIGNALING_PORT`，或在
Server IP prompt 輸入像 `127.0.0.1:8080` 這樣的位址。

Terminal 3，啟動 Alice 的 chat client：

```sh
SIGNALING_PORT=18080 STUN_SERVER= P2P_UDP_PREFERRED_PORT=50010 mvn exec:java \
  -Dexec.mainClass=sample.chat.P2pChatCli
```

然後用 Bob 產生的 group code 加入：

```text
Enter your name: alice
Enter Server IP (default 127.0.0.1:18080):
1. Create Group
2. Join Group
2
Enter Code: JCMD
```

下載目錄預設是 `./downloads`。可以用 `CHAT_DOWNLOAD_DIR=./alice-downloads` 覆寫。

Parameterized commands 仍可用於 debug：
`create`、`join`、`listen`、`connect`。

Chat commands：

```text
hello
/msg alice hello privately
/file ./some-file.txt
/file ./some-file.txt alice
/save 1a2b3c4d
/quit
```

## 高階 P2P API

其他 Java 程式可以直接使用同一套 group、direct-link、relay stack，不需要依賴 chat CLI：

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

加入既有 group：

```java
P2pSessionInstance group = p2p.joinGroup("JCMD");
Set<String> members = group.showMembers();
```

Chat CLI 現在的 group `create` 和 `join` 會使用這套 API。較底層的 `listen` 和 `connect`
commands 仍保留，方便 debug direct streams。

Package 角色：

- `p2p.api`：提供給其他 Java 程式使用的 public API。
- `p2p.session`：可重複使用的 stream-level 文字/檔案 protocol，被 API 和 debug chat commands 使用。
- `p2p.signaling`、`p2p.ice`、`p2p.quic`：可重複使用的 networking stack。
- `sample.chat`：console chat sample app，是 P2P stack 的其中一個使用者。
- `p2p.peer`：direct file-transfer CLI sample，是另一個使用者。

檔案安全規則：接收端永遠不會把 filesystem path 傳給傳送端。`/save` 只會送 opaque offer id。
傳送端只有在同一個 process 中曾經用 `/file <path>` 建立過該 offer id，才會提供檔案。

Signaling message 範例：

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

Server 會從 sender 註冊的 `peerId` 填入 `from`，再把 message forward 給 target peer。

## Legacy Prototype

翻譯過來的 TCP chat prototype 仍保留在 `src/main/java/legacy/chat`。
新的 Spring Boot application entry point 是 `p2p.P2pApplication`。

## 待辦

1. 在 VPS 設定 coturn，並設定 `TURN_SERVER`、`TURN_USERNAME`、`TURN_PASSWORD`。
2. 如果需要 process restart 後繼續 resume，補上 transfer state persistence。
3. 在 demo 以外的環境使用前，替換本機 demo QUIC certificates。
