#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSH_KEY="${HOME}/.ssh/mochame-relay_key.pem"
AZURE_HOST="azureuser@9.160.35.162"

info()    { echo -e "\033[1;34m[INFO]\033[0m $*"; }
success() { echo -e "\033[1;32m[SUCCESS]\033[0m $*"; }
err()     { echo -e "\033[1;31m[ERROR]\033[0m $*" >&2; }

if [[ ! -f "${SSH_KEY}" ]]; then
    err "SSH key not found at: ${SSH_KEY}"
    exit 1
fi

# ------------------------------------------------------------------------------
# 1. Local Build & Packaging
# ------------------------------------------------------------------------------
info "Compiling locally..."
"${PROJECT_ROOT}/gradlew" :server:installDist

SERVER_INSTALL_DIR="${PROJECT_ROOT}/server/build/install/server"

if [[ ! -d "${SERVER_INSTALL_DIR}" || ! -f "${SERVER_INSTALL_DIR}/bin/server" ]]; then
    err "Server install directory or bin/server missing at: ${SERVER_INSTALL_DIR}"
    exit 1
fi

TEMP_ARCHIVE="/tmp/server-dist.tar.gz"

info "Compressing server binaries into tarball..."
tar -czf "${TEMP_ARCHIVE}" -C "${SERVER_INSTALL_DIR}" .

# ------------------------------------------------------------------------------
# 2. Transfer Payload & Dockerfile to Azure
# ------------------------------------------------------------------------------
info "Uploading server archive and Dockerfile..."
scp -i "${SSH_KEY}" "${TEMP_ARCHIVE}" "${AZURE_HOST}:/tmp/server-dist.tar.gz"
rm -f "${TEMP_ARCHIVE}"

if [[ -f "${PROJECT_ROOT}/server/Dockerfile" ]]; then
    scp -i "${SSH_KEY}" "${PROJECT_ROOT}/server/Dockerfile" "${AZURE_HOST}:~/mochame/server/Dockerfile"
fi

# ------------------------------------------------------------------------------
# 3. Remote Wipe, Extract, Rebuild & Snapshot Logs
# ------------------------------------------------------------------------------
info "Executing remote redeployment on Azure..."
ssh -i "${SSH_KEY}" "${AZURE_HOST}" bash << 'EOF'
set -euo pipefail

cd ~/mochame

echo "==> Stopping running container..."
docker compose down --remove-orphans

echo "==> Deleting server-side SQLite state and old binaries..."
rm -rf server-data
rm -rf ~/.local/share/mochame
rm -rf server/build/install/server

echo "==> Pre-creating server-data as azureuser (UID 1000)..."
mkdir -p server-data

echo "==> Unpacking new server distribution..."
mkdir -p server/build/install/server
tar -xzf /tmp/server-dist.tar.gz -C server/build/install/server
rm -f /tmp/server-dist.tar.gz

echo "==> Starting container..."
docker compose up -d --build --force-recreate

echo "==> Waiting for Ktor initialization..."
sleep 2

echo "==> Relay status and recent logs:"
docker compose ps
docker compose logs --tail=20
EOF

success "Server deployed."