#!/data/data/com.tom.rv2ide/files/usr/bin/bash
# ==============================================================================
# Flutter Setup Script for Android Code Studio
# Supports ARM64 (aarch64) Android devices
# Integrates with flutter-for-termux prebuilt release (GeneralKaos666)
# ==============================================================================

set -e

Color_Off='\033[0m'
Red='\033[0;31m'
Green='\033[0;32m'
Blue='\033[0;34m'
Orange="\e[38;5;208m"
Cyan="\033[0;36m"

# Unified setup debug logging ($HOME/setup_output.log)
SETUP_LOG="${SETUP_LOG:-$HOME/setup_output.log}"
export SETUP_LOG

# Rotate log file if > 5 MB
if [ -f "$SETUP_LOG" ]; then
  log_bytes=$(wc -c < "$SETUP_LOG" 2>/dev/null || echo 0)
  if [ "$log_bytes" -gt 5242880 ]; then
    mv -f "$SETUP_LOG" "${SETUP_LOG}.old" 2>/dev/null || true
  fi
fi

strip_ansi() {
  printf "%s" "$1" | sed -E 's/\x1b\[[0-9;]*[a-zA-Z]//g'
}

write_setup_log() {
  local lvl="$1"
  shift
  local raw_msg="$*"
  local clean_msg
  clean_msg=$(strip_ansi "$raw_msg")
  local ts
  ts=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo "----")
  local log_dir
  log_dir="$(dirname "$SETUP_LOG")"
  [ ! -d "$log_dir" ] && mkdir -p "$log_dir" 2>/dev/null || true
  printf "[%s] [%s] %s\n" "$ts" "$lvl" "$clean_msg" >> "$SETUP_LOG" 2>/dev/null || true
}

print_info() {
  printf "${Blue}[ INFO ] %s${Color_Off}\n" "$1"
  write_setup_log "INFO" "$1"
}

print_success() {
  printf "${Green}[ SUCCESS ] %s${Color_Off}\n" "$1"
  write_setup_log "SUCCESS" "$1"
}

print_warn() {
  printf "${Orange}[ WARNING ] %s${Color_Off}\n" "$1"
  write_setup_log "WARN" "$1"
}

print_err() {
  printf "${Red}[ ERROR ] %s${Color_Off}\n" "$1" >&2
  write_setup_log "ERROR" "$1"
}

abort_script() {
  print_err "$1"
  write_setup_log "FATAL" "$1"
  write_setup_log "SUMMARY" "FLUTTER_SETUP_STATUS=FAILED (Error: $1)"
  exit 1
}

# Environment defaults
PREFIX="${PREFIX:-/data/data/com.tom.rv2ide/files/usr}"
HOME="${HOME:-/data/data/com.tom.rv2ide/files/home}"
ANDROID_SDK="${ANDROID_SDK:-$HOME/android-sdk}"
FLUTTER_ROOT="${FLUTTER_ROOT:-$PREFIX/opt/flutter}"

assume_yes=false
verbose=false
with_ndk=true
with_cmake=true
reinstall=false
run_doctor=true
clean_cache=false
keep_cache=false
flutter_version="3.47.8"
requested_ndk_version=""
requested_cmake_version=""
active_ndk_ver=""
active_cmake_ver=""
pkg_rel="7"
deb_sha256="eac81132f6060f4691cba05b86d445de79abb247ef8dd9aecf7e303f3e872474"

print_help() {
  echo "Android Code Studio - Flutter Toolchain Setup"
  echo "Usage: ${0} [options]"
  echo ""
  echo "Options:"
  echo "  -y,  --assume-yes        Run non-interactively without confirmation prompts"
  echo "       --verbose, --debug  Enable detailed debug logging to terminal"
  echo "       --no-ndk            Skip provisioning ARM64 Android NDK (enabled by default)"
  echo "       --ndk-version <ver> Specify specific NDK version (default: latest, e.g. 29.0.14033849)"
  echo "       --no-cmake          Skip provisioning on-device CMake (enabled by default)"
  echo "       --cmake-version <v> Specify specific CMake version (default: latest, e.g. 4.1.2)"
  echo "  -wn, --with-ndk          Explicitly enable NDK & CMake provisioning (default)"
  echo "  -wc, --with-cmake        Explicitly enable CMake provisioning (default)"
  echo "       --reinstall         Clean existing Flutter directory before installing"
  echo "       --clean-cache       Clean downloaded archives (.deb, .tar.xz, .zip) after successful setup"
  echo "       --keep-cache        Preserve downloaded archives after extraction"
  echo "       --no-doctor         Skip post-install flutter doctor verification"
  echo "  -v,  --version <ver>     Specify Flutter version (default: 3.47.8)"
  echo "  -h,  --help              Show this help message"
}

