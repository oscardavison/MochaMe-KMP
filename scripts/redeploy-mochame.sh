#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="0.1.1"
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

purge_if_installed() {
    local pkg="$1"
    if dpkg -s "${pkg}" &>/dev/null; then
        sudo apt purge -y "${pkg}" && info "Found and purged ${pkg}."
    else
        info "${pkg} is not installed; skipping."
    fi
}

# ------------------------------------------------------------------------------
# 1. Uninstall
# ------------------------------------------------------------------------------
info "Checking and removing existing packages..."
purge_if_installed "mochame"
purge_if_installed "mochame-cli"

info "Removing local CLI binaries..."
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
# 3. Azure Relay CLI Tool
# ------------------------------------------------------------------------------

if [[ -f "${SSH_KEY}" ]]; then
    REMOTE_DEB="/tmp/mochame-cli_${VERSION}_amd64.deb"

    info "Uploading CLI package to Azure Relay..."
    scp -i "${SSH_KEY}" "${DIST_DIR}/mochame-cli_${VERSION}_amd64.deb" "${AZURE_HOST}:${REMOTE_DEB}"

    info "Executing remote package lifecycle on Azure..."
    ssh -i "${SSH_KEY}" "${AZURE_HOST}" bash -s -- "${REMOTE_DEB}" << 'EOF'
set -euo pipefail
DEB_PATH="$1"

# 1. Inspect and Purge Existing State
if dpkg -s mochame-cli &>/dev/null; then
    sudo apt purge -y mochame-cli
    echo -e "\033[1;32m[REMOTE]\033[0m Successfully purged previous mochame-cli."
else
    echo -e "\033[1;34m[REMOTE]\033[0m No existing mochame-cli package found. Skipping purge."
fi

# 2. Install New Package
echo -e "\033[1;34m[REMOTE]\033[0m Installing fresh package from ${DEB_PATH}..."
sudo apt install -y "${DEB_PATH}"

# 3. Deterministic Verification
INSTALLED_BIN="$(command -v mochame-cli || true)"
if [[ -n "${INSTALLED_BIN}" ]] && dpkg -s mochame-cli &>/dev/null; then
    echo -e "\033[1;32m[REMOTE]\033[0m Verified: server side mochame-cli is verified at ${INSTALLED_BIN}"
else
    echo -e "\033[1;31m[REMOTE]\033[0m Verification failed: mochame-cli not found in system PATH." >&2
    exit 1
fi

# 4. Cleanup Remote Staging File
rm -f "${DEB_PATH}"
EOF

else
    warn "SSH key not found at ${SSH_KEY}. Skipping Azure CLI deployment."
fi

success "MochaMe re-deployment and database reset complete."