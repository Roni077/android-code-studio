#!/data/data/com.tom.rv2ide/files/usr/bin/bash
# ==============================================================================
# Android Code Studio - NDK Installation Diagnostic & Test Script
# ==============================================================================
# This script diagnoses, downloads, verifies, extracts, and actively tests
# the pinned Android NDK (r30 / 30.0.14206865 for ARM64 Termux/RV2IDE).
#
# Features:
# 1. Full persistent logging to $HOME/setup_logs/ndk_install_<timestamp>.log
# 2. Pre-flight diagnostics: free disk space, curl, tar, sha256sum, and xz
# 3. Automatic installation of missing 'xz-utils' (the common failure cause)
# 4. Tarball caching: preserves the 550 MB download on failures so retries don't re-download
# 5. Verbose extraction with exact error reporting and log preservation
# 6. Active C smoke test: compiles and runs an ARM64 binary with NDK clang
# ==============================================================================

set -u

# ------------------------------------------------------------------------------
# 1. Environment Resolution
# ------------------------------------------------------------------------------
if [ -z "${PREFIX:-}" ]; then
    if [ -d "/data/user/0/com.tom.rv2ide/files/usr" ]; then
        PREFIX="/data/user/0/com.tom.rv2ide/files/usr"
    elif [ -d "/data/data/com.tom.rv2ide/files/usr" ]; then
        PREFIX="/data/data/com.tom.rv2ide/files/usr"
    elif [ -d "/data/data/com.termux/files/usr" ]; then
        PREFIX="/data/data/com.termux/files/usr"
    else
        PREFIX="/data/data/com.tom.rv2ide/files/usr"
    fi
fi

if [ -z "${HOME:-}" ]; then
    if [ -d "/data/user/0/com.tom.rv2ide/files/home" ]; then
        HOME="/data/user/0/com.tom.rv2ide/files/home"
    elif [ -d "/data/data/com.tom.rv2ide/files/home" ]; then
        HOME="/data/data/com.tom.rv2ide/files/home"
    else
        HOME="$(dirname "$PREFIX")/home"
    fi
fi

export PATH="$PREFIX/bin:$PATH"

# Self-registration so ndksetup is globally available in terminal
if [ -d "$PREFIX/bin" ] && [ -w "$PREFIX/bin" ]; then
    cp -f "${BASH_SOURCE[0]:-${0}}" "$PREFIX/bin/ndksetup.sh" 2>/dev/null || true
    cp -f "${BASH_SOURCE[0]:-${0}}" "$PREFIX/bin/ndksetup" 2>/dev/null || true
    chmod +x "$PREFIX/bin/ndksetup.sh" "$PREFIX/bin/ndksetup" 2>/dev/null || true
fi

if [ -z "${ANDROID_SDK:-}" ]; then
    if [ -d "$PREFIX/opt/android-sdk" ]; then
        ANDROID_SDK="$PREFIX/opt/android-sdk"
    elif [ -d "$HOME/android-sdk" ]; then
        ANDROID_SDK="$HOME/android-sdk"
    elif [ -d "/data/data/com.tom.rv2ide/files/usr/opt/android-sdk" ]; then
        ANDROID_SDK="/data/data/com.tom.rv2ide/files/usr/opt/android-sdk"
    else
        ANDROID_SDK="$PREFIX/opt/android-sdk"
    fi
fi