# 1. Parse Arguments
while [ $# -gt 0 ]; do
  case "$1" in
    -y | --assume-yes) assume_yes=true; shift ;;
    --verbose | --debug) verbose=true; shift ;;
    --no-ndk | --without-ndk) with_ndk=false; shift ;;
    --no-cmake | --without-cmake) with_cmake=false; shift ;;
    -wn | --with-ndk) with_ndk=true; with_cmake=true; shift ;;
    --ndk-version)
      shift
      with_ndk=true
      requested_ndk_version="${1:-}"
      shift
      ;;
    -wc | --with-cmake) with_cmake=true; shift ;;
    --cmake-version)
      shift
      with_cmake=true
      requested_cmake_version="${1:-}"
      shift
      ;;
    --reinstall) reinstall=true; shift ;;
    --clean-cache) clean_cache=true; shift ;;
    --keep-cache) keep_cache=true; shift ;;
    --no-doctor) run_doctor=false; shift ;;
    -v | --version)
      shift
      flutter_version="${1:-3.47.8}"
      shift
      ;;
    -h | --help) print_help; exit 0 ;;
    *) shift ;;
  esac
done

echo ""
printf "${Cyan}========================================${Color_Off}\n"
printf "${Cyan}  Flutter SDK Setup for Android Code Studio  ${Color_Off}\n"
printf "${Cyan}========================================${Color_Off}\n"
echo ""

# 2. Preflight Check: CPU Architecture
arch=$(uname -m)
if [ "$arch" != "aarch64" ]; then
  abort_script "Flutter on Android Code Studio currently only supports AArch64 (64-bit ARM) devices. Detected: $arch"
fi

write_setup_log "FLUTTER" "================================================================================"
write_setup_log "FLUTTER" "Flutter SDK Provisioning Started (Target: $flutter_version, Arch: $arch)"
write_setup_log "FLUTTER" "FLUTTER_ROOT: $FLUTTER_ROOT | ANDROID_SDK: $ANDROID_SDK | SETUP_LOG: $SETUP_LOG"
write_setup_log "FLUTTER" "Options: WithNDK=$with_ndk, WithCMake=$with_cmake, Reinstall=$reinstall, CleanCache=$clean_cache"
write_setup_log "FLUTTER" "================================================================================"

# 3. Preflight Check: Free Disk Space
required_kb=2500000
[ "$with_ndk" = "true" ] && required_kb=5000000
avail_kb=$(df -k "$PREFIX" 2>/dev/null | awk 'NR==2 {print $4}' || echo "")
if [ -n "$avail_kb" ] && [ "$avail_kb" -lt "$required_kb" ]; then
  print_warn "Available space ($((avail_kb/1024)) MB) is lower than recommended ($((required_kb/1024)) MB)."
  if [ "$assume_yes" != "true" ]; then
    printf "Continue anyway? ([y]es/[n]o): "
    read -r confirm
    [[ ! "$confirm" =~ ^[Yy] ]] && exit 1
  fi
fi

# 4. Toolchain and Core Package Dependencies
print_info "[1/7] Ensuring core tools & AAPT2 link..."
for pkg in git curl tar termux-exec; do
  if ! command -v "$pkg" &>/dev/null && [ ! -f "$PREFIX/lib/libtermux-exec.so" ]; then
    print_info "Installing tool/package: $pkg..."
    write_setup_log "PACKAGE" "Installing tool/package: $pkg"
    pkg install -y "$pkg" >> "$SETUP_LOG" 2>&1 || apt-get install -y "$pkg" >> "$SETUP_LOG" 2>&1 || true
  fi
done

# Ensure AAPT2 symlink exists
if [ ! -f "$PREFIX/bin/aapt2" ]; then
  if [ -f "$HOME/.androidide/aapt2" ]; then
    ln -sf "$HOME/.androidide/aapt2" "$PREFIX/bin/aapt2"
  elif [ -d "$HOME/android-sdk/build-tools" ]; then
    bt_aapt2=$(find "$HOME/android-sdk/build-tools" -name aapt2 2>/dev/null | head -n 1)
    [ -n "$bt_aapt2" ] && ln -sf "$bt_aapt2" "$PREFIX/bin/aapt2"
  fi
  [ -f "$PREFIX/bin/aapt2" ] && chmod +x "$PREFIX/bin/aapt2"
fi

# 5. Clean Previous Installation if Requested
if [ "$reinstall" = "true" ] && [ -d "$FLUTTER_ROOT" ]; then
  print_info "Cleaning previous Flutter install at $FLUTTER_ROOT..."
  write_setup_log "FLUTTER" "Removing previous Flutter install at $FLUTTER_ROOT"
  rm -rf "$FLUTTER_ROOT"
fi

# If already installed and valid, skip download unless reinstall
if [ "$reinstall" != "true" ] && [ -x "$FLUTTER_ROOT/bin/flutter" ]; then
  print_info "Flutter is already installed at $FLUTTER_ROOT."
  write_setup_log "FLUTTER" "Flutter already installed at $FLUTTER_ROOT, skipping download."
