#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# MochaMe Local Debug
# Target Gateway: 10.168.131.237:8080 (Linux VPN)
# ==============================================================================

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_PKG="com.mochame.androidapp"
VPN_GATEWAY="10.168.131.237"
DEBUG_PORT=8080

info()    { echo -e "\033[1;34m[INFO]\033[0m $*"; }
success() { echo -e "\033[1;32m[SUCCESS]\033[0m $*"; }
warn()    { echo -e "\033[1;33m[WARN]\033[0m $*"; }
err()     { echo -e "\033[1;31m[ERROR]\033[0m $*" >&2; }

# ------------------------------------------------------------------------------
# 0. ADB Verification
# ------------------------------------------------------------------------------
if ! command -v adb >/dev/null 2>&1; then
    err "adb command not found in PATH."
    exit 1
fi

info "Checking for connected Android device..."
if ! adb devices | grep -q -w "device"; then
    err "No authorized Android device detected over ADB. Connect phone and enable USB debugging."
    exit 1
fi

# ------------------------------------------------------------------------------
# 1. Locate NetworkConfig & Trap
# ------------------------------------------------------------------------------
NETWORK_CONFIG_FILE="$(find "${PROJECT_ROOT}" -type f -path "*/com/mochame/sync/spi/network/NetworkConfig.kt" | head -n 1)"

if [[ -z "${NETWORK_CONFIG_FILE}" || ! -f "${NETWORK_CONFIG_FILE}" ]]; then
    err "Could not locate com/mochame/sync/spi/network/NetworkConfig.kt"
    exit 1
fi

apply_debug_vpn_config() {
    info "Applying Local VPN Gateway NetworkConfig (${VPN_GATEWAY}:${DEBUG_PORT})..."
    cat << EOF > "${NETWORK_CONFIG_FILE}"
package com.mochame.sync.spi.network

import org.koin.core.annotation.Single

@Single
data class NetworkConfig(
    val host: String = "${VPN_GATEWAY}",
    val port: Int = ${DEBUG_PORT},
    val isSecure: Boolean = false,
    val groupId: String = "frappe"
)
EOF
}

apply_remote_config() {
    info "Restoring Remote Production NetworkConfig (relay.mochame.me:443)..."
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

JVM_PID=""
cleanup() {
    echo ""
    warn "Debug session terminated. Cleaning up processes..."
    if [[ -n "${JVM_PID}" ]] && kill -0 "${JVM_PID}" 2>/dev/null; then
        kill "${JVM_PID}" 2>/dev/null || true
    fi
    apply_remote_config
    success "Cleanup complete."
}

trap cleanup EXIT INT TERM

# ------------------------------------------------------------------------------
# 2. Debug Network Configuration
# ------------------------------------------------------------------------------
apply_debug_vpn_config

# ------------------------------------------------------------------------------
# 3. Build Artifacts for Debug
# ------------------------------------------------------------------------------
info "1/4: Compiling Linux x64 Native CLI (Debug)..."
"${PROJECT_ROOT}/gradlew" :app:entry:linuxCliApp:linkDebugExecutableLinuxX64

CLI_DEBUG_BIN="$(find "${PROJECT_ROOT}/app/entry/linuxCliApp/build/bin/linuxX64/debugExecutable" -name "*.kexe" | head -n 1)"
if [[ -n "${CLI_DEBUG_BIN}" ]]; then
    mkdir -p "${HOME}/.local/bin"
    cp "${CLI_DEBUG_BIN}" "${HOME}/.local/bin/mochame-cli"
    chmod 755 "${HOME}/.local/bin/mochame-cli"
    info "Updated ~/.local/bin/mochame-cli with debug binary."
fi

info "2/4: Compiling Server Distribution..."
"${PROJECT_ROOT}/gradlew" :server:installDist

info "3/4: Compiling Android Debug APK..."
"${PROJECT_ROOT}/gradlew" :app:entry:androidApp:assembleDebug

ANDROID_DEBUG_APK="$(find "${PROJECT_ROOT}/app/entry/androidApp/build/outputs/apk/debug" -name "*debug.apk" | head -n 1)"
if [[ -z "${ANDROID_DEBUG_APK}" || ! -f "${ANDROID_DEBUG_APK}" ]]; then
    err "Android debug APK was not generated."
    exit 1
fi

info "Reinstalling Android app on device..."
adb uninstall "${ANDROID_PKG}" >/dev/null 2>&1 || true
adb install -r "${ANDROID_DEBUG_APK}"

info "Launching Android app..."
adb shell monkey -p "${ANDROID_PKG}" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

# ------------------------------------------------------------------------------
# 4. Launch JVM App (Background) & Attach Logcat (Foreground)
# ------------------------------------------------------------------------------
info "4/4: Launching JVM Desktop App in DEBUG mode..."
DEBUG="true" "${PROJECT_ROOT}/gradlew" :app:entry:jvmApp:run &
JVM_PID=$!

info "JVM app spawned (PID: ${JVM_PID}). Attaching to Android logcat stream..."
info "Press Ctrl+C at any time to exit both and restore production config."
echo "------------------------------------------------------------------------------"

adb logcat -c && adb logcat | grep --line-buffered -E "(\[Android|FATAL EXCEPTION|AndroidRuntime)"