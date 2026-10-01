#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="0.1.0"
DIST_DIR="${PROJECT_ROOT}/dist"
SERVER_BUILD_DIST="${PROJECT_ROOT}/server/build/distributions"
CLI_STAGE="${PROJECT_ROOT}/build/staging/mochame-cli-pkg"

info() { echo -e "\033[1;34m[INFO]\033[0m $*"; }
success() { echo -e "\033[1;32m[SUCCESS]\033[0m $*"; }

# ------------------------------------------------------------------------------
# 0. NetworkConfig & Trap
# ------------------------------------------------------------------------------
NETWORK_CONFIG_FILE="$(find "${PROJECT_ROOT}" -type f -path "*/com/mochame/sync/spi/network/NetworkConfig.kt" | head -n 1)"

if [[ -z "${NETWORK_CONFIG_FILE}" || ! -f "${NETWORK_CONFIG_FILE}" ]]; then
    echo -e "\033[1;31m[ERROR]\033[0m NetworkConfig.kt not found." >&2
    exit 1
fi

apply_cli_config() {
    info "Applying Local Loopback NetworkConfig (127.0.0.1:8080)..."
    cat << 'EOF' > "${NETWORK_CONFIG_FILE}"
package com.mochame.sync.spi.network

import org.koin.core.annotation.Single

@Single
data class NetworkConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8080,
    val isSecure: Boolean = false,
    val groupId: String = "frappe"
)
EOF
}

apply_remote_config() {
    info "Applying Remote Production NetworkConfig (relay.mochame.me:443)..."
    cat << 'EOF' > "${NETWORK_CONFIG_FILE}"
package com.mochame.sync.spi.network

import org.koin.core.annotation.Single

@Single
data class NetworkConfig(
    val host: String = "relay.mochame.me",
    val port: Int = 443,
    val isSecure: Boolean = true,
    val groupId: String = "frappe"
)
EOF
}

trap apply_remote_config EXIT

# ------------------------------------------------------------------------------
# 1. Clean and Setup Staging
# ------------------------------------------------------------------------------
info "Cleaning previous build staging and dist directories..."
rm -rf "${DIST_DIR}" "${CLI_STAGE}" /tmp/deb-patch-*
rm -rf "${SERVER_BUILD_DIST}"

mkdir -p "${DIST_DIR}" "${CLI_STAGE}/usr/bin" "${CLI_STAGE}/DEBIAN"

cat << EOF > "${CLI_STAGE}/DEBIAN/control"
Package: mochame-cli
Version: ${VERSION}
Section: utils
Priority: optional
Architecture: amd64
Maintainer: Oscar <omdavison@proton.me>
Description: MochaMe local-first sync terminal utility
 For reading/writing data within a local-first distributed system.
EOF

# ------------------------------------------------------------------------------
# 2. Package Native CLI (on Loopback)
# ------------------------------------------------------------------------------
apply_cli_config

info "Compiling Linux x64 Native CLI executable..."
"${PROJECT_ROOT}/gradlew" :app:entry:linuxCliApp:linkReleaseExecutableLinuxX64

CLI_BIN_SRC="$(find "${PROJECT_ROOT}/app/entry/linuxCliApp/build/bin/linuxX64/releaseExecutable" -name "*.kexe" | head -n 1)"

# Copy the compiled binary into the .deb staging path
cp "${CLI_BIN_SRC}" "${CLI_STAGE}/usr/bin/mochame-cli"
chmod 755 "${CLI_STAGE}/usr/bin/mochame-cli"

# Debian package - installs into /usr/bin/mochame-cli via apt/dpkg
info "Building mochame-cli_${VERSION}_amd64.deb..."
dpkg-deb --build "${CLI_STAGE}" "${DIST_DIR}/mochame-cli_${VERSION}_amd64.deb"

# Tarball - packs the binary directly from usr/bin
info "Building mochame-cli-${VERSION}-linux-x64.tar.gz..."
tar -czvf "${DIST_DIR}/mochame-cli-${VERSION}-linux-x64.tar.gz" -C "${CLI_STAGE}/usr/bin" mochame-cli

success "CLI packaging complete. Artifacts written to ${DIST_DIR}"

# ------------------------------------------------------------------------------
# 3. Package JVM Desktop App (Remote Config)
# ------------------------------------------------------------------------------
apply_remote_config

info "Building JVM Desktop package..."
"${PROJECT_ROOT}/gradlew" :app:entry:jvmApp:packageDeb

JVM_DEB_SRC="$(find "${PROJECT_ROOT}/app/entry/jvmApp/build/compose/binaries" -name "*.deb" | head -n 1)"
PATCH_DIR="/tmp/deb-patch-$$"

info "Patching JVM Desktop with StartupWMClass..."
rm -rf "${PATCH_DIR}"
dpkg-deb -R "${JVM_DEB_SRC}" "${PATCH_DIR}"

DESKTOP_ENTRY="${PATCH_DIR}/opt/mochame/lib/mochame-MochaMe.desktop"
if [[ -f "${DESKTOP_ENTRY}" ]]; then
    echo "StartupWMClass=com-mochame-app-entry-jvm-MainKt" >> "${DESKTOP_ENTRY}"
fi

dpkg-deb -b "${PATCH_DIR}" "${DIST_DIR}/mochame_${VERSION}_amd64.deb"
rm -rf "${PATCH_DIR}"

# ------------------------------------------------------------------------------
# 4. Package Android APK
# ------------------------------------------------------------------------------
info "Compiling Android release APK..."
"${PROJECT_ROOT}/gradlew" :app:entry:androidApp:assembleRelease

APK_SRC="$(find "${PROJECT_ROOT}/app/entry/androidApp/build/outputs/apk/release" -name "*.apk" | head -n 1)"
cp "${APK_SRC}" "${DIST_DIR}/mochame-v${VERSION}-release.apk"

# ------------------------------------------------------------------------------
# 5. Package Server Archive
# ------------------------------------------------------------------------------
info "Packaging Server distribution archives..."

"${PROJECT_ROOT}/gradlew" :server:installDist :server:distZip

SERVER_INSTALL_DIR="${PROJECT_ROOT}/server/build/install/server"
if [[ ! -d "${SERVER_INSTALL_DIR}" || ! -f "${SERVER_INSTALL_DIR}/bin/server" ]]; then
    echo -e "\033[1;31m[ERROR]\033[0m Server install directory or bin/server missing at: ${SERVER_INSTALL_DIR}" >&2
    exit 1
fi

info "Compressing flat server archive into ${DIST_DIR}/server-${VERSION}.tar.gz..."
tar -czf "${DIST_DIR}/server-${VERSION}.tar.gz" -C "${SERVER_INSTALL_DIR}" .

zip_src="$(find "${SERVER_BUILD_DIST}" -maxdepth 1 -name "*.zip" -print -quit)"
if [[ -z "${zip_src}" || ! -f "${zip_src}" ]]; then
    echo -e "\033[1;31m[ERROR]\033[0m Server .zip not found in ${SERVER_BUILD_DIST}" >&2
    exit 1
fi
cp "${zip_src}" "${DIST_DIR}/server-${VERSION}.zip"

success "Package pipeline completed. All distribution artifacts staged:"
ls -lh "${DIST_DIR}"