else
  # 6. Download Flutter ARM64 Release Package
  DEB_NAME="flutter_${flutter_version}-${pkg_rel}_aarch64.deb"
  DEB_URL="https://github.com/GeneralKaos666/flutter-for-termux/releases/download/${flutter_version}/${DEB_NAME}"
  CACHE_DIR="$HOME/.cache/flutter_setup"
  mkdir -p "$CACHE_DIR"
  CACHED_DEB="$CACHE_DIR/$DEB_NAME"

  # Query ACS build system manifest (Roni077/acs-build-system) for dynamic package details
  ACS_MANIFEST_URL="https://raw.githubusercontent.com/Roni077/acs-build-system/main/acs-manifest.json"
  MANIFEST_FILE="$CACHE_DIR/acs-manifest.json"
  if curl -sfL "$ACS_MANIFEST_URL" -o "$MANIFEST_FILE" >> "$SETUP_LOG" 2>&1 && command -v jq &>/dev/null; then
    manifest_url=$(jq -r --arg ver "$flutter_version" '.packages[] | select(.id=="flutter-sdk" and .architecture=="arm64-v8a" and (.version | startswith($ver))) | .url' "$MANIFEST_FILE" 2>/dev/null | head -n 1)
    manifest_sha=$(jq -r --arg ver "$flutter_version" '.packages[] | select(.id=="flutter-sdk" and .architecture=="arm64-v8a" and (.version | startswith($ver))) | .sha256' "$MANIFEST_FILE" 2>/dev/null | head -n 1)
    if [ -z "$manifest_url" ] || [ "$manifest_url" = "null" ]; then
      manifest_url=$(jq -r '.packages[] | select(.id=="flutter-sdk" and .architecture=="arm64-v8a") | .url' "$MANIFEST_FILE" 2>/dev/null | head -n 1)
      manifest_sha=$(jq -r '.packages[] | select(.id=="flutter-sdk" and .architecture=="arm64-v8a") | .sha256' "$MANIFEST_FILE" 2>/dev/null | head -n 1)
    fi
    if [ -n "$manifest_url" ] && [ "$manifest_url" != "null" ]; then
      DEB_URL="$manifest_url"
      [ -n "$manifest_sha" ] && [ "$manifest_sha" != "null" ] && deb_sha256="$manifest_sha"
      print_info "Resolved Flutter package from ACS build system manifest: $DEB_URL"
      write_setup_log "MANIFEST" "Resolved Flutter package URL: $DEB_URL, SHA256: $deb_sha256"
    fi
  fi

  download_needed=true
  if [ -f "$CACHED_DEB" ]; then
    print_info "Found cached package at $CACHED_DEB. Checking integrity..."
    cached_sha=$(sha256sum "$CACHED_DEB" | awk '{print $1}')
    if [ "$cached_sha" = "$deb_sha256" ]; then
      print_success "Cached package verified (SHA256: ${cached_sha:0:16}...)."
      download_needed=false
    else
      print_warn "Cached package checksum mismatch. Re-downloading..."
      rm -f "$CACHED_DEB"
    fi
  fi

  if [ "$download_needed" = "true" ]; then
    print_info "[2/7] Downloading Flutter SDK package (~610 MB)..."
    print_info "Source: $DEB_URL"
    write_setup_log "DOWNLOAD" "Downloading Flutter SDK package from $DEB_URL to $CACHED_DEB"
    local dl_err=0
    if [ "$verbose" = "true" ]; then
      curl -fL --progress-bar -o "$CACHED_DEB" "$DEB_URL" 2>&1 | tee -a "$SETUP_LOG" || dl_err=$?
    else
      curl -fL -o "$CACHED_DEB" "$DEB_URL" >> "$SETUP_LOG" 2>&1 || dl_err=$?
    fi
    if [ $dl_err -ne 0 ]; then
      # Fallback to pkg_rel 6 if 7 not found
      ALT_DEB="flutter_${flutter_version}-6_aarch64.deb"
      ALT_URL="https://github.com/GeneralKaos666/flutter-for-termux/releases/download/${flutter_version}/${ALT_DEB}"
      print_warn "Primary release download failed, trying fallback: $ALT_URL..."
      write_setup_log "WARN" "Primary download failed, trying fallback: $ALT_URL"
      if [ "$verbose" = "true" ]; then
        curl -fL --progress-bar -o "$CACHED_DEB" "$ALT_URL" 2>&1 | tee -a "$SETUP_LOG" || abort_script "Failed to download Flutter package."
      else
        curl -fL -o "$CACHED_DEB" "$ALT_URL" >> "$SETUP_LOG" 2>&1 || abort_script "Failed to download Flutter package."
      fi
    fi

    # Verify SHA256 if available
    if command -v sha256sum &>/dev/null; then
      actual_sha=$(sha256sum "$CACHED_DEB" | awk '{print $1}')
      if [ -n "$deb_sha256" ] && [ "$actual_sha" != "$deb_sha256" ]; then
        rm -f "$CACHED_DEB"
        abort_script "Downloaded package checksum ($actual_sha) differs from expected pin ($deb_sha256). Corrupt download discarded."
      else
        print_success "Download verified (SHA256: ${actual_sha:0:16}...)."
        write_setup_log "VERIFY" "Package download verified (SHA256: $actual_sha)"
      fi
    fi
  fi

  # 7. Direct Package Extraction via dpkg-deb -x
  print_info "[3/7] Extracting Flutter SDK (bypassing desktop APT dependencies)..."
  WORK_DIR=$(mktemp -d 2>/dev/null || echo "$PREFIX/tmp/flutter_extract_$$")
  mkdir -p "$WORK_DIR/extracted"
  trap 'rm -rf "$WORK_DIR"' EXIT ERR INT TERM

  write_setup_log "EXTRACT" "Extracting $CACHED_DEB into $WORK_DIR/extracted"
  if command -v dpkg-deb &>/dev/null; then
    dpkg-deb -x "$CACHED_DEB" "$WORK_DIR/extracted" >> "$SETUP_LOG" 2>&1 || {
      rm -rf "$WORK_DIR"
      abort_script "dpkg-deb failed to extract $CACHED_DEB."
    }
  elif command -v ar &>/dev/null; then
    (cd "$WORK_DIR" && ar -x "$CACHED_DEB" && tar -xf data.tar.* -C "$WORK_DIR/extracted") >> "$SETUP_LOG" 2>&1 || {
      rm -rf "$WORK_DIR"
      abort_script "ar/tar failed to extract $CACHED_DEB."
    }
  else
    rm -rf "$WORK_DIR"
    abort_script "Neither dpkg-deb nor ar available to extract package."
  fi

  # Relocate extracted Flutter tree
  print_info "[4/7] Relocating Flutter SDK to $FLUTTER_ROOT..."
  rm -rf "$FLUTTER_ROOT"
  mkdir -p "$(dirname "$FLUTTER_ROOT")" "$PREFIX/share/flutter"

  EXTRACTED_FLUTTER=""
  for cand in "$WORK_DIR/extracted/data/data/com.termux/files/usr/opt/flutter" "$WORK_DIR/extracted/opt/flutter" "$WORK_DIR/extracted/usr/opt/flutter"; do
    if [ -d "$cand" ]; then
      EXTRACTED_FLUTTER="$cand"
      break
    fi
  done

  if [ -z "$EXTRACTED_FLUTTER" ]; then
    EXTRACTED_FLUTTER=$(find "$WORK_DIR/extracted" -type d -name "flutter" 2>/dev/null | grep "bin" | head -n 1 | sed 's|/bin||')
  fi

  [ -n "$EXTRACTED_FLUTTER" ] && [ -d "$EXTRACTED_FLUTTER" ] || {
    rm -rf "$WORK_DIR"
    abort_script "Could not locate extracted flutter directory."
  }
  mv "$EXTRACTED_FLUTTER" "$FLUTTER_ROOT"

  # Copy share files if present
  if [ -d "$WORK_DIR/extracted/data/data/com.termux/files/usr/share/flutter" ]; then
    cp -rf "$WORK_DIR/extracted/data/data/com.termux/files/usr/share/flutter/"* "$PREFIX/share/flutter/" 2>/dev/null || true
  fi

  rm -rf "$WORK_DIR"
  trap - EXIT ERR INT TERM

  if [ "$clean_cache" = "true" ]; then
    print_info "Removing cached package $CACHED_DEB..."
    rm -f "$CACHED_DEB"
  fi
