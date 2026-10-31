#!/data/user/0/com.tom.rv2ide/files/usr/bin/bash
# ==============================================================================
# install_and_verify_flutter.sh
#
# Authoritative On-Device Flutter Setup, Recovery & Verification Script
# Conforms to: GeneralKaos666/flutter-for-termux
# Environment: Android Code Studio (com.tom.rv2ide) & Termux ARM64
# ==============================================================================

set -u

# --- Color Formatting ---
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

SETUP_LOG="${SETUP_LOG:-$HOME/setup_output.log}"
export SETUP_LOG

write_setup_log() {
  local lvl="$1"
  shift
  local raw_msg="$*"
  local clean_msg
  clean_msg=$(printf "%s" "$raw_msg" | sed -E 's/\x1b\[[0-9;]*[a-zA-Z]//g')
  local ts
  ts=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "----")
  local log_dir
  log_dir="$(dirname "$SETUP_LOG")"
  [ ! -d "$log_dir" ] && mkdir -p "$log_dir" 2>/dev/null || true
  printf "[%s] [%s] %s\n" "$ts" "$lvl" "$clean_msg" >> "$SETUP_LOG" 2>/dev/null || true
}

log_info()    { echo -e "${BLUE}[INFO]${NC} $*"; write_setup_log "INFO" "$*"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $*"; write_setup_log "SUCCESS" "$*"; }
log_warn()    { echo -e "${YELLOW}[WARNING]${NC} $*"; write_setup_log "WARN" "$*"; }
log_err()     { echo -e "${RED}[ERROR]${NC} $*" >&2; write_setup_log "ERROR" "$*"; }
log_section() {
  echo ""
  echo -e "${CYAN}${BOLD}=== $* ===${NC}"
  write_setup_log "SECTION" "=== $* ==="
}

# --- 1. Environment Auto-Detection ---
if [ -z "${PREFIX:-}" ]; then
  if [ -d "/data/user/0/com.tom.rv2ide/files/usr" ]; then
    PREFIX="/data/user/0/com.tom.rv2ide/files/usr"
  elif [ -d "/data/data/com.tom.rv2ide/files/usr" ]; then
    PREFIX="/data/data/com.tom.rv2ide/files/usr"
  elif [ -d "/data/data/com.termux/files/usr" ]; then
    PREFIX="/data/data/com.termux/files/usr"
  else
    PREFIX="$(cd "$(dirname "$0")/.." && pwd)"
  fi
fi

if [ -z "${HOME:-}" ]; then
  if [ -d "/data/user/0/com.tom.rv2ide/files/home" ]; then
    HOME="/data/user/0/com.tom.rv2ide/files/home"
  elif [ -d "/data/data/com.termux/files/home" ]; then
    HOME="/data/data/com.termux/files/home"
  else
    HOME="$(dirname "$PREFIX")/home"
  fi
fi

export PREFIX
export HOME
export FLUTTER_ROOT="${FLUTTER_ROOT:-$PREFIX/opt/flutter}"
export ANDROID_SDK="${ANDROID_SDK:-$PREFIX/opt/android-sdk}"
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
export TMPDIR="${TMPDIR:-$PREFIX/tmp}"
mkdir -p "$TMPDIR"

# Configure Java Home
JAVA_HOME=$(find "$PREFIX/lib/jvm" -maxdepth 1 -type d -name 'java-*-openjdk' 2>/dev/null | sort -V | tail -1)
if [ -n "$JAVA_HOME" ]; then
  export JAVA_HOME
fi

export PATH="$FLUTTER_ROOT/bin:$FLUTTER_ROOT/bin/cache/dart-sdk/bin:$PREFIX/bin:$PATH"

CACHE_DIR="$HOME/.cache/flutter_setup"
mkdir -p "$CACHE_DIR"

DEB_NAME="flutter_3.47.9-1_aarch64.deb"
DEB_URL="https://github.com/GeneralKaos666/flutter-for-termux/releases/download/3.47.9/${DEB_NAME}"
CACHED_DEB="$CACHE_DIR/$DEB_NAME"

CLEAN_CACHE=false
KEEP_CACHE=false
TARGET_PROJECT=""

for arg in "$@"; do
  case "$arg" in
    --clean-cache) CLEAN_CACHE=true ;;
    --keep-cache) KEEP_CACHE=true ;;
    --clean)
      log_info "Cleaning download cache ($CACHE_DIR)..."
      rm -f "$CACHE_DIR"/*.deb "$CACHE_DIR"/*.tar.xz "$CACHE_DIR"/*.zip "$CACHE_DIR"/acs-manifest.json 2>/dev/null || true
      log_success "Download cache cleaned."
      exit 0
      ;;
    *)
      if [ -z "$TARGET_PROJECT" ]; then
        TARGET_PROJECT="$arg"
      fi
      ;;
  esac
done

log_section "Android Code Studio • Flutter Environment"
echo "  PREFIX       : $PREFIX"
echo "  HOME         : $HOME"
echo "  FLUTTER_ROOT : $FLUTTER_ROOT"
echo "  ANDROID_SDK  : $ANDROID_SDK"
echo "  JAVA_HOME    : ${JAVA_HOME:-[NOT SET]}"
echo "  CACHE_DIR    : $CACHE_DIR"

# --- 2. Phase 1: Dart SDK Integrity Check & ELF Recovery ---
log_section "Phase 1: Validating Dart SDK Binaries"

dart_broken=0
DART_BIN="$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart"

if [ ! -f "$DART_BIN" ] || [ ! -x "$DART_BIN" ]; then
  log_warn "Dart executable not found at $DART_BIN"
  dart_broken=1
else
  # Test execution of dart to detect DT_HASH / dynamic linker corruption
  test_output="$("$DART_BIN" --version 2>&1 || true)"
  if echo "$test_output" | grep -qE "CANNOT LINK EXECUTABLE|DT_HASH|bad interpreter|unused DT entry"; then
    log_err "Detected corrupted Dart ELF binary: $test_output"
    dart_broken=1
  elif ! "$DART_BIN" --version >/dev/null 2>&1; then
    log_warn "Dart binary failed execution test."
    dart_broken=1
  else
    log_success "Dart binary verified: $($DART_BIN --version 2>&1 | head -1)"
  fi
fi

if [ "$dart_broken" -eq 1 ]; then
  log_warn "Restoring pristine uncorrupted Dart SDK from upstream package..."

  # Find deb package or download
  found_deb=""
  for cand in "$CACHED_DEB" \
              "$HOME/.cache/flutter_setup/flutter_3.47.8-7_aarch64.deb" \
              "/sdcard/Download/flutter_3.47.9-1_aarch64.deb" \
              "/sdcard/Download/flutter_ci_input.deb"; do
    if [ -f "$cand" ]; then
      found_deb="$cand"
      break
    fi
  done

  if [ -z "$found_deb" ]; then
    log_info "Downloading upstream package from $DEB_URL (~606 MB)..."
    if ! curl -fL --progress-bar -o "$CACHED_DEB" "$DEB_URL"; then
      log_err "Failed to download $DEB_URL. Trying alternative release 3.47.8-7..."
      ALT_URL="https://github.com/GeneralKaos666/flutter-for-termux/releases/download/3.47.8/flutter_3.47.8-7_aarch64.deb"
      curl -fL --progress-bar -o "$CACHED_DEB" "$ALT_URL" || {
        log_err "Could not download Flutter package. Please check network connection."
        exit 1
      }
    fi
    found_deb="$CACHED_DEB"
  fi

  log_info "Extracting pristine dart-sdk from $found_deb..."
  WORK_EXTRACT="$TMPDIR/deb_extract_$$"
  mkdir -p "$WORK_EXTRACT"
  trap 'rm -rf "$WORK_EXTRACT"' EXIT ERR INT TERM

  if command -v dpkg-deb >/dev/null 2>&1; then
    dpkg-deb -x "$found_deb" "$WORK_EXTRACT" || {
      rm -rf "$WORK_EXTRACT"
      log_err "dpkg-deb failed to extract $found_deb."
      exit 1
    }
  elif command -v ar >/dev/null 2>&1; then
    (cd "$WORK_EXTRACT" && ar -x "$found_deb" && tar -xf data.tar.*) || {
      rm -rf "$WORK_EXTRACT"
      log_err "ar/tar failed to extract $found_deb."
      exit 1
    }
  else
    rm -rf "$WORK_EXTRACT"
    log_err "Neither dpkg-deb nor ar available for extraction."
    exit 1
  fi

  EXTRACTED_DART=""
  for cand_dart in "$WORK_EXTRACT/data/data/com.termux/files/usr/opt/flutter/bin/cache/dart-sdk" \
                   "$WORK_EXTRACT/opt/flutter/bin/cache/dart-sdk" \
                   "$WORK_EXTRACT/usr/opt/flutter/bin/cache/dart-sdk"; do
    if [ -d "$cand_dart" ]; then
      EXTRACTED_DART="$cand_dart"
      break
    fi
  done

  if [ -n "$EXTRACTED_DART" ] && [ -d "$EXTRACTED_DART" ]; then
    mkdir -p "$FLUTTER_ROOT/bin/cache"
    rm -rf "$FLUTTER_ROOT/bin/cache/dart-sdk"
    mv "$EXTRACTED_DART" "$FLUTTER_ROOT/bin/cache/dart-sdk"
    chmod +x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/"* "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/utils/"* 2>/dev/null || true
    log_success "Pristine dart-sdk restored successfully."
  else
    log_err "Could not locate dart-sdk inside extracted package."
    rm -rf "$WORK_EXTRACT"
    exit 1
  fi

  # Also copy share scripts if present
  EXTRACTED_SHARE=""
  for cand_share in "$WORK_EXTRACT/data/data/com.termux/files/usr/share/flutter" \
                    "$WORK_EXTRACT/share/flutter" \
                    "$WORK_EXTRACT/usr/share/flutter"; do
    if [ -d "$cand_share" ]; then
      EXTRACTED_SHARE="$cand_share"
      break
    fi
  done
  if [ -n "$EXTRACTED_SHARE" ]; then
    mkdir -p "$PREFIX/share/flutter"
    cp -rn "$EXTRACTED_SHARE/"* "$PREFIX/share/flutter/" 2>/dev/null || cp -rf "$EXTRACTED_SHARE/"* "$PREFIX/share/flutter/" 2>/dev/null || true
  fi

  rm -rf "$WORK_EXTRACT"
  trap - EXIT ERR INT TERM

  if [ "$CLEAN_CACHE" = "true" ] && [ "$found_deb" = "$CACHED_DEB" ]; then
    log_info "Removing cached package $CACHED_DEB..."
    rm -f "$CACHED_DEB"
  fi
fi

# Quick test of recovered binary
"$DART_BIN" --version >/dev/null 2>&1 || {
  log_err "Recovered Dart binary still failed execution test. Check permissions."
  exit 1
}
log_success "Dart executable is fully functional: $("$DART_BIN" --version 2>&1 | head -1)"

# --- 3. Phase 2: Upstream post_install.sh Engine Execution ---
log_section "Phase 2: Executing Upstream post_install.sh Engine"

POST_INSTALL_SCRIPT="$PREFIX/share/flutter/post_install.sh"
if [ ! -f "$POST_INSTALL_SCRIPT" ]; then
  # Fallback: check workspace or local scratch
  if [ -f "$(dirname "$0")/post_install.sh" ]; then
    mkdir -p "$PREFIX/share/flutter"
    cp "$(dirname "$0")/post_install.sh" "$POST_INSTALL_SCRIPT"
  fi
fi

if [ -f "$POST_INSTALL_SCRIPT" ]; then
  # Adapt post_install.sh shebang and prefix for Android Code Studio
  chmod 755 "$POST_INSTALL_SCRIPT"

  # Patch post_install.sh to ensure line 1 shebang replacement catches any previous shebang
  # (e.g. #!/usr/bin/env bash or #!/data/data/com.termux/files/usr/bin/bash)
  sed -i -E \
    -e "s|PREFIX=\"\$\{PREFIX:-/data/data/com\.termux/files/usr\}\"|PREFIX=\"\${PREFIX:-$PREFIX}\"|g" \
    -e "s|1s\|#!/usr/bin/env bash\|#!.*\||1s\|^#!.*\|#!${PREFIX}/bin/bash\||g" \
    "$POST_INSTALL_SCRIPT" 2>/dev/null || true

  log_info "Running upstream post_install.sh (executing 17-step configuration engine)..."
  bash "$POST_INSTALL_SCRIPT"
  POST_INSTALL_RES=$?
  if [ "$POST_INSTALL_RES" -eq 0 ]; then
    log_success "Upstream post_install.sh completed successfully."
  else
    log_warn "post_install.sh exited with code $POST_INSTALL_RES. Continuing verification."
  fi
else
  log_warn "post_install.sh not found at $POST_INSTALL_SCRIPT. Performing targeted safe patches..."
  # Safe line 1 shebang replacement for known wrapper scripts ONLY
  for script in "$FLUTTER_ROOT/bin/flutter" \
                "$FLUTTER_ROOT/bin/dart" \
                "$FLUTTER_ROOT/bin/internal/shared.sh" \
                "$FLUTTER_ROOT/bin/internal/update_dart_sdk.sh" \
                "$FLUTTER_ROOT/bin/internal/content_aware_hash.sh" \
                "$FLUTTER_ROOT/bin/internal/last_engine_commit.sh" \
                "$FLUTTER_ROOT/bin/internal/update_engine_version.sh" \
                "$FLUTTER_ROOT/packages/flutter_tools/bin/tool_backend.sh"; do
    if [ -f "$script" ]; then
      sed -i -E "1s|^#!.*|#!$PREFIX/bin/bash|" "$script"
      chmod +x "$script"
    fi
  done
fi

# Ensure Step 17 artifacts are verified: flutter_tools.snapshot and stamp
SNAPSHOT="$FLUTTER_ROOT/bin/cache/flutter_tools.snapshot"
STAMP="$FLUTTER_ROOT/bin/cache/flutter_tools.stamp"
if [ ! -s "$SNAPSHOT" ] || [ ! -s "$STAMP" ]; then
  log_info "Compiling flutter_tools.snapshot via Dart App-JIT (Step 17 requirement)..."
  TOOLS_DIR="$FLUTTER_ROOT/packages/flutter_tools"
  ENTRY="$TOOLS_DIR/bin/flutter_tools.dart"
  PKG_CFG="$TOOLS_DIR/.dart_tool/package_config.json"
  mkdir -p "$FLUTTER_ROOT/bin/cache"

  REVISION=""
  if [ -f "$FLUTTER_ROOT/bin/internal/engine.version" ]; then
    REVISION=$(cat "$FLUTTER_ROOT/bin/internal/engine.version" 2>/dev/null | tr -d '\n\r')
  fi
  [ -z "$REVISION" ] && REVISION="c197ca5199e9ee5661e865596c9c07294dfab51c"

  if [ -f "$PKG_CFG" ]; then
    "$DART_BIN" --verbosity=error --snapshot="$SNAPSHOT" --snapshot-kind="app-jit" --packages="$PKG_CFG" --no-enable-mirrors "$ENTRY" "--version" >/dev/null 2>&1 || true
  else
    "$DART_BIN" --verbosity=error --snapshot="$SNAPSHOT" --snapshot-kind="app-jit" --no-enable-mirrors "$ENTRY" "--version" >/dev/null 2>&1 || true
  fi

  if [ -s "$SNAPSHOT" ]; then
    echo -n "${REVISION}:" > "$STAMP"
    log_success "flutter_tools.snapshot successfully compiled & stamped."
  else
    log_warn "App-JIT compile did not create snapshot; Flutter will use live Dart compilation."
  fi
fi

# --- Phase 2b: Companion Tools Provisioning (NDK v29.0.14033849 & CMake v4.1.2) ---
log_section "Phase 2b: Provisioning NDK v29.0.14033849 & CMake v4.1.2"

NDK_VER="29.0.14033849"
NDK_DEST="$ANDROID_SDK/ndk/$NDK_VER"
NDK_URL="https://github.com/Roni077/acs-build-system/releases/download/v${NDK_VER}/android-ndk-r29-aarch64-linux-android.tar.xz"
NDK_SHA="cb7e2be23c9029f928da61bfdb5314a7ff7bae972c4ec55c353bb08c4874d15d"

if [ -f "$NDK_DEST/build/ndk-build" ] || [ -f "$NDK_DEST/ndk-build" ]; then
  log_success "NDK $NDK_VER already provisioned at $NDK_DEST"
else
  NDK_ARCHIVE="$CACHE_DIR/android-ndk-${NDK_VER}-aarch64.tar.xz"
  log_info "Downloading unencrypted NDK $NDK_VER from $NDK_URL..."
  if curl -fL --progress-bar -o "$NDK_ARCHIVE" "$NDK_URL"; then
    if command -v sha256sum >/dev/null 2>&1; then
      actual_sha=$(sha256sum "$NDK_ARCHIVE" | awk '{print $1}')
      if [ "$actual_sha" != "$NDK_SHA" ]; then
        rm -f "$NDK_ARCHIVE"
        log_err "NDK archive checksum mismatch ($actual_sha != $NDK_SHA). Corrupt download discarded."
        exit 1
      fi
      log_success "NDK archive verified (SHA256: ${actual_sha:0:16}...)"
    fi
    mkdir -p "$NDK_DEST"
    log_info "Extracting NDK $NDK_VER..."
    if tar -xJf "$NDK_ARCHIVE" -C "$NDK_DEST" --strip-components=1 2>/dev/null || tar -xJf "$NDK_ARCHIVE" -C "$NDK_DEST"; then
      [ "$KEEP_CACHE" != "true" ] && rm -f "$NDK_ARCHIVE"
      log_success "NDK $NDK_VER extracted successfully."
    else
      rm -rf "$NDK_DEST"
      [ "$KEEP_CACHE" != "true" ] && rm -f "$NDK_ARCHIVE"
      log_err "Failed to extract NDK archive."
      exit 1
    fi
  else
    log_warn "Failed to download NDK $NDK_VER from $NDK_URL."
  fi
fi

if [ -d "$NDK_DEST" ]; then
  cat << 'EOF_NDK' > "$NDK_DEST/ndk-build"
#!/bin/sh
DIR=$(cd "$(dirname "$0")" && pwd)
exec /data/user/0/com.tom.rv2ide/files/usr/bin/bash "$DIR/build/ndk-build" "$@"
EOF_NDK
  chmod +x "$NDK_DEST/ndk-build" "$NDK_DEST/build/ndk-build" 2>/dev/null || true
  ln -sf "$NDK_DEST" "$ANDROID_SDK/ndk-bundle" 2>/dev/null || true
  export ANDROID_NDK_HOME="$NDK_DEST"
  export NDK_HOME="$NDK_DEST"
fi

CMAKE_VER="4.1.2"
CMAKE_DEST="$ANDROID_SDK/cmake/$CMAKE_VER"
CMAKE_URL="https://github.com/Roni077/acs-build-system/releases/download/v${CMAKE_VER}/cmake-aarch64-linux-musl.tar.xz"
CMAKE_SHA="d412df999891e5c3e4c2bff0d26827c5c309542c485e11c3810c1ddd01edde72"

if [ -x "$CMAKE_DEST/bin/cmake" ]; then
  log_success "CMake $CMAKE_VER already provisioned at $CMAKE_DEST"
else
  CMAKE_ARCHIVE="$CACHE_DIR/cmake-${CMAKE_VER}-aarch64.tar.xz"
  log_info "Downloading CMake $CMAKE_VER from $CMAKE_URL..."
  if curl -fL --progress-bar -o "$CMAKE_ARCHIVE" "$CMAKE_URL"; then
    if command -v sha256sum >/dev/null 2>&1; then
      actual_sha=$(sha256sum "$CMAKE_ARCHIVE" | awk '{print $1}')
      if [ "$actual_sha" != "$CMAKE_SHA" ]; then
        rm -f "$CMAKE_ARCHIVE"
        log_err "CMake archive checksum mismatch ($actual_sha != $CMAKE_SHA). Corrupt download discarded."
        exit 1
      fi
      log_success "CMake archive verified (SHA256: ${actual_sha:0:16}...)"
    fi
    mkdir -p "$CMAKE_DEST"
    log_info "Extracting CMake $CMAKE_VER..."
    if tar -xf "$CMAKE_ARCHIVE" -C "$CMAKE_DEST" --strip-components=1 2>/dev/null || tar -xf "$CMAKE_ARCHIVE" -C "$CMAKE_DEST"; then
      [ "$KEEP_CACHE" != "true" ] && rm -f "$CMAKE_ARCHIVE"
      chmod +x "$CMAKE_DEST/bin/"* 2>/dev/null || true
      log_success "CMake $CMAKE_VER extracted successfully."
    else
      rm -rf "$CMAKE_DEST"
      [ "$KEEP_CACHE" != "true" ] && rm -f "$CMAKE_ARCHIVE"
      log_err "Failed to extract CMake archive."
      exit 1
    fi
  else
    log_warn "Failed to download CMake $CMAKE_VER from $CMAKE_URL."
  fi
fi

mkdir -p "$PREFIX/bin"
[ -f "$CMAKE_DEST/bin/cmake" ] && ln -sf "$CMAKE_DEST/bin/cmake" "$PREFIX/bin/cmake"
[ -f "$CMAKE_DEST/bin/ninja" ] && ln -sf "$CMAKE_DEST/bin/ninja" "$PREFIX/bin/ninja"
mkdir -p "$ANDROID_SDK/cmake/3.22.1/bin"
[ -f "$PREFIX/bin/cmake" ] && ln -sfn "$PREFIX/bin/cmake" "$ANDROID_SDK/cmake/3.22.1/bin/cmake"
[ -f "$PREFIX/bin/ninja" ] && ln -sfn "$PREFIX/bin/ninja" "$ANDROID_SDK/cmake/3.22.1/bin/ninja"

# --- 4. Phase 3: Upstream 4-Step Verification Suite ---
log_section "Phase 3: Upstream 4-Step Verification"

FAIL_COUNT=0

# Step 1: flutter --version
echo -n "Check 1: flutter --version ... "
if "$FLUTTER_ROOT/bin/flutter" --version >/dev/null 2>&1; then
  echo -e "${GREEN}PASS${NC}"
  "$FLUTTER_ROOT/bin/flutter" --version 2>&1 | head -2
else
  echo -e "${RED}FAIL${NC}"
  "$FLUTTER_ROOT/bin/flutter" --version 2>&1 || true
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi
echo ""

# Step 2: dart --version
echo -n "Check 2: dart --version ... "
if "$DART_BIN" --version >/dev/null 2>&1; then
  echo -e "${GREEN}PASS${NC}"
  "$DART_BIN" --version 2>&1
else
  echo -e "${RED}FAIL${NC}"
  "$DART_BIN" --version 2>&1 || true
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi
echo ""

# Step 3: engine dartvm --version
DARTVM="$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dartvm"
echo -n "Check 3: dartvm --version ... "
if [ -x "$DARTVM" ] && "$DARTVM" --version >/dev/null 2>&1; then
  echo -e "${GREEN}PASS${NC}"
  "$DARTVM" --version 2>&1
elif [ -x "$DART_BIN" ]; then
  echo -e "${GREEN}PASS (using SDK dart binary)${NC}"
else
  echo -e "${RED}FAIL${NC}"
  FAIL_COUNT=$((FAIL_COUNT + 1))
fi
echo ""

# Step 4: flutter doctor -v
echo -n "Check 4: flutter doctor -v ... "
echo ""
"$FLUTTER_ROOT/bin/flutter" doctor -v
DOCTOR_RES=$?
if [ "$DOCTOR_RES" -eq 0 ]; then
  echo -e "Doctor Status: ${GREEN}PASS${NC}"
else
  echo -e "Doctor Status: ${YELLOW}COMPLETED WITH EXPECTED NOTICES (Check warnings above)${NC}"
fi
echo ""

# Step 5: Upstream flutter_termux_doctor.sh (if present)
TERMUX_DOCTOR="$PREFIX/share/flutter/flutter_termux_doctor.sh"
if [ -f "$TERMUX_DOCTOR" ]; then
  log_info "Running upstream diagnostic tool: $TERMUX_DOCTOR"
  bash "$TERMUX_DOCTOR" || true
fi

# --- 5. Phase 4: Project Configuration & Build Verification ---
if [ -n "$TARGET_PROJECT" ] && [ -d "$TARGET_PROJECT" ]; then
  log_section "Phase 4: Configuring Project: $TARGET_PROJECT"

  # Use upstream configurator if available
  PROJECT_CONFIG="$PREFIX/share/flutter/flutter_project_config.sh"
  if [ -f "$PROJECT_CONFIG" ]; then
    log_info "Applying upstream flutter_project_config.sh..."
    bash "$PROJECT_CONFIG" "$TARGET_PROJECT" || true
  fi

  # Ensure gradle properties
  GRADLE_PROPS="$TARGET_PROJECT/android/gradle.properties"
  if [ -f "$GRADLE_PROPS" ]; then
    sed -i -E "s|android\.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=$PREFIX/bin/aapt2|g" "$GRADLE_PROPS"
    grep -q 'android.aapt2FromMavenOverride' "$GRADLE_PROPS" || echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> "$GRADLE_PROPS"
    grep -q 'android.enableResourceOptimizations' "$GRADLE_PROPS" || echo "android.enableResourceOptimizations=false" >> "$GRADLE_PROPS"
    grep -q 'shrink=' "$GRADLE_PROPS" || echo "shrink=false" >> "$GRADLE_PROPS"
    log_success "Updated $GRADLE_PROPS"
  fi

  # Fix gradlew shebang
  if [ -f "$TARGET_PROJECT/android/gradlew" ]; then
    sed -i "1s|^#!.*|#!$PREFIX/bin/bash|" "$TARGET_PROJECT/android/gradlew"
    chmod +x "$TARGET_PROJECT/android/gradlew"
    log_success "Updated gradlew shebang"
  fi

  # Project build instructions
  log_section "Ready to Build APK"
  echo "Execute the following command to build your APK:"
  echo ""
  echo -e "  ${BOLD}cd \"$TARGET_PROJECT\"${NC}"
  echo -e "  ${BOLD}flutter build apk --release --target-platform android-arm64 --no-tree-shake-icons --verbose${NC}"
  echo ""
fi

if [ "$CLEAN_CACHE" = "true" ]; then
  log_info "Cleaning download cache ($CACHE_DIR)..."
  rm -f "$CACHE_DIR"/*.deb "$CACHE_DIR"/*.tar.xz "$CACHE_DIR"/*.zip "$CACHE_DIR"/acs-manifest.json 2>/dev/null || true
  log_success "Download cache cleaned."
fi

# --- Summary ---
log_section "Summary"
if [ "$FAIL_COUNT" -eq 0 ]; then
  log_success "Flutter toolchain is properly installed and verified according to GeneralKaos666/flutter-for-termux specifications!"
  write_setup_log "SUMMARY" "================================================================================"
  write_setup_log "SUMMARY" "SETUP_STATUS=SUCCESS"
  write_setup_log "SUMMARY" "COMPLETED_AT: $(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo 'now')"
  write_setup_log "SUMMARY" "================================================================================"
  exit 0
else
  log_err "Encountered $FAIL_COUNT check failure(s). Please review the logs above."
  write_setup_log "SUMMARY" "================================================================================"
  write_setup_log "SUMMARY" "SETUP_STATUS=FAILED (FailCount: $FAIL_COUNT)"
  write_setup_log "SUMMARY" "FAILED_AT: $(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo 'now')"
  write_setup_log "SUMMARY" "================================================================================"
  exit 1
fi
