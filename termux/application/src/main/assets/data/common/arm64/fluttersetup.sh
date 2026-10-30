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

print_info() { printf "${Blue}[ INFO ] %s${Color_Off}\n" "$1"; }
print_success() { printf "${Green}[ SUCCESS ] %s${Color_Off}\n" "$1"; }
print_warn() { printf "${Orange}[ WARNING ] %s${Color_Off}\n" "$1"; }
print_err() { printf "${Red}[ ERROR ] %s${Color_Off}\n" "$1"; }

abort_script() {
  print_err "$1"
  exit 1
}

# Environment defaults
PREFIX="${PREFIX:-/data/data/com.tom.rv2ide/files/usr}"
HOME="${HOME:-/data/data/com.tom.rv2ide/files/home}"
ANDROID_SDK="${ANDROID_SDK:-$HOME/android-sdk}"
FLUTTER_ROOT="${FLUTTER_ROOT:-$PREFIX/opt/flutter}"

assume_yes=false
with_ndk=false
reinstall=false
run_doctor=true
flutter_version="3.47.8"
pkg_rel="7"
deb_sha256="eac81132f6060f4691cba05b86d445de79abb247ef8dd9aecf7e303f3e872474"

print_help() {
  echo "Android Code Studio - Flutter Toolchain Setup"
  echo "Usage: ${0} [options]"
  echo ""
  echo "Options:"
  echo "  -y,  --assume-yes      Run non-interactively without confirmation prompts"
  echo "  -wn, --with-ndk        Also provision the ARM64 Android NDK (r30) for native plugins"
  echo "       --reinstall       Clean existing Flutter directory before installing"
  echo "       --no-doctor       Skip post-install flutter doctor verification"
  echo "  -v,  --version <ver>   Specify Flutter version (default: 3.47.8)"
  echo "  -h,  --help            Show this help message"
}

# 1. Parse Arguments
while [ $# -gt 0 ]; do
  case "$1" in
    -y | --assume-yes) assume_yes=true; shift ;;
    -wn | --with-ndk) with_ndk=true; shift ;;
    --reinstall) reinstall=true; shift ;;
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
for pkg in git curl tar; do
  if ! command -v "$pkg" &>/dev/null; then
    print_info "Installing missing tool: $pkg..."
    pkg install -y "$pkg" 2>/dev/null || apt-get install -y "$pkg" 2>/dev/null || true
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
  rm -rf "$FLUTTER_ROOT"
fi

# If already installed and valid, skip download unless reinstall
if [ "$reinstall" != "true" ] && [ -x "$FLUTTER_ROOT/bin/flutter" ]; then
  print_info "Flutter is already installed at $FLUTTER_ROOT."
else
  # 6. Download Flutter ARM64 Release Package
  DEB_NAME="flutter_${flutter_version}-${pkg_rel}_aarch64.deb"
  DEB_URL="https://github.com/GeneralKaos666/flutter-for-termux/releases/download/${flutter_version}/${DEB_NAME}"
  CACHE_DIR="$HOME/.cache/flutter_setup"
  mkdir -p "$CACHE_DIR"
  CACHED_DEB="$CACHE_DIR/$DEB_NAME"

  # Query ACS build system manifest (Roni077/acs-build-system) for dynamic package details
  ACS_MANIFEST_URL="https://raw.githubusercontent.com/Roni077/acs-build-system/main/acs-manifest.json"
  if curl -sfL "$ACS_MANIFEST_URL" -o "$CACHE_DIR/acs-manifest.json" 2>/dev/null && command -v jq &>/dev/null; then
    manifest_url=$(jq -r '.packages[] | select(.id=="flutter-sdk" and .architecture=="arm64-v8a") | .url' "$CACHE_DIR/acs-manifest.json" 2>/dev/null | head -n 1)
    manifest_sha=$(jq -r '.packages[] | select(.id=="flutter-sdk" and .architecture=="arm64-v8a") | .sha256' "$CACHE_DIR/acs-manifest.json" 2>/dev/null | head -n 1)
    if [ -n "$manifest_url" ] && [ "$manifest_url" != "null" ]; then
      DEB_URL="$manifest_url"
      [ -n "$manifest_sha" ] && [ "$manifest_sha" != "null" ] && deb_sha256="$manifest_sha"
      print_info "Resolved Flutter package from ACS build system manifest: $DEB_URL"
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
    if ! curl -fL --progress-bar -o "$CACHED_DEB" "$DEB_URL"; then
      # Fallback to pkg_rel 6 if 7 not found
      ALT_DEB="flutter_${flutter_version}-6_aarch64.deb"
      ALT_URL="https://github.com/GeneralKaos666/flutter-for-termux/releases/download/${flutter_version}/${ALT_DEB}"
      print_warn "Primary release download failed, trying fallback: $ALT_URL..."
      curl -fL --progress-bar -o "$CACHED_DEB" "$ALT_URL" || abort_script "Failed to download Flutter package."
    fi

    # Verify SHA256 if available
    if command -v sha256sum &>/dev/null; then
      actual_sha=$(sha256sum "$CACHED_DEB" | awk '{print $1}')
      if [ -n "$deb_sha256" ] && [ "$actual_sha" != "$deb_sha256" ]; then
        print_warn "Downloaded checksum (${actual_sha:0:16}...) differs from expected pin. Proceeding with caution."
      else
        print_success "Download verified (SHA256: ${actual_sha:0:16}...)."
      fi
    fi
  fi

  # 7. Direct Package Extraction via dpkg-deb -x
  print_info "[3/7] Extracting Flutter SDK (bypassing desktop APT dependencies)..."
  WORK_DIR=$(mktemp -d 2>/dev/null || echo "$PREFIX/tmp/flutter_extract_$$")
  mkdir -p "$WORK_DIR/extracted"

  if command -v dpkg-deb &>/dev/null; then
    dpkg-deb -x "$CACHED_DEB" "$WORK_DIR/extracted"
  elif command -v ar &>/dev/null; then
    (cd "$WORK_DIR" && ar -x "$CACHED_DEB" && tar -xf data.tar.* -C "$WORK_DIR/extracted")
  else
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
    EXTRACTED_FLUTTER=$(find "$WORK_DIR/extracted" -type d -name "flutter" | grep "bin" | head -n 1 | sed 's|/bin||')
  fi

  [ -n "$EXTRACTED_FLUTTER" ] && [ -d "$EXTRACTED_FLUTTER" ] || abort_script "Could not locate extracted flutter directory."
  mv "$EXTRACTED_FLUTTER" "$FLUTTER_ROOT"

  # Copy share files if present
  if [ -d "$WORK_DIR/extracted/data/data/com.termux/files/usr/share/flutter" ]; then
    cp -rf "$WORK_DIR/extracted/data/data/com.termux/files/usr/share/flutter/"* "$PREFIX/share/flutter/" 2>/dev/null || true
  fi

  rm -rf "$WORK_DIR"