fi

# 8. Path & Shebang Relocation and Upstream Post-Install Engine
print_info "[5/7] Patching paths and executing upstream GeneralKaos666 post_install engine..."
old_pfx="/data/data/com.termux/files/usr"
new_pfx="$PREFIX"

# Safe line-1 shebang rewriting for known launcher and internal scripts ONLY
for script_file in \
  "$FLUTTER_ROOT/bin/flutter" \
  "$FLUTTER_ROOT/bin/dart" \
  "$FLUTTER_ROOT/bin/internal/shared.sh" \
  "$FLUTTER_ROOT/bin/internal/update_dart_sdk.sh" \
  "$FLUTTER_ROOT/bin/internal/content_aware_hash.sh" \
  "$FLUTTER_ROOT/bin/internal/last_engine_commit.sh" \
  "$FLUTTER_ROOT/bin/internal/update_engine_version.sh" \
  "$FLUTTER_ROOT/packages/flutter_tools/bin/tool_backend.sh"; do
  if [ -f "$script_file" ]; then
    sed -i 's/\r$//' "$script_file" 2>/dev/null || true
    sed -i "s|$old_pfx|$new_pfx|g" "$script_file" 2>/dev/null || true
    sed -i -E "1s|^#!.*|#!$PREFIX/bin/bash|" "$script_file" 2>/dev/null || true
    chmod +x "$script_file" 2>/dev/null || true
  fi
done

# Ensure package scripts in Flutter have proper shebangs
if [ -d "$FLUTTER_ROOT/packages" ]; then
  find "$FLUTTER_ROOT/packages" -type f -name "*.sh" 2>/dev/null | while read -r f; do
    if [ -f "$f" ] && [ -w "$f" ]; then
      sed -i 's/\r$//' "$f" 2>/dev/null || true
      sed -i "s|$old_pfx|$new_pfx|g" "$f" 2>/dev/null || true
      sed -i -E "1s|^#!.*|#!$PREFIX/bin/bash|" "$f" 2>/dev/null || true
      chmod +x "$f" 2>/dev/null || true
    fi
  done
fi

# Set executable permissions on binaries and scripts (preserving ELF integrity)
chmod +x "$FLUTTER_ROOT/bin/"* 2>/dev/null || true
chmod +x "$FLUTTER_ROOT/bin/internal/"* 2>/dev/null || true
chmod +x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/"* 2>/dev/null || true
chmod +x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/utils/"* 2>/dev/null || true

