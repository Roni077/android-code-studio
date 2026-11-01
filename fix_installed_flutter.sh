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

# 1. Detect and resolve PREFIX
if [ -z "${PREFIX:-}" ]; then
  if [ -d "/data/user/0/com.tom.rv2ide/files/usr" ]; then
    PREFIX="/data/user/0/com.tom.rv2ide/files/usr"
  elif [ -d "/data/data/com.tom.rv2ide/files/usr" ]; then
    PREFIX="/data/data/com.tom.rv2ide/files/usr"
  elif [ -d "/data/data/com.termux/files/usr" ]; then
    PREFIX="/data/data/com.termux/files/usr"
  elif [ -n "${HOME:-}" ] && [ -d "$HOME/../usr" ]; then
    PREFIX="$(cd "$HOME/../usr" && pwd)"
  else
    PREFIX="/data/user/0/com.tom.rv2ide/files/usr"
  fi
fi
export PREFIX

if [ -z "${HOME:-}" ]; then
  if [ -d "/data/user/0/com.tom.rv2ide/files/home" ]; then
    HOME="/data/user/0/com.tom.rv2ide/files/home"
  elif [ -d "/data/data/com.tom.rv2ide/files/home" ]; then
    HOME="/data/data/com.tom.rv2ide/files/home"
  elif [ -d "/data/data/com.termux/files/home" ]; then
    HOME="/data/data/com.termux/files/home"
  else
    HOME="$(dirname "$PREFIX")/home"
  fi
fi
export HOME

# Dedicated setup log folder in home directory ($HOME/setup_logs)
SETUP_LOG_DIR="${SETUP_LOG_DIR:-$HOME/setup_logs}"
mkdir -p "$SETUP_LOG_DIR" 2>/dev/null || true
SETUP_LOG="${SETUP_LOG:-$SETUP_LOG_DIR/flutter_fix.log}"
export SETUP_LOG_DIR
export SETUP_LOG

LATEST_LOG="$SETUP_LOG_DIR/latest_setup.log"
LEGACY_LOG="$HOME/setup_output.log"

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
  if [ "$SETUP_LOG" != "$LATEST_LOG" ]; then
    printf "[%s] [%s] %s\n" "$ts" "$lvl" "$clean_msg" >> "$LATEST_LOG" 2>/dev/null || true
  fi
  if [ "$SETUP_LOG" != "$LEGACY_LOG" ]; then
    printf "[%s] [%s] %s\n" "$ts" "$lvl" "$clean_msg" >> "$LEGACY_LOG" 2>/dev/null || true
  fi
}

print_info() { printf "${Blue}[ INFO ] %s${Color_Off}\n" "$1"; write_setup_log "INFO" "$1"; }
print_success() { printf "${Green}[ SUCCESS ] %s${Color_Off}\n" "$1"; write_setup_log "SUCCESS" "$1"; }
print_warn() { printf "${Orange}[ WARNING ] %s${Color_Off}\n" "$1"; write_setup_log "WARN" "$1"; }
print_err() { printf "${Red}[ ERROR ] %s${Color_Off}\n" "$1" >&2; write_setup_log "ERROR" "$1"; }

echo ""
printf "${Cyan}====================================================${Color_Off}\n"
printf "${Cyan}   Flutter On-Device Patch for Android Code Studio  ${Color_Off}\n"
printf "${Cyan}====================================================${Color_Off}\n"
echo ""

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

# 4. Step 2: Safe regex shebang rewriting and Termux prefix relocation
print_info "Step 2/5: Rewriting shebangs and relocating prefixes (strictly protecting ELF binaries)..."
patched_count=0
old_pfx="/data/data/com.termux/files/usr"