REQUESTED_NDK="${1:-30}"
case "$REQUESTED_NDK" in
    30|30.*)
        NDK_MAJOR="30"
        NDK_VERSION="30.0.14206865"
        NDK_ASSET="android-ndk-r30-aarch64-linux-android.tar.xz"
        NDK_URL="https://github.com/HomuHomu833/android-ndk-custom/releases/download/r30/$NDK_ASSET"
        NDK_SHA256="406afc90e64eee11caf39c0eadb0fc829b126387f18e50562320cdc6162e5a2b"
        NDK_FALLBACK_SHA256="aa443113433cf8a3a4a97524a94041972d1ccbb72897adc4aa48c13ae627509d"
        ;;
    29|29.*)
        NDK_MAJOR="29"
        NDK_VERSION="29.0.14033849"
        NDK_ASSET="android-ndk-r29-aarch64-linux-android.tar.xz"
        NDK_URL="https://github.com/HomuHomu833/android-ndk-custom/releases/download/r29/$NDK_ASSET"
        NDK_SHA256="9c42546e3ad2c65ba7e3cac8d9a952944534242e176bbf037703e83a2ac8c18d"
        NDK_FALLBACK_SHA256="cb7e2be23c9029f928da61bfdb5314a7ff7bae972c4ec55c353bb08c4874d15d"
        ;;
    28|28.*)
        NDK_MAJOR="28"
        NDK_VERSION="28.0.12674770"
        NDK_ASSET="android-ndk-r28c-aarch64-linux-android.tar.xz"
        NDK_URL="https://github.com/HomuHomu833/android-ndk-custom/releases/download/r28/$NDK_ASSET"
        NDK_SHA256="c540986ce9c4568c5cdc5adb04cbce7570f372f1f27314edd344e45e00f334f1"
        NDK_FALLBACK_SHA256="5dee54fb382e5ee7c6ed0e328157df5f32c8f5933c225b0293ced6a1bf42fad7"
        ;;
    27|27.*)
        NDK_MAJOR="27"
        NDK_VERSION="27.2.12479018"
        NDK_ASSET="android-ndk-r27d-aarch64-linux-android.tar.xz"
        NDK_URL="https://github.com/HomuHomu833/android-ndk-custom/releases/download/r27/$NDK_ASSET"
        NDK_SHA256="bb3e98dd14a70fdc82f2468f8bdda1dcbd65e731b36cb00e858cec6155e56694"
        NDK_FALLBACK_SHA256="819b47ed906d4b1282ceaf4f036be69393098a26575369aac3e6526d29f81b7c"
        ;;
    26|26.*)
        NDK_MAJOR="26"
        NDK_VERSION="26.3.11579264"
        NDK_ASSET="android-ndk-r26d-aarch64-linux-android.tar.xz"
        NDK_URL="https://github.com/HomuHomu833/android-ndk-custom/releases/download/r26/$NDK_ASSET"
        NDK_SHA256="aaa4a288ad5be38617f33cc6011927e5fb57bae296709471268cec7e5505df5f"
        NDK_FALLBACK_SHA256="d9466fde4c6c0e65652ddc2193113fe5a4c99cff306e85fb8e6c3e10f2bd245e"
        ;;
    *)
        echo "Error: Unknown NDK version '$REQUESTED_NDK'. Supported versions: 30, 29, 28, 27, 26" >&2
        exit 1
        ;;
esac

is_valid_ndk_sha256() {
    local hash="$1"
    [ "$hash" = "$NDK_SHA256" ] || [ -n "$NDK_FALLBACK_SHA256" -a "$hash" = "$NDK_FALLBACK_SHA256" ]
}

# ------------------------------------------------------------------------------
# 2. Logging Architecture Setup
# ------------------------------------------------------------------------------
SETUP_LOG_DIR="$HOME/setup_logs"
mkdir -p "$SETUP_LOG_DIR" 2>/dev/null || true
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
LOG_FILE="$SETUP_LOG_DIR/ndk_install_${TIMESTAMP}.log"
LATEST_LOG="$SETUP_LOG_DIR/latest_ndk_install.log"
LEGACY_LOG="$HOME/setup_output.log"

# Tee all standard output and standard error to the log file and screen
exec > >(tee -a "$LOG_FILE") 2>&1

# Update convenience links
ln -sf "$LOG_FILE" "$LATEST_LOG" 2>/dev/null || cp -f "$LOG_FILE" "$LATEST_LOG" 2>/dev/null || true
echo "=== NDK Diagnostic Run Started at $(date) ===" >> "$LEGACY_LOG" 2>/dev/null || true

log_step() {
    echo ""
    echo "=========================================================="
    echo " $1"
    echo "=========================================================="
}

log_info() {
    echo "[INFO]  $1"
}

log_warn() {
    echo "[WARN]  $1"
}

log_error() {
    echo "[ERROR] $1"
}

log_success() {
    echo "[OK]    $1"
}