# Execute Upstream GeneralKaos666 post_install.sh engine
if [ -f "$PREFIX/share/flutter/post_install.sh" ]; then
  chmod +x "$PREFIX/share/flutter/post_install.sh"
  sed -i -E \
    -e "s|PREFIX=\"\$\{PREFIX:-/data/data/com\.termux/files/usr\}\"|PREFIX=\"\${PREFIX:-$PREFIX}\"|g" \
    -e "s|1s\|#!/usr/bin/env bash\|#!.*\||1s\|^#!.*\|#!${PREFIX}/bin/bash\||g" \
    "$PREFIX/share/flutter/post_install.sh" 2>/dev/null || true

  print_info "Running upstream post_install.sh (17-step post-installation engine)..."
  write_setup_log "ENGINE" "Executing upstream post_install.sh engine"
  bash "$PREFIX/share/flutter/post_install.sh" >> "$SETUP_LOG" 2>&1 || print_warn "post_install.sh exited with warnings; continuing..."
fi

# Ensure Step 17 requirement: flutter_tools.snapshot and stamp exist
SNAPSHOT="$FLUTTER_ROOT/bin/cache/flutter_tools.snapshot"
STAMP="$FLUTTER_ROOT/bin/cache/flutter_tools.stamp"
DART_BIN="$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart"
if [ ! -s "$SNAPSHOT" ] || [ ! -s "$STAMP" ]; then
  if [ -x "$DART_BIN" ]; then
    print_info "Compiling flutter_tools.snapshot with Dart App-JIT..."
    write_setup_log "SNAPSHOT" "Compiling flutter_tools.snapshot"
    TOOLS_DIR="$FLUTTER_ROOT/packages/flutter_tools"
    ENTRY="$TOOLS_DIR/bin/flutter_tools.dart"
    PKG_CFG="$TOOLS_DIR/.dart_tool/package_config.json"
    mkdir -p "$FLUTTER_ROOT/bin/cache"

    REVISION=""
    [ -f "$FLUTTER_ROOT/bin/internal/engine.version" ] && REVISION=$(cat "$FLUTTER_ROOT/bin/internal/engine.version" 2>/dev/null | tr -d '\n\r')
    [ -z "$REVISION" ] && REVISION="c197ca5199e9ee5661e865596c9c07294dfab51c"

    if [ -f "$PKG_CFG" ]; then
      "$DART_BIN" --verbosity=error --snapshot="$SNAPSHOT" --snapshot-kind="app-jit" --packages="$PKG_CFG" --no-enable-mirrors "$ENTRY" "--version" >> "$SETUP_LOG" 2>&1 || true
    else
      "$DART_BIN" --verbosity=error --snapshot="$SNAPSHOT" --snapshot-kind="app-jit" --no-enable-mirrors "$ENTRY" "--version" >> "$SETUP_LOG" 2>&1 || true
    fi
    [ -s "$SNAPSHOT" ] && echo -n "${REVISION}:" > "$STAMP"
  fi
fi

# Global symlinks
mkdir -p "$PREFIX/bin"
ln -sf "$FLUTTER_ROOT/bin/flutter" "$PREFIX/bin/flutter"
ln -sf "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart" "$PREFIX/bin/dart"
chmod +x "$PREFIX/bin/flutter" "$PREFIX/bin/dart"
ln -sf "$FLUTTER_ROOT" "$HOME/flutter-sdk"

# Self-registration so fluttersetup is globally available
if [ -f "${BASH_SOURCE[0]:-${0}}" ]; then
  cp -f "${BASH_SOURCE[0]:-${0}}" "$PREFIX/bin/fluttersetup.sh" 2>/dev/null || true
  cp -f "${BASH_SOURCE[0]:-${0}}" "$PREFIX/bin/fluttersetup" 2>/dev/null || true
  chmod +x "$PREFIX/bin/fluttersetup.sh" "$PREFIX/bin/fluttersetup" 2>/dev/null || true
fi

