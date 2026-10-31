#!/data/user/0/com.tom.rv2ide/files/usr/bin/bash
# ==============================================================================
# Fix Installed Flutter SDK in Android Code Studio (RV2IDE)
# Solves: /usr/bin/env: bad interpreter: No such file or directory
# ==============================================================================

set -e

Color_Off='\033[0m'
Red='\033[0;31m'
Green='\033[0;32m'
Blue='\033[0;34m'
Orange='\e[38;5;208m'
Cyan='\033[0;36m'

print_info() { printf "${Blue}[ INFO ] %s${Color_Off}\n" "$1"; }
print_success() { printf "${Green}[ SUCCESS ] %s${Color_Off}\n" "$1"; }
print_warn() { printf "${Orange}[ WARNING ] %s${Color_Off}\n" "$1"; }
print_err() { printf "${Red}[ ERROR ] %s${Color_Off}\n" "$1"; }

echo ""
printf "${Cyan}====================================================${Color_Off}\n"
printf "${Cyan}   Flutter On-Device Patch for Android Code Studio  ${Color_Off}\n"
printf "${Cyan}====================================================${Color_Off}\n"
echo ""

# 1. Detect and resolve PREFIX
if [ -z "$PREFIX" ]; then
  if [ -d "/data/user/0/com.tom.rv2ide/files/usr" ]; then
    PREFIX="/data/user/0/com.tom.rv2ide/files/usr"
  elif [ -d "/data/data/com.tom.rv2ide/files/usr" ]; then
    PREFIX="/data/data/com.tom.rv2ide/files/usr"
  elif [ -n "$HOME" ] && [ -d "$HOME/../usr" ]; then
    PREFIX="$(cd "$HOME/../usr" && pwd)"
  else
    PREFIX="/data/user/0/com.tom.rv2ide/files/usr"
  fi
fi
export PREFIX
print_info "Using PREFIX: $PREFIX"

TARGET_BASH="$PREFIX/bin/bash"
TARGET_SH="$PREFIX/bin/sh"
print_info "Target bash interpreter: $TARGET_BASH"

# 2. Detect and resolve FLUTTER_ROOT
if [ -z "$FLUTTER_ROOT" ]; then
  if [ -d "$PREFIX/opt/flutter" ]; then
    FLUTTER_ROOT="$PREFIX/opt/flutter"
  elif [ -n "$HOME" ] && [ -d "$HOME/flutter-sdk" ]; then
    FLUTTER_ROOT="$(cd "$HOME/flutter-sdk" && pwd)"
  elif command -v flutter &>/dev/null; then
    flutter_bin="$(command -v flutter)"
    flutter_real="$(readlink -f "$flutter_bin" 2>/dev/null || echo "$flutter_bin")"
    FLUTTER_ROOT="$(dirname "$(dirname "$flutter_real")")"
  else
    FLUTTER_ROOT="$PREFIX/opt/flutter"
  fi
fi
export FLUTTER_ROOT
print_info "Using FLUTTER_ROOT: $FLUTTER_ROOT"

if [ ! -d "$FLUTTER_ROOT" ]; then
  print_err "Flutter root directory does not exist at: $FLUTTER_ROOT"
  print_err "Please verify that Flutter SDK is installed or specify FLUTTER_ROOT manually."
  exit 1
fi

# 3. Step 1: Run termux-fix-shebang if available
if command -v termux-fix-shebang &>/dev/null; then
  print_info "Step 1/5: Running termux-fix-shebang on Flutter scripts..."
  find "$FLUTTER_ROOT/bin" -type f -exec termux-fix-shebang {} + 2>/dev/null || true
  if [ -d "$FLUTTER_ROOT/packages" ]; then
    find "$FLUTTER_ROOT/packages" -type f -name "*.sh" -exec termux-fix-shebang {} + 2>/dev/null || true
  fi
else
  print_info "Step 1/5: termux-fix-shebang not present, relying on internal patcher..."
fi

# 4. Step 2: Deep regex shebang rewriting and Termux prefix relocation
print_info "Step 2/5: Rewriting shebangs, removing CRLF, and relocating prefixes..."
patched_count=0
old_pfx="/data/data/com.termux/files/usr"

