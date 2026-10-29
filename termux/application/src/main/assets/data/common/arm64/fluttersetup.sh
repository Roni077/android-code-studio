#!/data/data/com.tom.rv2ide/files/usr/bin/bash
# ==============================================================================
# Flutter Setup Script for Android Code Studio
# Supports ARM64 (aarch64) Android devices
# Integrates with flutter-for-termux
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
  abort_script "Flutter on Termux currently only supports AArch64 (64-bit ARM) devices. Detected: $arch"
fi

# 3. Preflight Check: Free Disk Space
required_kb=2500000
[ "$with_ndk" = "true" ] && required_kb=5000000
avail_kb=$(df "$PREFIX" 2>/dev/null | awk 'NR==2 {print $4}' || echo "")
if [ -n "$avail_kb" ] && [ "$avail_kb" -lt "$required_kb" ]; then
  print_warn "Available space ($((avail_kb/1024)) MB) is lower than recommended ($((required_kb/1024)) MB)."
  if [ "$assume_yes" != "true" ]; then
    printf "Continue anyway? ([y]es/[n]o): "
    read -r confirm
    [[ ! "$confirm" =~ ^[Yy] ]] && exit 1
  fi
fi

# 4. Prerequisites via pkg/apt
print_info "[1/6] Installing toolchain dependencies..."
apt update -y || true
apt --fix-broken install -y || true
pkg install -y x11-repo gnupg curl tar 7zip git which gtk3 xorgproto ninja cmake clang pkg-config aapt2 termux-elf-cleaner

# Ensure JDK 17 or 21 is present
if [ ! -d "$PREFIX/lib/jvm/java-17-openjdk" ] && [ ! -d "$PREFIX/lib/jvm/java-21-openjdk" ]; then
  print_info "Installing OpenJDK 17..."
  pkg install -y openjdk-17
fi

# 5. Configure APT Repository
print_info "[2/6] Configuring Flutter APT repository..."
mkdir -p "$PREFIX/etc/apt/keyrings"
curl -fsSL https://generalkaos666.github.io/termux-apt-repo/key.gpg \
  -o "$PREFIX/etc/apt/keyrings/termux-apt-repo.gpg"

echo "deb [signed-by=$PREFIX/etc/apt/keyrings/termux-apt-repo.gpg] https://generalkaos666.github.io/termux-apt-repo stable main" \
  > "$PREFIX/etc/apt/sources.list.d/termux-apt-repo.list"

pkg update -y

# 6. Reinstall handling
if [ "$reinstall" = "true" ] && [ -d "$FLUTTER_ROOT" ]; then
  print_info "Cleaning previous Flutter install at $FLUTTER_ROOT..."
  rm -rf "$FLUTTER_ROOT"
fi

# 7. Package Installation
print_info "[3/6] Installing Flutter package (${flutter_version})..."
pkg install -y flutter

# 8. Post-Install Configuration
print_info "[4/6] Executing post-install assembly & patches..."
export PREFIX="$PREFIX"
export ANDROID_SDK="$ANDROID_SDK"
if [ -f "$PREFIX/share/flutter/post_install.sh" ]; then
  bash "$PREFIX/share/flutter/post_install.sh"

  if [ "$with_ndk" = "true" ]; then
    print_info "Provisioning ARM64 Android NDK repack..."
    bash "$PREFIX/share/flutter/post_install.sh" --provision-ndk
  fi
else
  abort_script "post_install.sh not found at $PREFIX/share/flutter/post_install.sh"
fi

# 9. Profile and Symlinks
print_info "[5/6] Setting up environment variables and symlinks..."
mkdir -p "$PREFIX/etc/profile.d"
cat << 'EOF' > "$PREFIX/etc/profile.d/flutter.sh"
export FLUTTER_ROOT="$PREFIX/opt/flutter"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export PATH="$FLUTTER_ROOT/bin:$FLUTTER_ROOT/bin/cache/dart-sdk/bin:$PATH"
EOF
chmod 644 "$PREFIX/etc/profile.d/flutter.sh"

ln -sf "$FLUTTER_ROOT/bin/flutter" "$PREFIX/bin/flutter"
ln -sf "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart" "$PREFIX/bin/dart"

# 10. Verification
if [ "$run_doctor" = "true" ]; then
  print_info "[6/6] Verifying toolchain with flutter doctor..."
  export PATH="$FLUTTER_ROOT/bin:$FLUTTER_ROOT/bin/cache/dart-sdk/bin:$PATH"
  flutter doctor -v || true
fi

echo ""
print_success "=========================================="
print_success " Flutter setup complete!"
print_success " Flutter: $FLUTTER_ROOT/bin/flutter"
print_success " Dart:    $FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart"
print_success "=========================================="
echo ""