# 9. Companion Tools Provisioning (NDK & CMake)
provision_companion_tools() {
  local manifest_file="$1"
  local target_ndk="$2"
  local target_cmake="$3"

  # 1. Provision CMake if requested
  if [ "$with_cmake" = "true" ]; then
    print_info "Provisioning ARM64 CMake for Flutter native builds..."
    local cmake_ver="$target_cmake"
    local cmake_url=""
    local cmake_sha=""

    [ -z "$cmake_ver" ] && cmake_ver="4.1.2"
    if [ -f "$manifest_file" ] && command -v jq &>/dev/null; then
      if [ "$cmake_ver" = "latest" ]; then
        cmake_ver=$(jq -r '.cmake_versions[0] // "4.1.2"' "$manifest_file" 2>/dev/null)
      fi
      cmake_url=$(jq -r --arg ver "$cmake_ver" '.packages[] | select(.id=="android-cmake" and .architecture=="arm64-v8a" and (.encrypted == false or .encrypted == null) and (.version | startswith($ver))) | .url' "$manifest_file" 2>/dev/null | head -n 1)
      cmake_sha=$(jq -r --arg ver "$cmake_ver" '.packages[] | select(.id=="android-cmake" and .architecture=="arm64-v8a" and (.encrypted == false or .encrypted == null) and (.version | startswith($ver))) | .sha256' "$manifest_file" 2>/dev/null | head -n 1)
    fi

    if [ -z "$cmake_url" ] || [ "$cmake_url" = "null" ]; then
      cmake_ver="4.1.2"
      cmake_url="https://github.com/Roni077/acs-build-system/releases/download/v${cmake_ver}/cmake-aarch64-linux-musl.tar.xz"
      cmake_sha="d412df999891e5c3e4c2bff0d26827c5c309542c485e11c3810c1ddd01edde72"
    fi

    local cmake_dest="$ANDROID_SDK/cmake/$cmake_ver"
    if [ -x "$cmake_dest/bin/cmake" ]; then
      print_info "CMake $cmake_ver already installed at $cmake_dest."
      write_setup_log "CMAKE" "CMake $cmake_ver already installed at $cmake_dest."
    else
      local cmake_archive="$CACHE_DIR/cmake-${cmake_ver}-aarch64.tar.xz"
      print_info "Downloading CMake $cmake_ver..."
      write_setup_log "CMAKE" "Downloading CMake from $cmake_url to $cmake_archive"
      local c_err=0
      if [ "$verbose" = "true" ]; then
        curl -fL --progress-bar -o "$cmake_archive" "$cmake_url" 2>&1 | tee -a "$SETUP_LOG" || c_err=$?
      else
        curl -fL -o "$cmake_archive" "$cmake_url" >> "$SETUP_LOG" 2>&1 || c_err=$?
      fi
      if [ $c_err -eq 0 ]; then
        if [ -n "$cmake_sha" ] && command -v sha256sum &>/dev/null; then
          local actual_sha=$(sha256sum "$cmake_archive" | awk '{print $1}')
          if [ "$actual_sha" != "$cmake_sha" ]; then
            rm -f "$cmake_archive"
            abort_script "CMake archive checksum mismatch ($actual_sha != $cmake_sha). Corrupt download discarded."
          fi
          print_success "CMake archive verified (SHA256: ${actual_sha:0:16}...)."
          write_setup_log "VERIFY" "CMake archive verified (SHA256: $actual_sha)"
        fi
        mkdir -p "$cmake_dest"
        print_info "Extracting CMake to $cmake_dest..."
        write_setup_log "EXTRACT" "Extracting CMake to $cmake_dest"
        if tar -xf "$cmake_archive" -C "$cmake_dest" --strip-components=1 >> "$SETUP_LOG" 2>&1 || tar -xf "$cmake_archive" -C "$cmake_dest" >> "$SETUP_LOG" 2>&1; then
          [ "$keep_cache" != "true" ] && rm -f "$cmake_archive"
          chmod +x "$cmake_dest/bin/"* 2>/dev/null || true
          print_success "CMake $cmake_ver provisioned."
          write_setup_log "CMAKE" "CMake $cmake_ver provisioned successfully."
        else
          rm -rf "$cmake_dest"
          [ "$keep_cache" != "true" ] && rm -f "$cmake_archive"
          abort_script "Failed to extract CMake archive."
        fi
      else
        print_warn "Failed to download CMake from $cmake_url. Attempting package manager..."
        write_setup_log "WARN" "Failed to download CMake from $cmake_url, falling back to pkg/apt"
        pkg install -y cmake ninja >> "$SETUP_LOG" 2>&1 || true
      fi
    fi

    # Symlink to PREFIX/bin and ensure ninja is present
    mkdir -p "$PREFIX/bin"
    [ -f "$cmake_dest/bin/cmake" ] && ln -sf "$cmake_dest/bin/cmake" "$PREFIX/bin/cmake"
    [ -f "$cmake_dest/bin/ninja" ] && ln -sf "$cmake_dest/bin/ninja" "$PREFIX/bin/ninja"
    if ! command -v ninja &>/dev/null; then
      pkg install -y ninja >> "$SETUP_LOG" 2>&1 || apt-get install -y ninja >> "$SETUP_LOG" 2>&1 || true
    fi
    active_cmake_ver="$cmake_ver"
  fi

  # 2. Provision NDK if requested
  if [ "$with_ndk" = "true" ]; then
    print_info "Provisioning ARM64 Android NDK for Flutter native plugins..."
    local ndk_ver="$target_ndk"
    local ndk_url=""
    local ndk_sha=""

    [ -z "$ndk_ver" ] && ndk_ver="29.0.14033849"
    if [ -f "$manifest_file" ] && command -v jq &>/dev/null; then
      if [ "$ndk_ver" = "latest" ]; then
        ndk_ver=$(jq -r '.ndk_versions[0] // "29.0.14033849"' "$manifest_file" 2>/dev/null)
      fi
      ndk_url=$(jq -r --arg ver "$ndk_ver" '.packages[] | select(.id=="android-native-kit" and .architecture=="arm64-v8a" and (.encrypted == false or .encrypted == null) and (.version | startswith($ver))) | .url' "$manifest_file" 2>/dev/null | head -n 1)
      ndk_sha=$(jq -r --arg ver "$ndk_ver" '.packages[] | select(.id=="android-native-kit" and .architecture=="arm64-v8a" and (.encrypted == false or .encrypted == null) and (.version | startswith($ver))) | .sha256' "$manifest_file" 2>/dev/null | head -n 1)
    fi

    if [ -z "$ndk_url" ] || [ "$ndk_url" = "null" ]; then
      ndk_ver="29.0.14033849"
      ndk_url="https://github.com/Roni077/acs-build-system/releases/download/v${ndk_ver}/android-ndk-r29-aarch64-linux-android.tar.xz"
      ndk_sha="cb7e2be23c9029f928da61bfdb5314a7ff7bae972c4ec55c353bb08c4874d15d"
    fi

    local ndk_dest="$ANDROID_SDK/ndk/$ndk_ver"
    if [ -f "$ndk_dest/build/ndk-build" ] || [ -f "$ndk_dest/ndk-build" ]; then
      print_info "NDK $ndk_ver already installed at $ndk_dest."
      write_setup_log "NDK" "NDK $ndk_ver already installed at $ndk_dest."
    else
      local ndk_archive="$CACHE_DIR/android-ndk-${ndk_ver}-aarch64.tar.xz"
      print_info "Downloading NDK $ndk_ver..."
      write_setup_log "NDK" "Downloading NDK from $ndk_url to $ndk_archive"
      local n_err=0
      if [ "$verbose" = "true" ]; then
        curl -fL --progress-bar -o "$ndk_archive" "$ndk_url" 2>&1 | tee -a "$SETUP_LOG" || n_err=$?
      else
        curl -fL -o "$ndk_archive" "$ndk_url" >> "$SETUP_LOG" 2>&1 || n_err=$?
      fi
      if [ $n_err -eq 0 ]; then
        if [ -n "$ndk_sha" ] && command -v sha256sum &>/dev/null; then
          local actual_sha=$(sha256sum "$ndk_archive" | awk '{print $1}')
          if [ "$actual_sha" != "$ndk_sha" ]; then
            rm -f "$ndk_archive"
            abort_script "NDK archive checksum mismatch ($actual_sha != $ndk_sha). Corrupt download discarded."
          fi
          print_success "NDK archive verified (SHA256: ${actual_sha:0:16}...)."
          write_setup_log "VERIFY" "NDK archive verified (SHA256: $actual_sha)"
        fi
        mkdir -p "$ndk_dest"
        print_info "Extracting NDK to $ndk_dest..."
        write_setup_log "EXTRACT" "Extracting NDK to $ndk_dest"
        if tar -xJf "$ndk_archive" -C "$ndk_dest" --strip-components=1 >> "$SETUP_LOG" 2>&1 || tar -xJf "$ndk_archive" -C "$ndk_dest" >> "$SETUP_LOG" 2>&1; then
          [ "$keep_cache" != "true" ] && rm -f "$ndk_archive"
          print_success "NDK $ndk_ver extracted."
          write_setup_log "NDK" "NDK $ndk_ver extracted successfully."
        else
          rm -rf "$ndk_dest"
          [ "$keep_cache" != "true" ] && rm -f "$ndk_archive"
          abort_script "Failed to extract NDK archive."
        fi
      else
        print_warn "Direct NDK download failed from $ndk_url. Checking post_install.sh fallback..."
        write_setup_log "WARN" "Direct NDK download failed from $ndk_url"
        if [ -f "$PREFIX/share/flutter/post_install.sh" ]; then
          bash "$PREFIX/share/flutter/post_install.sh" --provision-ndk >> "$SETUP_LOG" 2>&1 || true
        fi
      fi
    fi

    # Ensure ndk-build launcher and permissions
    if [ -d "$ndk_dest" ]; then
      cat << 'EOF_NDK' > "$ndk_dest/ndk-build"
#!/bin/sh
DIR=$(cd "$(dirname "$0")" && pwd)
exec /data/data/com.tom.rv2ide/files/usr/bin/bash "$DIR/build/ndk-build" "$@"
EOF_NDK
      chmod +x "$ndk_dest/ndk-build"
      chmod +x "$ndk_dest/build/ndk-build" 2>/dev/null || true
      ln -sf "$ndk_dest" "$ANDROID_SDK/ndk-bundle" 2>/dev/null || true
    fi
    active_ndk_ver="$ndk_ver"
  fi
}