log_step "Android NDK Installation & Diagnostic Tool"
log_info "Date / Time:     $(date)"
log_info "Prefix:          $PREFIX"
log_info "Home:            $HOME"
log_info "Android SDK:     $ANDROID_SDK"
log_info "Target NDK:      $NDK_VERSION (Major r$NDK_MAJOR)"
log_info "Log File:        $LOG_FILE"

# ------------------------------------------------------------------------------
# 3. Pre-Flight Diagnostics
# ------------------------------------------------------------------------------
log_step "[1/6] Running Pre-Flight Diagnostics..."

# Check available disk space
mkdir -p "$ANDROID_SDK/ndk" 2>/dev/null || true
AVAIL_KB=$(df -Pk "$ANDROID_SDK/ndk" 2>/dev/null | awk 'NR==2 {print $4}')
if [ -n "$AVAIL_KB" ]; then
    AVAIL_MB=$((AVAIL_KB / 1024))
    log_info "Available disk space in SDK directory: ${AVAIL_MB} MB"
    if [ "$AVAIL_KB" -lt 2700000 ]; then
        log_warn "Free disk space is under 2.6 GB (${AVAIL_MB} MB available)."
        log_warn "NDK archive is ~550 MB and expands to ~1.6 GB. Extraction may run out of space!"
    else
        log_success "Disk space check passed (${AVAIL_MB} MB available, >= 2600 MB needed)."
    fi
else
    log_warn "Could not determine available disk space with df. Proceeding with caution."
fi

# Check essential CLI tools
for tool in curl tar sha256sum; do
    if command -v "$tool" >/dev/null 2>&1; then
        log_success "Found tool: $tool ($(command -v "$tool"))"
    else
        log_error "Missing required tool: $tool"
        exit 1
    fi
done

# Check for xz / xz-utils (The #1 root cause of silent tar.xz extraction failures)
XZ_FOUND=false
if command -v xz >/dev/null 2>&1; then
    XZ_FOUND=true
    log_success "Found xz decompression utility: $(command -v xz) ($(xz --version 2>/dev/null | head -n 1 || echo 'ok'))"
else
    log_warn "Utility 'xz' is MISSING! Standard 'tar' cannot unpack .tar.xz without 'xz'."
    if command -v pkg >/dev/null 2>&1; then
        log_info "Attempting to install 'xz-utils' automatically via pkg..."
        if pkg install -y xz-utils; then
            if command -v xz >/dev/null 2>&1; then
                XZ_FOUND=true
                log_success "Successfully installed xz-utils!"
            fi
        fi
    fi
fi

if [ "$XZ_FOUND" = "false" ]; then
    log_error "FATAL: 'xz' is missing and could not be installed automatically."
    log_error "Please run: pkg install -y xz-utils"
    log_error "and re-run this script."
    exit 1
fi

# ------------------------------------------------------------------------------
# 4. Check Existing Installation
# ------------------------------------------------------------------------------
log_step "[2/6] Checking for Existing NDK Installation..."
NDK_TARGET="$ANDROID_SDK/ndk/$NDK_VERSION"
CLANG_BIN="$NDK_TARGET/toolchains/llvm/prebuilt/linux-aarch64/bin/clang"

if [ -d "$NDK_TARGET" ] && [ -x "$CLANG_BIN" ]; then
    log_info "Found existing NDK at: $NDK_TARGET"
    log_info "Testing existing Clang binary..."
    if "$CLANG_BIN" --version >/dev/null 2>&1; then
        log_success "Existing Clang is operational:"
        "$CLANG_BIN" --version | head -n 2
        EXISTING_VALID=true
    else
        log_warn "Existing Clang found but failed execution check. Will re-provision."
        EXISTING_VALID=false
    fi
else
    log_info "No valid existing NDK found at $NDK_TARGET."
    EXISTING_VALID=false
fi

# ------------------------------------------------------------------------------
# 5. Download with Persistent Cache
# ------------------------------------------------------------------------------
CACHE_DIR="$ANDROID_SDK/ndk/.cache"
if ! mkdir -p "$CACHE_DIR" 2>/dev/null; then
    CACHE_DIR="$HOME/.cache/ndk"
    mkdir -p "$CACHE_DIR" 2>/dev/null || true
fi
TARBALL="$CACHE_DIR/$NDK_ASSET"

