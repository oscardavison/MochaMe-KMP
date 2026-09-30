#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/..." && pwd)"
VERSION="0.1.0"
DIST_DIR="${PROJECT_ROOT}/dist"
SSH_KEY="${HOME}/.ssh/mochame-relay_key.pem"
AZURE_HOST="azureuser@9.160.35.162"
AZURE_DEST="~/mochame/"
ANDROID_PKG="com.mochame.androidapp"

info() { echo -e "\033[1;34m[INFO]\033[0m $*"; }
success() { echo -e "\033[1;32m[SUCCESS]\033[0m $*"; }
warn() { echo -e "\033[1;33m[WARN]\033[0m $*"; }

if [[ ! -d "${DIST_DIR}" ]]; then
    echo -e "\033[1;31m[ERROR]\033[0m Dist directory missing. Run ./package.sh first." >&2
    exit 1
fi

# ------------------------------------------------------------------------------
# 1. Wipe
# ------------------------------------------------------------------------------
info "Removing existing Desktop and CLI packages..."
if dpkg -l | grep -q "^ii  mochame "; then
    sudo apt purge -y mochame
fi
if dpkg -l | grep -q "^ii  mochame-cli "; then
    sudo apt purge -y mochame-cli
fi

info "Removing loose CLI binaries..."
rm -f "${HOME}/.local/bin/mochame-cli"
sudo rm -f "/usr/local/bin/mochame-cli"

info "Wiping local SQLite databases and sync state..."
rm -rf "${HOME}/.local/share/mochame"
rm -rf "${HOME}/.config/mochame"
rm -rf "${HOME}/.cache/mochame"

if command -v adb >/dev/null 2>&1 && adb devices | grep -q -w "device"; then
    info "Device connected. Uninstalling Android app..."
    adb uninstall "${ANDROID_PKG}" || true
fi

# ------------------------------------------------------------------------------
# 2. Installation
# ------------------------------------------------------------------------------
info "Installing JVM Desktop .deb..."
sudo apt install -y "${DIST_DIR}/mochame_${VERSION}_amd64.deb"

info "Installing CLI .deb locally..."
sudo apt install -y "${DIST_DIR}/mochame-cli_${VERSION}_amd64.deb"

if command -v adb >/dev/null 2>&1 && adb devices | grep -q -w "device"; then
    info "Installing fresh APK to Android device..."
    adb install -r "${DIST_DIR}/mochame-v${VERSION}-release.apk"
else
    warn "No Android device detected over ADB. Skipping phone deployment."
fi

# ------------------------------------------------------------------------------
# 3. Azure Relay ClI Tool
# ------------------------------------------------------------------------------
if [[ -f "${SSH_KEY}" ]]; then
    info "Uploading CLI package to Azure Relay..."
    scp -i "${SSH_KEY}" "${DIST_DIR}/mochame-cli_${VERSION}_amd64.deb" "${AZURE_HOST}:${AZURE_DEST}"
else
    warn "SSH key not found at ${SSH_KEY}. Skipping Azure upload."
fi

success "Local re-deployment and database reset complete."