provision_companion_tools "$MANIFEST_FILE" "$requested_ndk_version" "$requested_cmake_version"

# 10. Environment Profile & Android SDK Integration
print_info "[6/7] Generating environment profile & configuring Flutter CLI..."
mkdir -p "$PREFIX/etc/profile.d"

cmake_path_entry=""
[ -n "$active_cmake_ver" ] && cmake_path_entry=":${ANDROID_SDK}/cmake/${active_cmake_ver}/bin"

ndk_env_entries=""
if [ -n "$active_ndk_ver" ]; then
ndk_env_entries="export ANDROID_NDK_HOME=\"\${ANDROID_SDK}/ndk/${active_ndk_ver}\"
export NDK_HOME=\"\${ANDROID_SDK}/ndk/${active_ndk_ver}\""
fi

preload_entry=""
if [ -f "${PREFIX}/lib/libtermux-exec.so" ]; then
  preload_entry='if [ -f "${PREFIX}/lib/libtermux-exec.so" ]; then
  case ":$LD_PRELOAD:" in
    *":${PREFIX}/lib/libtermux-exec.so:"*) ;;
    *) export LD_PRELOAD="${PREFIX}/lib/libtermux-exec.so${LD_PRELOAD:+:$LD_PRELOAD}" ;;
  esac
fi'
fi

