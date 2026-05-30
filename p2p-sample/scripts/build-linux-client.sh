#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_NAME="java-p2p-chat"
BUILD_DIR="$ROOT_DIR/target/client-app"
INPUT_DIR="$BUILD_DIR/input"
OUTPUT_DIR="$ROOT_DIR/target/dist"

cd "$ROOT_DIR"
mvn clean package -DskipTests

rm -rf "$BUILD_DIR" "$OUTPUT_DIR/$APP_NAME"
mkdir -p "$INPUT_DIR" "$OUTPUT_DIR"
if [[ ! -f "$ROOT_DIR/target/java-p2p.jar.original" ]]; then
  echo "thin project jar not found under target" >&2
  exit 1
fi
cp "$ROOT_DIR/target/java-p2p.jar.original" "$INPUT_DIR/java-p2p-client.jar"
mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory="$INPUT_DIR" -Dsilent=true

jpackage \
  --type app-image \
  --name "$APP_NAME" \
  --input "$INPUT_DIR" \
  --main-jar java-p2p-client.jar \
  --main-class p2p.chat.P2pChatCli \
  --dest "$OUTPUT_DIR"

echo "Client executable:"
echo "$OUTPUT_DIR/$APP_NAME/bin/$APP_NAME"