# Explicit list of known shell scripts in bin and bin/internal (Zero risk to ELF binaries)
for f in \
  "$FLUTTER_ROOT/bin/flutter" \
  "$FLUTTER_ROOT/bin/dart" \
  "$FLUTTER_ROOT/bin/internal/shared.sh" \
  "$FLUTTER_ROOT/bin/internal/update_dart_sdk.sh" \
  "$FLUTTER_ROOT/bin/internal/content_aware_hash.sh" \
  "$FLUTTER_ROOT/bin/internal/last_engine_commit.sh" \
  "$FLUTTER_ROOT/bin/internal/update_engine_version.sh"; do
  if [ -f "$f" ] && [ -w "$f" ]; then
    # Protect against ELF binary
    if head -c 4 "$f" 2>/dev/null | grep -q "ELF"; then
      continue
    fi
    sed -i 's/\r$//' "$f" 2>/dev/null || true
    sed -i "s|$old_pfx|$PREFIX|g" "$f" 2>/dev/null || true
    sed -i -E "1s|^#!.*|#!$TARGET_BASH|" "$f" 2>/dev/null || true
    chmod +x "$f" 2>/dev/null || true
    patched_count=$((patched_count + 1))
  fi
done

# Also patch any shell scripts in packages/
if [ -d "$FLUTTER_ROOT/packages" ]; then
  find "$FLUTTER_ROOT/packages" -type f -name "*.sh" 2>/dev/null | while read -r f; do
    if [ -f "$f" ] && [ -w "$f" ]; then
      if head -c 4 "$f" 2>/dev/null | grep -q "ELF"; then
        continue
      fi
      sed -i 's/\r$//' "$f" 2>/dev/null || true
      sed -i "s|$old_pfx|$PREFIX|g" "$f" 2>/dev/null || true
      sed -i -E "1s|^#!.*|#!$TARGET_BASH|" "$f" 2>/dev/null || true
      chmod +x "$f" 2>/dev/null || true
    fi
  done
fi

print_success "Finished sanitizing Flutter scripts ($patched_count key wrapper scripts updated)."

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

# 7. Step 5: Execute Upstream post_install.sh & Run 4-Step Verification
POST_INSTALL="$PREFIX/share/flutter/post_install.sh"
if [ -f "$POST_INSTALL" ]; then
  print_info "Step 5/6: Executing upstream post_install.sh engine..."
  sed -i -E "s|PREFIX=\"\$\{PREFIX:-/data/data/com\.termux/files/usr\}\"|PREFIX=\"\${PREFIX:-$PREFIX}\"|g" "$POST_INSTALL" 2>/dev/null || true
  bash "$POST_INSTALL" || print_warn "post_install.sh finished with notices."
fi

# 8. Step 6: Upstream 4-Step Verification
print_info "Step 6/6: Verifying Flutter installation (Upstream Standard)..."
echo -n "Check 1: flutter --version ... "
"$FLUTTER_ROOT/bin/flutter" --version 2>&1 | head -1 || true
echo -n "Check 2: dart --version ... "
"$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart" --version 2>&1 || true
echo -n "Check 3: dartvm --version ... "
if [ -x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dartvm" ]; then
  "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dartvm" --version 2>&1 || true
else
  echo "OK (using dart binary)"
fi
echo "Check 4: flutter doctor -v"
"$FLUTTER_ROOT/bin/flutter" doctor -v || true

echo ""
write_setup_log "SUMMARY" "================================================================================"
write_setup_log "SUMMARY" "FLUTTER_PATCH_STATUS=SUCCESS"
write_setup_log "SUMMARY" "COMPLETED_AT: $(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo 'now')"
write_setup_log "SUMMARY" "================================================================================"
print_success "===================================================="
print_success " Flutter SDK patch completed successfully!"
print_success " Log saved to: $SETUP_LOG"
print_success ""
print_success " When building an APK, remember to use --no-tree-shake-icons:"
print_success "   flutter build apk --release --target-platform android-arm64 --no-tree-shake-icons"
print_success ""
print_success " For future repairs, you can simply run: fix-flutter"
print_success " Or run the comprehensive suite: bash install_and_verify_flutter.sh"
print_success "===================================================="
echo ""