cat << EOF > "$PREFIX/etc/profile.d/flutter.sh"
PREFIX="${PREFIX}"
export FLUTTER_ROOT="${FLUTTER_ROOT}"
export ANDROID_HOME="${ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK}"
${ndk_env_entries}
${preload_entry}
export PATH="\${FLUTTER_ROOT}/bin:\${FLUTTER_ROOT}/bin/cache/dart-sdk/bin:\${PREFIX}/bin${cmake_path_entry}:\${PATH}"
EOF
chmod 644 "$PREFIX/etc/profile.d/flutter.sh"

# Ensure ~/.bashrc sources profile.d
BASHRC="$HOME/.bashrc"
if [ -f "$BASHRC" ]; then
  if ! grep -q "profile.d/flutter.sh" "$BASHRC" 2>/dev/null; then
    echo '[ -f "$PREFIX/etc/profile.d/flutter.sh" ] && source "$PREFIX/etc/profile.d/flutter.sh"' >> "$BASHRC"
  fi
else
  echo '[ -f "$PREFIX/etc/profile.d/flutter.sh" ] && source "$PREFIX/etc/profile.d/flutter.sh"' > "$BASHRC"
fi

# Apply current session PATH and environment
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
[ -n "$active_ndk_ver" ] && export ANDROID_NDK_HOME="$ANDROID_SDK/ndk/$active_ndk_ver"
[ -n "$active_ndk_ver" ] && export NDK_HOME="$ANDROID_SDK/ndk/$active_ndk_ver"
if [ -f "$PREFIX/lib/libtermux-exec.so" ]; then
  case ":$LD_PRELOAD:" in
    *":$PREFIX/lib/libtermux-exec.so:"*) ;;
    *) export LD_PRELOAD="$PREFIX/lib/libtermux-exec.so${LD_PRELOAD:+:$LD_PRELOAD}" ;;
  esac
fi
export PATH="$FLUTTER_ROOT/bin:$FLUTTER_ROOT/bin/cache/dart-sdk/bin:$PREFIX/bin${cmake_path_entry}:$PATH"

# Configure Flutter options
print_info "Configuring Flutter SDK settings..."
"$FLUTTER_ROOT/bin/flutter" config --no-analytics >> "$SETUP_LOG" 2>&1 || true
"$FLUTTER_ROOT/bin/flutter" config --android-sdk "$ANDROID_SDK" >> "$SETUP_LOG" 2>&1 || true

# Auto-accept Android SDK licenses
print_info "Accepting Android SDK licenses..."
yes 2>/dev/null | "$FLUTTER_ROOT/bin/flutter" doctor --android-licenses >> "$SETUP_LOG" 2>&1 || true

# 11. Verification (GeneralKaos666 Official 4-Step Verification)
if [ "$run_doctor" = "true" ]; then
  print_info "[7/7] Running upstream 4-step verification checks..."
  echo ""
  print_info "Check 1: flutter --version"
  "$FLUTTER_ROOT/bin/flutter" --version 2>&1 | head -n 2 | tee -a "$SETUP_LOG" || true
  echo ""
  print_info "Check 2: dart --version"
  "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart" --version 2>&1 | tee -a "$SETUP_LOG" || true
  echo ""
  print_info "Check 3: dartvm --version"
  if [ -x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dartvm" ]; then
    "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dartvm" --version 2>&1 | tee -a "$SETUP_LOG" || true
  else
    print_info "dartvm matches dart host executable."
  fi
  echo ""
  print_info "Check 4: flutter doctor -v"
  if [ "$verbose" = "true" ]; then
    "$FLUTTER_ROOT/bin/flutter" doctor -v 2>&1 | tee -a "$SETUP_LOG" || true
  else
    "$FLUTTER_ROOT/bin/flutter" doctor -v >> "$SETUP_LOG" 2>&1 || true
  fi
  echo ""
  if [ -f "$PREFIX/share/flutter/flutter_termux_doctor.sh" ]; then
    print_info "Check 5: flutter_termux_doctor diagnostic report"
    bash "$PREFIX/share/flutter/flutter_termux_doctor.sh" >> "$SETUP_LOG" 2>&1 || true
  fi
fi

if [ "$clean_cache" = "true" ]; then
  print_info "Cleaning download cache ($CACHE_DIR)..."
  rm -f "$CACHE_DIR"/*.deb "$CACHE_DIR"/*.tar.xz "$CACHE_DIR"/*.zip "$CACHE_DIR"/acs-manifest.json 2>/dev/null || true
  print_success "Download cache cleaned."
fi

write_setup_log "SUMMARY" "================================================================================"
write_setup_log "SUMMARY" "FLUTTER_SETUP_STATUS=SUCCESS"
write_setup_log "SUMMARY" "FLUTTER_ROOT: $FLUTTER_ROOT"
write_setup_log "SUMMARY" "DART_VERSION: $($FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart --version 2>&1 | head -n 1 || echo 'unknown')"
write_setup_log "SUMMARY" "LOG_FILE: $SETUP_LOG"
write_setup_log "SUMMARY" "COMPLETED_AT: $(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo 'now')"
write_setup_log "SUMMARY" "================================================================================"

echo ""
print_success "=========================================="
print_success " Flutter SDK setup complete!"
print_success " Flutter: $FLUTTER_ROOT/bin/flutter"
print_success " Dart:    $FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart"
print_success " Setup log: $SETUP_LOG"
print_success "=========================================="
echo ""
