#!/usr/bin/env bash

set -Eeuo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

if ! command -v node >/dev/null 2>&1; then
  echo "Error: Node.js is not installed."
  exit 1
fi

MAVEN_VERSION="3.9.11"
LOCAL_MAVEN_DIR="$PROJECT_DIR/.tools/apache-maven-$MAVEN_VERSION"

if command -v mvn >/dev/null 2>&1; then
  MAVEN_CMD="$(command -v mvn)"
elif [[ -x "$LOCAL_MAVEN_DIR/bin/mvn" ]]; then
  MAVEN_CMD="$LOCAL_MAVEN_DIR/bin/mvn"
else
  if ! command -v curl >/dev/null 2>&1 || ! command -v tar >/dev/null 2>&1 || ! command -v sha512sum >/dev/null 2>&1; then
    echo "Error: Maven is missing and curl, tar, and sha512sum are required for automatic setup."
    exit 1
  fi
  mkdir -p "$PROJECT_DIR/.tools"
  archive="$PROJECT_DIR/.tools/apache-maven-$MAVEN_VERSION-bin.tar.gz"
  download_url="https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz"
  echo "Maven was not found. Downloading Maven $MAVEN_VERSION..."
  curl -fsSL "$download_url" -o "$archive"
  expected_hash="$(curl -fsSL "$download_url.sha512" | awk '{print $1}')"
  actual_hash="$(sha512sum "$archive" | cut -d' ' -f1)"
  if [[ -z "$expected_hash" || "$expected_hash" != "$actual_hash" ]]; then
    echo "Error: Maven archive checksum verification failed."
    exit 1
  fi
  tar -xzf "$archive" -C "$PROJECT_DIR/.tools"
  MAVEN_CMD="$LOCAL_MAVEN_DIR/bin/mvn"
fi

if [[ ! -f credentials.json && -z "${GOOGLE_CREDENTIALS_JSON:-}" ]]; then
  echo "Error: credentials.json was not found in $PROJECT_DIR."
  exit 1
fi

if [[ ! -d node_modules ]]; then
  echo "Installing frontend dependencies..."
  npm install
fi

export APP_ACCESS_TOKEN="${APP_ACCESS_TOKEN:-dev-local-key}"
export VITE_LOCAL_ACCESS_TOKEN="$APP_ACCESS_TOKEN"

backend_pid=""
frontend_pid=""

cleanup() {
  trap - INT TERM EXIT
  echo
  echo "Stopping frontend and backend..."
  [[ -n "$frontend_pid" ]] && kill "$frontend_pid" 2>/dev/null || true
  [[ -n "$backend_pid" ]] && kill "$backend_pid" 2>/dev/null || true
  wait "$frontend_pid" "$backend_pid" 2>/dev/null || true
}

trap cleanup INT TERM EXIT

echo "Starting Java backend at http://localhost:8080"
"$MAVEN_CMD" -q -DskipTests spring-boot:run &
backend_pid=$!

echo "Starting Vite frontend at http://localhost:5173"
npm run dev -- --host 0.0.0.0 &
frontend_pid=$!

echo
echo "Open: http://localhost:5173 (local access is automatic)"
echo "Press Ctrl+C to stop both services."
echo

wait -n "$backend_pid" "$frontend_pid"