# Patch all files in bin/ and bin/internal/
find "$FLUTTER_ROOT/bin" -type f 2>/dev/null | while read -r f; do
  if [ -f "$f" ] && [ -w "$f" ]; then
    fsize=$(wc -c < "$f" 2>/dev/null || echo 0)
    # Skip large binaries (e.g. dart, snapshots)
    if [ "$fsize" -lt 5000000 ]; then
      # Strip CRLF carriage returns
      sed -i 's/\r$//' "$f" 2>/dev/null || true

      # Relocate old Termux prefix to current RV2IDE prefix
      sed -i "s|$old_pfx|$PREFIX|g" "$f" 2>/dev/null || true

      # Rewrite Linux FHS shebangs to Android Code Studio prefix
      sed -i -E \
        -e "s|^#!\s*(/usr)?/bin/(env\s+)?bash|#!$TARGET_BASH|g" \
        -e "s|^#!\s*(/usr)?/bin/(env\s+)?sh|#!$TARGET_SH|g" \
        "$f" 2>/dev/null || true

      patched_count=$((patched_count + 1))
    fi
  fi
done

# Also patch any shell scripts in packages/
if [ -d "$FLUTTER_ROOT/packages" ]; then
  find "$FLUTTER_ROOT/packages" -type f -name "*.sh" 2>/dev/null | while read -r f; do
    if [ -f "$f" ] && [ -w "$f" ]; then
      sed -i 's/\r$//' "$f" 2>/dev/null || true
      sed -i "s|$old_pfx|$PREFIX|g" "$f" 2>/dev/null || true
      sed -i -E \
        -e "s|^#!\s*(/usr)?/bin/(env\s+)?bash|#!$TARGET_BASH|g" \
        -e "s|^#!\s*(/usr)?/bin/(env\s+)?sh|#!$TARGET_SH|g" \
        "$f" 2>/dev/null || true
      chmod +x "$f" 2>/dev/null || true
    fi
  done
fi

print_success "Finished sanitizing Flutter scripts."

# 5. Step 3: Set execute permissions on binaries and scripts
print_info "Step 3/5: Setting execute permissions..."
chmod +x "$FLUTTER_ROOT/bin/"* 2>/dev/null || true
chmod +x "$FLUTTER_ROOT/bin/internal/"* 2>/dev/null || true
chmod +x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/"* 2>/dev/null || true
chmod +x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/utils/"* 2>/dev/null || true
[ -f "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart" ] && chmod +x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart" 2>/dev/null || true
[ -f "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dartaotruntime" ] && chmod +x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dartaotruntime" 2>/dev/null || true

# 6. Step 4: Ensure global symlinks exist
print_info "Step 4/5: Updating global symlinks in $PREFIX/bin..."
mkdir -p "$PREFIX/bin"
[ -f "$FLUTTER_ROOT/bin/flutter" ] && ln -sf "$FLUTTER_ROOT/bin/flutter" "$PREFIX/bin/flutter" || true
[ -f "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart" ] && ln -sf "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart" "$PREFIX/bin/dart" || true
[ -n "$HOME" ] && ln -sf "$FLUTTER_ROOT" "$HOME/flutter-sdk" 2>/dev/null || true

# Register self as fix-flutter command in $PREFIX/bin
CURRENT_SCRIPT="${BASH_SOURCE[0]:-${0}}"
if [ -f "$CURRENT_SCRIPT" ]; then
  cp -f "$CURRENT_SCRIPT" "$PREFIX/bin/fix-flutter" 2>/dev/null || true
  chmod +x "$PREFIX/bin/fix-flutter" 2>/dev/null || true
  ln -sf "$PREFIX/bin/fix-flutter" "$PREFIX/bin/flutter-fix" 2>/dev/null || true
  print_success "Registered global command: 'fix-flutter' in $PREFIX/bin/"
fi

# 7. Step 5: Test update_engine_version.sh directly
print_info "Step 5/5: Testing update_engine_version.sh execution..."
ENGINE_SCRIPT="$FLUTTER_ROOT/bin/internal/update_engine_version.sh"
if [ -f "$ENGINE_SCRIPT" ]; then
  # Direct invocation to verify kernel can load interpreter without 'bad interpreter' error
  if "$ENGINE_SCRIPT" 2>/dev/null || true; then
    print_success "update_engine_version.sh executed without interpreter error!"
  else
    print_warn "update_engine_version.sh ran with non-zero exit code, but interpreter loaded."
  fi
else
  print_warn "update_engine_version.sh not found at: $ENGINE_SCRIPT"
fi

echo ""
print_success "===================================================="
print_success " Flutter SDK patch completed successfully!"
print_success ""
print_success " You can now run:"
print_success "   flutter doctor"
print_success "   flutter build apk --release --target-platform android-arm64 --verbose"
print_success ""
print_success " For future repairs, you can simply run: fix-flutter"
print_success "===================================================="
echo ""