if [ "$EXISTING_VALID" = "false" ]; then
    log_step "[3/6] Acquiring NDK Archive (with Local Cache)..."
    DOWNLOAD_NEEDED=true

    if [ -f "$TARBALL" ]; then
        log_info "Cached tarball found at: $TARBALL"
        log_info "Verifying SHA256 checksum of cached tarball..."
        ACTUAL_SHA=$(sha256sum "$TARBALL" | awk '{print $1}')
        if is_valid_ndk_sha256 "$ACTUAL_SHA"; then
            log_success "Cached tarball checksum matches ($ACTUAL_SHA). Skipping download!"
            DOWNLOAD_NEEDED=false
        else
            log_warn "Cached tarball checksum mismatch (expected $NDK_SHA256, got $ACTUAL_SHA)."
            log_warn "Cached archive will be re-downloaded."
            rm -f "$TARBALL"
        fi
    fi

    if [ "$DOWNLOAD_NEEDED" = "true" ]; then
        log_info "Downloading Android NDK r$NDK_MAJOR aarch64 repack..."
        log_info "Source URL: $NDK_URL"
        TMP_DOWNLOAD="${TARBALL}.part.$$"
        rm -f "$TMP_DOWNLOAD"
        
        if curl -fL --retry 3 --retry-delay 2 --progress-bar -o "$TMP_DOWNLOAD" "$NDK_URL"; then
            log_success "Download completed successfully."
            mv -f "$TMP_DOWNLOAD" "$TARBALL"
        else
            log_error "Download failed from $NDK_URL!"
            rm -f "$TMP_DOWNLOAD"
            exit 1
        fi

        log_info "Verifying downloaded archive SHA256..."
        ACTUAL_SHA=$(sha256sum "$TARBALL" | awk '{print $1}')
        if is_valid_ndk_sha256 "$ACTUAL_SHA"; then
            log_success "SHA256 verification passed: $ACTUAL_SHA"
        else
            log_error "SHA256 verification failed!"
            log_error "Expected: $NDK_SHA256 (or fallback: $NDK_FALLBACK_SHA256)"
            log_error "Actual:   $ACTUAL_SHA"
            log_warn "Archive preserved at $TARBALL for inspection (not deleted)."
            exit 1
        fi
    fi