fi

# 8. Path & Shebang Relocation
print_info "[5/7] Patching paths and configuring executables..."
old_pfx="/data/data/com.termux/files/usr"
new_pfx="$PREFIX"

# Patch shell scripts and text config
for f in "$FLUTTER_ROOT/bin/flutter" "$FLUTTER_ROOT/bin/dart" "$FLUTTER_ROOT/bin/internal/shared.sh"; do
  if [ -f "$f" ] && [ -w "$f" ]; then
    sed -i "s|$old_pfx|$new_pfx|g" "$f" 2>/dev/null || true
    sed -i "s|#!/usr/bin/env bash|#!$PREFIX/bin/bash|g" "$f" 2>/dev/null || true
    sed -i "s|#!/bin/bash|#!$PREFIX/bin/bash|g" "$f" 2>/dev/null || true
  fi
done

# Set executable permissions on binaries
chmod +x "$FLUTTER_ROOT/bin/flutter" "$FLUTTER_ROOT/bin/dart" 2>/dev/null || true
chmod +x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart" 2>/dev/null || true
chmod +x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dartaotruntime" 2>/dev/null || true
chmod +x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/utils/gen_snapshot" 2>/dev/null || true

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

# 9. Optional NDK Provisioning
if [ "$with_ndk" = "true" ]; then
  print_info "Provisioning ARM64 Android NDK (r30) for native C/C++ plugins..."
  if [ -f "$PREFIX/share/flutter/post_install.sh" ]; then
    export PREFIX="$PREFIX"
    export ANDROID_SDK="$ANDROID_SDK"
    bash "$PREFIX/share/flutter/post_install.sh" --provision-ndk || print_warn "NDK provisioning had non-critical warnings."
  else
    print_warn "post_install.sh not found. Skipping automatic NDK provisioning."
  fi
fi

# 10. Environment Profile & Android SDK Integration
print_info "[6/7] Generating environment profile & configuring Flutter CLI..."
mkdir -p "$PREFIX/etc/profile.d"
cat << EOF > "$PREFIX/etc/profile.d/flutter.sh"
PREFIX="${PREFIX}"
export FLUTTER_ROOT="${FLUTTER_ROOT}"
export PATH="\${FLUTTER_ROOT}/bin:\${FLUTTER_ROOT}/bin/cache/dart-sdk/bin:\${PATH}"
export ANDROID_HOME="${ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK}"
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

# Apply current session PATH
export PATH="$FLUTTER_ROOT/bin:$FLUTTER_ROOT/bin/cache/dart-sdk/bin:$PATH"

# Configure Flutter options
print_info "Configuring Flutter SDK settings..."
"$FLUTTER_ROOT/bin/flutter" config --no-analytics >/dev/null 2>&1 || true
"$FLUTTER_ROOT/bin/flutter" config --android-sdk "$ANDROID_SDK" >/dev/null 2>&1 || true

# Auto-accept Android SDK licenses
print_info "Accepting Android SDK licenses..."
yes 2>/dev/null | "$FLUTTER_ROOT/bin/flutter" doctor --android-licenses >/dev/null 2>&1 || true

# 11. Verification
if [ "$run_doctor" = "true" ]; then
  print_info "[7/7] Verifying Flutter toolchain..."
  echo ""
  "$FLUTTER_ROOT/bin/flutter" doctor -v || true
fi

echo ""
print_success "=========================================="
print_success " Flutter SDK setup complete!"
print_success " Flutter: $FLUTTER_ROOT/bin/flutter"
print_success " Dart:    $FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart"
print_success "=========================================="
echo ""
