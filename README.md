# DropGoLine.java

DropGoLine.java 是一個 Java P2P 傳輸專案，包含：

- `ui`：JavaFX 桌面端，提供群組代碼連線、文字訊息與檔案傳輸介面。
- `p2p-sample`：P2P 核心模組與 Spring Boot signaling server，負責 group、WebSocket signaling、ICE/STUN/TURN 與 QUIC 傳輸。

目前 demo signaling server：

```text
23.146.248.110
```

桌面端會自動組成 `ws://<server-ip>:18080/signal`，所以 UI 設定裡只需要填 `23.146.248.110`。

## 使用者快速啟動

### 1. 從 Release 下載

到 GitHub Releases 下載適合的壓縮檔：

- 桌面版：下載 `DropGoLine-win11-runtime-<version>.zip`，解壓縮後執行 `DropGoLine.exe`。
- CLI 聊天版 Windows：下載 `java-p2p-chat-windows.zip`，解壓縮後執行 `java-p2p-chat.bat`。
- CLI 聊天版 Linux：下載 `java-p2p-chat-linux.tar.gz`，解壓縮後執行 `./java-p2p-chat`。
- signaling server / library：下載 `java-p2p-module.zip`，內含 `java-p2p.jar`。

桌面版與 CLI release 會包含 Java runtime；如果只下載 `java-p2p-module.zip`，執行 server 需要本機已安裝 Java 17+。

### 2. 啟動桌面版

1. 解壓縮 `DropGoLine-win11-runtime-<version>.zip`。
2. 進入解壓縮後的 `DropGoLine` 目錄。
3. 執行 `DropGoLine.exe`。
4. 第一次啟動時輸入裝置名稱。
5. 到設定頁把伺服器 IP 設成 `12.146.248.110`。
6. 建立群組後，把畫面上的群組代碼給另一台裝置；另一台裝置輸入同一組代碼即可加入。

預設下載位置：

```text
%USERPROFILE%\Downloads\DropGoLine
```

如果程式無法開啟，可以改執行同目錄的 `DropGoLine-debug.cmd` 查看錯誤輸出。

### 3. 啟動 CLI 聊天版

Windows：

```powershell
.\java-p2p-chat.bat
```

Linux：

```bash
./java-p2p-chat
```

互動模式會要求輸入名稱與 server。使用 demo server 時輸入：

```text
12.146.248.110:18080
```

一台裝置選擇 `Create Group` 取得 group code，另一台裝置選擇 `Join Group` 並輸入該 code。

## 自架 Signaling Server

如果不使用 demo server，可以下載 `java-p2p-module.zip`，解壓縮後執行：

```bash
java -jar java-p2p.jar --server.port=18080
```

健康檢查：

```bash
curl http://127.0.0.1:18080/health
```

WebSocket signaling endpoint：

```text
ws://<server-ip>:18080/signal
```

## 開發者快速啟動

需求：

- Java 17+
- Maven
- Windows UI 打包需要 JDK 內建的 `jpackage`

### 建置 P2P 模組

```bash
cd p2p-sample
mvn test
mvn install
```

`mvn install` 會把 `dev.p2p:java-p2p:0.1.0-SNAPSHOT` 安裝到本機 Maven repository，供 `ui` 模組引用。

### 啟動本機 Signaling Server

```bash
cd p2p-sample
mvn package
java -jar target/java-p2p.jar --server.port=18080
```

### 啟動桌面 UI

另開一個 terminal：

```bash
cd ui
mvn javafx:run
```

UI 預設 server IP 是 `127.0.0.1`，會連到：

```text
ws://127.0.0.1:18080/signal
```

若只想開 UI 畫面、不連真實 signaling server，可以使用 mock 模式：

```bash
cd ui
mvn javafx:run -DuseMock=true
```

### 建置 Windows 桌面發行檔

在 repo 根目錄執行：

```powershell
.\scripts\build-win11.ps1 -Version 0.1.0
```

產物會在：

```text
ui\target\DropGoLine-win11-runtime-0.1.0.zip
```

若要建立 Windows installer：

```powershell
.\scripts\build-win11.ps1 -Version 0.1.0 -Installer
```

若要連同測試一起跑：

```powershell
.\scripts\build-win11.ps1 -Version 0.1.0 -RunTests
```

### CLI Chat 測試

Terminal 1 啟動 signaling server：

```bash
cd p2p-sample
mvn package
java -jar target/java-p2p.jar --server.port=18080
```

Terminal 2 建立群組：

```bash
cd p2p-sample
mvn exec:java -Dexec.mainClass=sample.chat.P2pChatCli
```

Terminal 3 加入群組：

```bash
cd p2p-sample
mvn exec:java -Dexec.mainClass=sample.chat.P2pChatCli
```

常用 chat 指令：

```text
hello
/msg <username> <message>
/file <path>
/file <path> <username>
/save <offer-id>
/quit
```

## 專案架構

```text
.
├── README.md
├── scripts/
│   └── build-win11.ps1
├── p2p-sample/
│   ├── pom.xml
│   └── src/main/java/
│       ├── p2p/
│       │   ├── P2pApplication.java
│       │   ├── api/          # 給 UI 與應用層使用的 P2P facade
│       │   ├── signaling/    # Spring WebSocket signaling server
│       │   ├── peer/         # direct file-transfer CLI sample
│       │   ├── session/      # stream/session protocol
│       │   ├── transfer/     # file chunk、checksum、ACK、resume
│       │   ├── ice/          # ICE/STUN/TURN NAT traversal
│       │   └── quic/         # QUIC transport
│       ├── sample/chat/      # CLI group chat sample
│       └── legacy/chat/      # 舊 TCP prototype
└── ui/
    ├── pom.xml
    ├── settings.json
    └── src/main/java/dropgoline/
        ├── App.java
        ├── Launcher.java
        ├── net/             # P2PManager、RealP2PManager、MockP2PManager
        ├── settings/        # AppSettings
        ├── historyservice/  # 傳輸與訊息紀錄
        ├── model/           # UI model
        ├── ui/              # JavaFX stages/cards/helpers
        └── util/            # peer id 工具
```

## 重要設定

- Demo server IP：`12.146.248.110`
- Signaling port：`18080`
- Signaling path：`/signal`
- UI 設定檔：`ui/settings.json` 或 release 執行目錄下的 `settings.json`
- UI 下載目錄：`~/Downloads/DropGoLine`
- 本機測試 server：`127.0.0.1:18080`

## 備註

- signaling server 只負責 peer 註冊、group 管理與 signaling relay；檔案傳輸優先走 peer-to-peer QUIC 連線。
- NAT traversal 使用 ice4j，預設 STUN server 在 `p2p-sample/src/main/resources/application.yml`。
- 若網路環境無法打洞，可能需要額外部署 TURN server 並設定 `TURN_SERVER`、`TURN_USERNAME`、`TURN_PASSWORD`。