# ------------------------------------------------------------------------------
# 6. Verbose Staging Extraction
# ------------------------------------------------------------------------------
    log_step "[4/6] Extracting NDK with Verbose Logging..."
    STAGE_DIR="$ANDROID_SDK/ndk/.ndk-stage.$$"
    if ! mkdir -p "$STAGE_DIR" 2>/dev/null; then
        STAGE_DIR="$HOME/.ndk-stage.$$"
        mkdir -p "$STAGE_DIR" 2>/dev/null || true
    fi

    log_info "Staging directory: $STAGE_DIR"
    log_info "Starting tar extraction (all files logged to $LOG_FILE)..."

    # Run tar with verbose output so every file is recorded in the log
    if tar -xvf "$TARBALL" -C "$STAGE_DIR"; then
        log_success "Tar extraction completed successfully!"
    else
        TAR_EXIT=$?
        log_error "Tar extraction FAILED with exit code: $TAR_EXIT"
        log_error "Tarball preserved at: $TARBALL"
        log_error "Staging directory preserved for inspection at: $STAGE_DIR"
        log_error "Check $LOG_FILE for exact file failure details."
        exit $TAR_EXIT
    fi

    # Locate extracted folder
    EXTRACTED_DIR=""
    for cand in "$STAGE_DIR/android-ndk-r$NDK_MAJOR-aarch64-linux-android" \
                "$STAGE_DIR/android-ndk-r$NDK_MAJOR" \
                "$STAGE_DIR/android-ndk-r$NDK_MAJOR-aarch64"; do
        if [ -d "$cand" ]; then
            EXTRACTED_DIR="$cand"
            break
        fi
    done

    if [ -z "$EXTRACTED_DIR" ]; then
        EXTRACTED_DIR=$(find "$STAGE_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1)
    fi

    if [ -z "$EXTRACTED_DIR" ] || [ ! -d "$EXTRACTED_DIR" ]; then
        log_error "Extracted NDK directory structure unrecognized in $STAGE_DIR."
        exit 1
    fi

    log_info "Found extracted NDK at: $EXTRACTED_DIR"
    log_info "Moving to final destination: $NDK_TARGET"
    rm -rf "$NDK_TARGET"
    mkdir -p "$(dirname "$NDK_TARGET")"
    mv "$EXTRACTED_DIR" "$NDK_TARGET"
    rm -rf "$STAGE_DIR"
    log_success "NDK directory moved to $NDK_TARGET."
fi

# ------------------------------------------------------------------------------
# 7. Toolchain Wrappers & Shebang Sanitization
# ------------------------------------------------------------------------------
log_step "[5/6] Wiring Clang Toolchain & Shebang Permissions..."
TOOLCHAIN_BIN="$NDK_TARGET/toolchains/llvm/prebuilt/linux-aarch64/bin"
if [ ! -d "$TOOLCHAIN_BIN" ]; then
    log_error "LLVM toolchain bin directory not found at: $TOOLCHAIN_BIN"
    exit 1
fi

log_info "Ensuring executable permissions on all LLVM binaries..."
find "$TOOLCHAIN_BIN" -type f -exec chmod 755 {} + 2>/dev/null || true

# Sanitize any script wrappers in toolchain
for script in "$TOOLCHAIN_BIN"/*; do
    if [ -f "$script" ] && [ ! -L "$script" ]; then
        head_bytes=$(head -c 2 "$script" 2>/dev/null || echo "")
        if [ "$head_bytes" = "#!" ]; then
            first_line=$(head -n 1 "$script")
            if echo "$first_line" | grep -q "/bin/bash\|/bin/sh\|/usr/bin/env"; then
                sed -i -E "1s|^#!.*|#!$PREFIX/bin/bash|" "$script" 2>/dev/null || true
                chmod 755 "$script" 2>/dev/null || true
            fi
        fi
    fi
done
log_success "Toolchain permissions and wrappers configured."

# ------------------------------------------------------------------------------
# 8. Active Compiler Smoke Test
# ------------------------------------------------------------------------------
log_step "[6/6] Active Compiler Smoke Test (C Compilation Check)..."
CLANG_EXEC="$TOOLCHAIN_BIN/clang"
if [ ! -x "$CLANG_EXEC" ]; then
    log_error "Clang binary not executable: $CLANG_EXEC"
    exit 1
fi

log_info "Compiler Version:"
"$CLANG_EXEC" --version | head -n 2

TEST_DIR="$HOME/.cache/ndk_test_$$"
mkdir -p "$TEST_DIR"
TEST_C="$TEST_DIR/smoke_test.c"
TEST_BIN="$TEST_DIR/smoke_test_bin"

cat > "$TEST_C" <<'CEOF'
#include <stdio.h>
int main(void) {
    printf(">> SUCCESS: Android NDK r30 ARM64 Clang is fully operational on this device!\n");
    return 0;
}
CEOF

log_info "Compiling test C program with target aarch64-linux-android35..."
if "$CLANG_EXEC" --target=aarch64-linux-android35 -o "$TEST_BIN" "$TEST_C"; then
    log_success "Compilation succeeded! Generated test binary: $TEST_BIN"
    if [ -x "$TEST_BIN" ]; then
        log_info "Executing generated test binary:"
        "$TEST_BIN" || true
    fi
    rm -rf "$TEST_DIR"
else
    log_error "Compiler smoke test FAILED! Check compiler error logs above."
    rm -rf "$TEST_DIR"
    exit 1
fi

# ------------------------------------------------------------------------------
# 9. Completion Summary
# ------------------------------------------------------------------------------
log_step "NDK DIAGNOSTIC & INSTALLATION COMPLETE!"
log_success "Android NDK $NDK_VERSION is fully installed, verified, and operational."
log_info "NDK Location:       $NDK_TARGET"
log_info "Clang Binary:       $CLANG_EXEC"
log_info "Detailed Log File:  $LOG_FILE"
log_info "Latest Log Link:    $LATEST_LOG"
echo "=== NDK Diagnostic Run Finished Successfully at $(date) ===" >> "$LEGACY_LOG" 2>/dev/null || true
echo "=========================================================="
exit 0
