#!/data/data/com.tom.rv2ide/files/usr/bin/bash

# Modified by Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
# ++ ndk support 
# Unified setup debug logging & resilient manifest URL resolution

Color_Off='\033[0m'
Red='\033[0;31m'
Green='\033[0;32m'
Blue='\033[0;34m'
Orange="\e[38;5;208m"

yes='^[Yy][Ee]?[Ss]?$'

# Default values
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
  elif [ -d "/data/data/com.termux/files/home" ]; then
    HOME="/data/data/com.termux/files/home"
  else
    HOME="$(dirname "$PREFIX")/home"
  fi
fi
SYSROOT="${SYSROOT:-$PREFIX}"
arch=$(uname -m)
install_dir=$HOME
sdkver_org=35.0.2
with_cmdline=true
assume_yes=false
verbose=false
clean_log=false
manifest="https://raw.githubusercontent.com/Roni077/acs-build-system/main/acs-manifest.json"
pkgm="pkg"
pkg_curl="libcurl"
pkgs="jq tar wget termux-exec which unzip"
jdk_version="17"
ndk_version="0"
ndk=false
with_flutter=false

# Dedicated setup log folder in home directory ($HOME/setup_logs)
SETUP_LOG_DIR="${SETUP_LOG_DIR:-$HOME/setup_logs}"
mkdir -p "$SETUP_LOG_DIR" 2>/dev/null || true
SETUP_LOG="${SETUP_LOG:-$SETUP_LOG_DIR/idesetup.log}"
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

print_info() {
  # shellcheck disable=SC2059
  printf "${Blue}%s${Color_Off}\n" "$1"
  write_setup_log "INFO" "$1"
}

print_err() {
  # shellcheck disable=SC2059
  printf "${Red}%s${Color_Off}\n" "$1" >&2
  write_setup_log "ERROR" "$1"
}

print_warn() {
  # shellcheck disable=SC2059
  printf "${Orange}%s${Color_Off}\n" "$1"
  write_setup_log "WARN" "$1"
}

print_success() {
  # shellcheck disable=SC2059
  printf "${Green}%s${Color_Off}\n" "$1"
  write_setup_log "SUCCESS" "$1"
}

print_debug() {
  write_setup_log "DEBUG" "$1"
  if [ "$verbose" = "true" ]; then
    printf "${Color_Off}[DEBUG] %s\n" "$1"
  fi
}

npr() {
  local TAG="$Green[ NDK SETUP ]$Color_Off"
  echo -e "$TAG $1"
  write_setup_log "NDK" "$1"
}

this_abort() {
  local TAG="$Red[ ERROR ]$Color_Off"
  echo -e "$TAG $1" >&2
  write_setup_log "FATAL" "$1"
  write_setup_log "SUMMARY" "SETUP_STATUS=FAILED (Error: $1)"
  exit 1
}

# Initial APT update & fix-broken check with logging
if apt update -y >> "$SETUP_LOG" 2>&1; then
  apt --fix-broken install -y >> "$SETUP_LOG" 2>&1 || true
fi

grep_prop() {
  local REGEX="s/^$1=//p"
  shift
  local FILES=$@
  [ -z "$FILES" ] && FILES='/system/build.prop'
  cat $FILES 2>/dev/null | dos2unix | sed -n "$REGEX" | head -n 1
}

grep_get_prop() {
  local result=$(grep_prop $@)
  if [ -z "$result" ]; then
    # Fallback to getprop
    getprop "$1"
  else
    echo $result
  fi
}

api_level_arch_detect() {
  API=$(grep_get_prop ro.build.version.sdk)
  ABI=$(grep_get_prop ro.product.cpu.abi)
  if [ "$ABI" = "arm64-v8a" ]; then
    ARCH=arm64
    ABI32=armeabi-v7a
    IS64BIT=true
  elif [ "$ABI" = "x86_64" ]; then
    ARCH=x64
    ABI32=x86
    IS64BIT=true
  elif [ "$ABI" = "armeabi-v7a" ]; then
    ARCH=arm
    ABI32=armeabi-v7a
    IS64BIT=false
  elif [ "$ABI" = "x86" ]; then
    ARCH=x86
    ABI32=x86
    IS64BIT=false
  elif [ "$ABI" = "riscv64" ]; then
    ARCH=riscv64
    ABI32=riscv32
    IS64BIT=true
  fi
}
    
ensure_ndk() {
  local ndkVersion="$ndk_version"
  if [ -f "$HOME/android-sdk/ndk/$ndkVersion/ndk-build" ]; then
    return 0
  fi
  return 1
}

setup_ndk_extractor() {
  api_level_arch_detect
    
  if [[ "$ndk_version" != "0" ]]; then
    NDK_MANIFEST_FILE_URL="https://raw.githubusercontent.com/Roni077/acs-build-system/refs/heads/main/acs-manifest.json"
    PACKAGE_ID="android-native-kit"
            
    NDK_FILENAME=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$ndk_version" -f filename 2>/dev/null || echo "")
    NDK_OUTPUTDIR="$HOME/acs/$NDK_FILENAME"
    NDK_VERSION=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$ndk_version" -f version 2>/dev/null || echo "$ndk_version")   
    NDK_CHECKSUM=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$ndk_version" -f sha256 2>/dev/null || echo "")
    NDK_ENCRYPTED=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$ndk_version" -f encrypted 2>/dev/null || echo "false")
        
    echo
    npr "- Native Development Kit Manager - "
    npr "\t - Device abi: $ABI"
    npr "\t - Artifact name: $NDK_FILENAME"
    npr "\t - NDK version: $NDK_VERSION"
    npr "\t - NDK sha256sum: ${NDK_CHECKSUM:0:8}..."
    echo
    npr "Downloading $NDK_FILENAME..."
    write_setup_log "NDK" "Downloading NDK $NDK_VERSION for $ABI (checksum: $NDK_CHECKSUM)"
    
    OUTPUT_BIN="$PREFIX/bin/ndk_extractor"
        
    # Run the acs system for downloading the native kit
    if ! acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$ndk_version" --download; then
      this_abort "Failed to download Native Development Kit, Version: $NDK_VERSION ! Please try again..."
    fi
        
    local _dest="$HOME/android-sdk/ndk/$ndk_version"
    mkdir -p "$_dest"

    if [[ "$NDK_ENCRYPTED" == "false" ]] || [[ "$NDK_FILENAME" == *.tar.xz ]]; then
      npr "Extracting open NDK archive ($NDK_FILENAME)..."
      write_setup_log "NDK" "Extracting $NDK_OUTPUTDIR to $_dest"
      local tar_err
      if ! tar_err=$(tar -xJf "$NDK_OUTPUTDIR" -C "$_dest" --strip-components=1 2>&1); then
        write_setup_log "ERROR" "NDK extraction error: $tar_err"
        this_abort "Failed to extract NDK tarball"
      fi
      rm -f "$NDK_OUTPUTDIR"
      touch "$OUTPUT_BIN"
      chmod +x "$OUTPUT_BIN"
      npr "Direct extraction succeeded and archive cleaned"
      write_setup_log "NDK" "NDK extracted successfully to $_dest and archive cleaned"
    else
      # Rename the native kit bundle
      mv "$NDK_OUTPUTDIR" "$OUTPUT_BIN" || this_abort "Something went wrong while renaming $NDK_FILENAME"
      npr "Setting up token chain..."
      if chmod +x $OUTPUT_BIN; then
        npr "Splendid. every operation succeeded"
      fi
    fi
  else
    npr "NDK Installation skipped."
  fi
}

ndk_extractor() {
  local _arch="$1"
  local _token="795203ab38cefaa8663642d85fdb22bb8c7fc0e10d079da3209adec3ce39e0e3"
  local _dest="$HOME/android-sdk/ndk"
  [ ! -d "$_dest" ] && mkdir -p "$_dest"
  if $PREFIX/bin/ndk_extractor --extract "$_token" "$_arch" "$_dest" --decompress; then
    return 0
  fi
  return 1
}

setup_ndk() {
  npr "Running environmental check..."
  [ ! -f $PREFIX/bin/ndk_extractor ] && setup_ndk_extractor
  local ndkVersion="${ndk_version:-28.2.13676358}"
  [ "$ndkVersion" = "0" ] && ndkVersion="28.2.13676358"
  local not_supported=("x86" "riscv64")
  local ndk_file_structure=""
  api_level_arch_detect
    
  if [[ "$ndk_version" != "0" ]]; then
    npr "Checking device architecture..."
    for isSupportedArch in ${not_supported[@]}; do
      if [[ "$ARCH" == "$isSupportedArch" ]]; then
        this_abort "Unsupported architecture $ARCH ! ❌"
      fi
    done
    npr "Device architecture: $ARCH ✅"
    if ! ensure_ndk; then
      if [ -f "$HOME/android-sdk/ndk/$ndkVersion/build/ndk-build" ]; then
        npr "NDK binaries extracted successfully."
      elif ndk_extractor $ARCH; then
        npr "Successfully extracted"
      else
        this_abort "Failed to extract NDK"
      fi
      npr "Applying ndk patches..."
      cat << EOF > "$HOME/android-sdk/ndk/$ndkVersion/ndk-build"
#!$PREFIX/bin/sh
DIR="\$(cd "\$(dirname "\$0")" && pwd)"
exec "$PREFIX/bin/bash" "\$DIR/build/ndk-build" "\$@"
EOF
      if [ $? -eq 0 ]; then
        chmod +x "$HOME/android-sdk/ndk/$ndkVersion/ndk-build"
        npr "Successfully applied 2 patches"
      fi
    else
      npr "NDK:$ndk_version Already installed! SETUP Skipped"
    fi
  fi
}

is_yes() {
  msg=$1
  printf "%s ([y]es/[n]o): " "$msg"
  if [ "$assume_yes" == "true" ]; then
    ans="y"
    echo $ans
  else
    read -r ans
  fi
  if [[ "$ans" =~ $yes ]]; then
    return 0
  fi
  return 1
}

check_arg_value() {
  option_name="$1"
  arg_value="$2"
  if [[ -z "$arg_value" ]]; then
    print_err "No value provided for $option_name!" >&2
    exit 1
  fi
}

check_command_exists() {
  if command -v "$1" &>/dev/null; then
    return
  else
    print_err "Command '$1' not found!"
    exit 1
  fi
}

# shellcheck disable=SC2068
install_packages() {
  if [ "$assume_yes" == "true" ]; then
    $pkgm install $@ -y
  else
    $pkgm install $@
  fi
}

print_help() {
  echo "AndroidIDE build tools installer"
  echo "This script helps you easily install build tools in AndroidIDE."
  echo ""
  echo "Usage:"
  echo "${0} -s 35.0.2 -c -j 17"
  echo "This will install Android SDK 35.0.2 with command line tools and JDK 17."
  echo ""
  echo "Options :"
  echo "-i   Set the installation directory. Defaults to \$HOME."
  echo "-s   Android SDK version to download."
  echo "-c   Download Android SDK with command line tools."
  echo "-m   Manifest file URL. Defaults to 'manifest.json' in 'androidide-tools' GitHub repository."
  echo "-j   OpenJDK version to install. Values can be '17' or '21'"
  echo "-g   Install package: 'git'."
  echo "-o   Install package: 'openssh'."
  echo "-y   Assume \"yes\" as answer to all prompts and run non-interactively."
  echo "-v   Enable verbose debugging output on terminal."
  echo "--clean-log Purge existing setup_output.log before starting."
  echo ""
  echo "For testing purposes:"
  echo "-a   CPU architecture. Extracted using 'uname -m' by default."
  echo "-p   Package manager. Defaults to 'pkg'."
  echo "-l   Name of curl package that will be installed before starting installation process. Defaults to 'libcurl'."
  echo ""
  echo "-h   Prints this message."
}

resolve_manifest_url() {
  local comp_type="$1"
  local resolved=""

  case "$comp_type" in
    "android-sdk")
      # Tier 1: .android_sdk
      resolved=$(jq -r '.android_sdk // empty' "$downloaded_manifest" 2>/dev/null)
      # Tier 2: .packages[] matching android-sdk-core
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved=$(jq -r --arg a "$pkg_arch" '.packages[] | select(.id == "android-sdk-core" and .architecture == $a) | .url' "$downloaded_manifest" 2>/dev/null | head -n 1)
      fi
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved=$(jq -r '.packages[] | select(.id == "android-sdk-core") | .url' "$downloaded_manifest" 2>/dev/null | head -n 1)
      fi
      # Tier 3: Hardcoded fallback
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved="https://github.com/Roni077/acs-build-system/releases/download/sdk/android-sdk.tar.xz"
        print_warn "Using reliable fallback URL for Android SDK: $resolved"
      fi
      ;;

    "build-tools")
      # Tier 1: exact version in .build_tools[manifest_arch][sdk_version]
      resolved=$(jq -r --arg a "$manifest_arch" --arg v "$sdk_version" '.build_tools[$a][$v] // empty' "$downloaded_manifest" 2>/dev/null)
      # Tier 2: exact version in .packages[]
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved=$(jq -r --arg a "$pkg_arch" --arg ma "$manifest_arch" --arg v "$sdkver_org" \
          '.packages[] | select(.id == "android-build-tools" and (.architecture == $a or .architecture == $ma) and .version == $v) | .url' \
          "$downloaded_manifest" 2>/dev/null | head -n 1)
      fi
      # Tier 3: any version matching architecture in .packages[]
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved=$(jq -r --arg a "$pkg_arch" --arg ma "$manifest_arch" \
          '.packages[] | select(.id == "android-build-tools" and (.architecture == $a or .architecture == $ma)) | .url' \
          "$downloaded_manifest" 2>/dev/null | head -n 1)
      fi
      # Tier 4: latest version under .build_tools[manifest_arch]
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved=$(jq -r --arg a "$manifest_arch" '.build_tools[$a] | to_entries | last | .value // empty' "$downloaded_manifest" 2>/dev/null)
      fi
      # Tier 5: Reliable hardcoded release URLs
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        case "$manifest_arch" in
          arm)
            resolved="https://github.com/Roni077/acs-build-system/releases/download/v35.0.2/build-tools-35.0.2-arm.tar.xz"
            ;;
          x86_64)
            resolved="https://github.com/Roni077/acs-build-system/releases/download/v35.0.2/build-tools-35.0.2-x86_64.tar.xz"
            ;;
          *)
            resolved="https://github.com/Roni077/acs-build-system/releases/download/v35.0.2/build-tools-35.0.2-aarch64.tar.xz"
            ;;
        esac
        print_warn "Using reliable fallback URL for Android SDK Build Tools: $resolved"
      fi
      ;;

    "platform-tools")
      # Tier 1: exact version in .platform_tools[manifest_arch][sdk_version]
      resolved=$(jq -r --arg a "$manifest_arch" --arg v "$sdk_version" '.platform_tools[$a][$v] // empty' "$downloaded_manifest" 2>/dev/null)
      # Tier 2: exact version in .packages[]
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved=$(jq -r --arg a "$pkg_arch" --arg ma "$manifest_arch" --arg v "$sdkver_org" \
          '.packages[] | select(.id == "android-platform-tools" and (.architecture == $a or .architecture == $ma) and .version == $v) | .url' \
          "$downloaded_manifest" 2>/dev/null | head -n 1)
      fi
      # Tier 3: any version matching architecture in .packages[]
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved=$(jq -r --arg a "$pkg_arch" --arg ma "$manifest_arch" \
          '.packages[] | select(.id == "android-platform-tools" and (.architecture == $a or .architecture == $ma)) | .url' \
          "$downloaded_manifest" 2>/dev/null | head -n 1)
      fi
      # Tier 4: latest version under .platform_tools[manifest_arch]
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved=$(jq -r --arg a "$manifest_arch" '.platform_tools[$a] | to_entries | last | .value // empty' "$downloaded_manifest" 2>/dev/null)
      fi
      # Tier 5: Reliable hardcoded release URLs
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        case "$manifest_arch" in
          arm)
            resolved="https://github.com/Roni077/acs-build-system/releases/download/v35.0.2/platform-tools-35.0.2-arm.tar.xz"
            ;;
          x86_64)
            resolved="https://github.com/Roni077/acs-build-system/releases/download/v35.0.2/platform-tools-35.0.2-x86_64.tar.xz"
            ;;
          *)
            resolved="https://github.com/Roni077/acs-build-system/releases/download/v35.0.2/platform-tools-35.0.2-aarch64.tar.xz"
            ;;
        esac
        print_warn "Using reliable fallback URL for Android SDK Platform Tools: $resolved"
      fi
      ;;

    "cmdline-tools")
      # Tier 1: .cmdline_tools
      resolved=$(jq -r '.cmdline_tools // empty' "$downloaded_manifest" 2>/dev/null)
      # Tier 2: .packages[] matching cmdline-tools
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved=$(jq -r --arg a "$pkg_arch" '.packages[] | select(.id == "cmdline-tools" and .architecture == $a) | .url' "$downloaded_manifest" 2>/dev/null | head -n 1)
      fi
      if [ -z "$resolved" ] || [ "$resolved" = "null" ]; then
        resolved=$(jq -r '.packages[] | select(.id == "cmdline-tools") | .url' "$downloaded_manifest" 2>/dev/null | head -n 1)
      fi
      ;;

    *)
      # Generic query fallback
      resolved=$(jq -r "$comp_type" "$downloaded_manifest" 2>/dev/null)
      ;;
  esac

  echo "$resolved"
}

download_and_extract() {
  # Display name to use in print messages
  name=$1

  # URL to download from
  url=$2

  # Directory in which the downloaded archive will be extracted
  dir=$3

  # Destination path for downloading the file
  dest=$4
  
  if [ $# -ge 5 ]; then
    extract_with=$5
  else
    extract_with="xz"
  fi

  if [ -z "$url" ] || [ "$url" = "null" ]; then
    print_err "Cannot proceed: invalid or empty download URL for $name ($url)."
    this_abort "Invalid download URL for $name."
  fi

  if [ ! -d "$dir" ]; then
    mkdir -p "$dir"
  fi

  cd "$dir"

  do_download=true
  if [ -f "$dest" ]; then
    name=$(basename "$dest")
    print_info "File ${name} already exists."
    if is_yes "Do you want to skip the download process?"; then
      do_download=false
    fi
    echo ""
  fi

  if [ "$do_download" = "true" ]; then
    print_info "Downloading $name..."
    write_setup_log "DOWNLOAD" "Starting download: $name from $url -> $dest"
    local curl_res=0
    if [ "$verbose" = "true" ]; then
      curl -fL -o "$dest" "$url" --http1.1 2>&1 | tee -a "$SETUP_LOG" || curl_res=$?
    else
      curl -fL -o "$dest" "$url" --http1.1 >> "$SETUP_LOG" 2>&1 || curl_res=$?
    fi
    if [ $curl_res -ne 0 ]; then
      print_err "Download failed for $name (curl exit code: $curl_res)."
      this_abort "Failed to download $name from $url"
    fi
    print_success "$name has been downloaded."
    echo ""
  fi

  if [ ! -f "$dest" ] || [ ! -s "$dest" ]; then
    rm -f "$dest"
    print_err "The downloaded file $name does not exist or is empty. Cannot proceed..."
    this_abort "Downloaded file $name is missing or empty."
  fi

  # Extract the downloaded archive
  if [[ "$extract_with" == "xz" ]]; then
    print_info "Extracting downloaded archive with xz..."
    local tar_res=0
    if [ "$verbose" = "true" ]; then
      write_setup_log "EXTRACT" "Extracting $dest using tar xvJf in $dir"
      tar xvJf "$dest" 2>&1 | tee -a "$SETUP_LOG" || tar_res=$?
    else
      write_setup_log "EXTRACT" "Extracting $dest using tar xJf in $dir"
      tar xJf "$dest" >> "$SETUP_LOG" 2>&1 || tar_res=$?
    fi
    if [ $tar_res -eq 0 ]; then
      print_info "Extracted successfully"
      rm -f "$dest" >> "$SETUP_LOG" 2>&1 || rm -f "$dest"
    else
      rm -f "$dest"
      print_err "Failed to extract archive $name (tar exit code: $tar_res)."
      this_abort "Failed to extract archive $name."
    fi
  else
    print_info "Extracting downloaded archive with unzip..."
    local zip_res=0
    if [ "$verbose" = "true" ]; then
      write_setup_log "EXTRACT" "Extracting $dest using unzip in $dir"
      unzip "$dest" 2>&1 | tee -a "$SETUP_LOG" || zip_res=$?
    else
      write_setup_log "EXTRACT" "Extracting $dest using unzip -q in $dir"
      unzip -q "$dest" >> "$SETUP_LOG" 2>&1 || zip_res=$?
    fi
    if [ $zip_res -eq 0 ]; then
      print_info "Extracted successfully"
      rm -f "$dest" >> "$SETUP_LOG" 2>&1 || rm -f "$dest"
    else
      rm -f "$dest"
      print_err "Failed to extract archive $name (unzip exit code: $zip_res)."
      this_abort "Failed to extract archive $name."
    fi
  fi
  echo ""

  # cd into the previous working directory
  cd - >/dev/null 2>&1 || true
}

download_comp() {
  local nm="$1"
  local comp_key="$2"
  local mdir="$3"
  local dname="$4"

  # Extract the Android SDK URL
  print_info "Extracting URL for $nm from manifest..."
  local url
  url=$(resolve_manifest_url "$comp_key")

  if [ -z "$url" ] || [ "$url" = "null" ]; then
    print_err "Could not resolve valid URL for $nm from manifest!"
    this_abort "Missing download URL for required component: $nm"
  fi

  print_success "Found URL: $url"
  echo ""

  # Download and extract the component archive
  download_and_extract "$nm" "$url" "$mdir" "$mdir/$dname.tar.xz" "xz"
}

install_platform_sdk() {
  local target_api="${1:-36}"
  local pdir="$install_dir/android-sdk/platforms"
  mkdir -p "$pdir"

  if [ -d "$pdir/android-$target_api" ]; then
    print_info "Android SDK Platform $target_api is already installed."
    return 0
  fi

  print_info "Installing Android SDK Platform $target_api..."
  local plat_url=""
  # Tier 1: Check downloaded manifest
  if [ -f "$downloaded_manifest" ]; then
    plat_url=$(jq -r --arg a "$target_api" '.platforms[$a] // empty' "$downloaded_manifest" 2>/dev/null)
  fi

  # Tier 2: Official Google AOSP repository fallback
  if [ -z "$plat_url" ] || [ "$plat_url" = "null" ]; then
    case "$target_api" in
      36) plat_url="https://dl.google.com/android/repository/platform-36_r01.zip" ;;
      35) plat_url="https://dl.google.com/android/repository/platform-35_r01.zip" ;;
      34) plat_url="https://dl.google.com/android/repository/platform-34-ext7_r02.zip" ;;
      *) plat_url="" ;;
    esac
  fi

  local dest_zip="$pdir/platform-${target_api}.zip"
  local dl_ok=false

  if [ -n "$plat_url" ]; then
    print_info "Downloading Platform $target_api from repository..."
    write_setup_log "DOWNLOAD" "Downloading Android SDK Platform $target_api from $plat_url -> $dest_zip"
    local curl_res=0
    if [ "$verbose" = "true" ]; then
      curl -fL -o "$dest_zip" "$plat_url" --http1.1 2>&1 | tee -a "$SETUP_LOG" || curl_res=$?
    else
      curl -fL -o "$dest_zip" "$plat_url" --http1.1 >> "$SETUP_LOG" 2>&1 || curl_res=$?
    fi

    if [ $curl_res -eq 0 ] && [ -s "$dest_zip" ]; then
      print_info "Extracting Android SDK Platform $target_api with unzip..."
      write_setup_log "EXTRACT" "Extracting $dest_zip into $pdir"
      local unzip_res=0
      if [ "$verbose" = "true" ]; then
        unzip -q "$dest_zip" -d "$pdir" 2>&1 | tee -a "$SETUP_LOG" || unzip_res=$?
      else
        unzip -q "$dest_zip" -d "$pdir" >> "$SETUP_LOG" 2>&1 || unzip_res=$?
      fi
      rm -f "$dest_zip" >> "$SETUP_LOG" 2>&1 || rm -f "$dest_zip"
      if [ $unzip_res -eq 0 ] && [ -d "$pdir/android-$target_api" ]; then
        print_success "Android SDK Platform $target_api installed successfully."
        dl_ok=true
      else
        print_warn "Extraction did not yield expected android-$target_api directory."
      fi
    else
      rm -f "$dest_zip"
      print_warn "Could not download Platform $target_api from $plat_url (offline or network error)."
    fi
  fi

  # Tier 3: Resilient offline fallback stub
  if [ "$dl_ok" != "true" ] && [ ! -d "$pdir/android-$target_api" ]; then
    local base_plat
    base_plat=$(find "$pdir" -maxdepth 1 -mindepth 1 -type d ! -name "android-$target_api" 2>/dev/null | head -n 1)
    if [ -n "$base_plat" ] && [ -f "$base_plat/android.jar" ]; then
      print_info "Creating compatibility Android SDK Platform $target_api stub from $(basename "$base_plat")..."
      write_setup_log "PLATFORM" "Synthesizing compatibility Platform $target_api stub from $base_plat"
      mkdir -p "$pdir/android-$target_api"
      ln -sf "$base_plat/android.jar" "$pdir/android-$target_api/android.jar"
      cat << EOF > "$pdir/android-$target_api/source.properties"
Pkg.Desc=Android SDK Platform $target_api
Pkg.UserSrc=false
Platform.Version=$target_api
Platform.CodeName=
Pkg.Revision=1
AndroidVersion.ApiLevel=$target_api
EOF
      print_success "Compatibility Android SDK Platform $target_api stub created."
    fi
  fi
  echo ""
}

# Parse command line arguments
while [ $# -gt 0 ]; do
  case $1 in
  -c | --with-cmdline-tools)
    shift
    with_cmdline=false
    ;;
  -wf | --with-flutter)
    shift
    with_flutter=true
    ;;
  -g | --with-git)
    shift
    pkgs+=" git"
    ;;
  -o | --with-openssh)
    shift
    pkgs+=" openssh"
    ;;
  -wn | --with-ndk)
    shift
    ndk=true
    ;;
  -y | --assume-yes)
    shift
    assume_yes=true
    ;;
  -i | --install-dir)
    shift
    check_arg_value "--install-dir" "${1:-}"
    install_dir="$1"
    ;;
  -m | --manifest)
    shift
    check_arg_value "--manifest" "${1:-}"
    manifest="$1"
    ;;
  -s | --sdk)
    shift
    check_arg_value "--sdk" "${1:-}"
    sdkver_org="$1"
    ;;
  -j | --jdk)
    shift
    check_arg_value "--jdk" "${1:-}"
    jdk_version="$1"
    ;;
  -n | --ndk)
    shift
    check_arg_value "--ndk" "${1:-}"
    ndk_version="$1"
    ;;
  -a | --arch)
    shift
    check_arg_value "--arch" "${1:-}"
    arch="$1"
    ;;
  -p | --package-manager)
    shift
    check_arg_value "--package-manager" "${1:-}"
    pkgm="$1"
    ;;
  -l | --curl)
    shift
    check_arg_value "--curl" "${1:-}"
    pkg_curl="$1"
    ;;
  -v | --verbose | --debug)
    shift
    verbose=true
    ;;
  --clean-log)
    shift
    clean_log=true
    ;;
  -h | --help)
    print_help
    exit 0
    ;;
  -*)
    echo "Invalid option: $1" >&2
    exit 1
    ;;
  *) break ;;
  esac
  shift
done

if [ "$clean_log" = "true" ]; then
  rm -f "$SETUP_LOG" "${SETUP_LOG}.old" 2>/dev/null || true
  print_info "Setup log cleaned ($SETUP_LOG)."
fi

# Normalize architecture strings for system, manifest and package queries
case "$arch" in
  aarch64|arm64|arm64-v8a)
    arch="arm64"
    manifest_arch="aarch64"
    pkg_arch="arm64-v8a"
    ;;
  arm|armv7l|armv8l|armeabi*|armhf)
    arch="arm"
    manifest_arch="arm"
    pkg_arch="armeabi-v7a"
    ;;
  x86_64|x64|amd64)
    arch="x64"
    manifest_arch="x86_64"
    pkg_arch="x86_64"
    ;;
  *)
    manifest_arch="$arch"
    pkg_arch="$arch"
    ;;
esac

check_command_exists "$pkgm"

if [ "$jdk_version" == "21" ]; then
  print_warn "OpenJDK 21 support in AndroidIDE is experimental. It may or may not work properly."
  print_warn "Also, OpenJDK 21 is only supported in Gradle v8.4 and newer. Older versions of Gradle will NOT work!"
  if ! is_yes "Do you still want to install OpenJDK 21?"; then
    jdk_version="17"
    print_info "OpenJDK version has been reset to '17'"
  fi
fi

if [ "$jdk_version" != "17" ] && [ "$jdk_version" != "21" ]; then
  print_err "Invalid JDK version '$jdk_version'. Value can be '17' or '21'."
  exit 1
fi

sdk_version="_${sdkver_org//'.'/'_'}"

pkgs+=" $pkg_curl"

echo "------------------------------------------"
echo "Installation directory    : ${install_dir}"
echo "SDK version               : ${sdkver_org}"
echo "JDK version               : ${jdk_version}"
echo "With command line tools   : ${with_cmdline}"
echo "Extra packages            : ${pkgs}"
echo "CPU architecture          : ${arch} (${manifest_arch})"
echo "Debug log file            : ${SETUP_LOG}"
echo "------------------------------------------"

if ! is_yes "Confirm configuration"; then
  print_err "Aborting..."
  exit 1
fi

write_setup_log "SESSION" "================================================================================"
write_setup_log "SESSION" "Android Code Studio IDE Setup Session Started"
write_setup_log "SESSION" "Date: $(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo 'unknown')"
write_setup_log "SESSION" "ARCH: $arch (manifest_arch: $manifest_arch, pkg_arch: $pkg_arch)"
write_setup_log "SESSION" "PREFIX: ${PREFIX:-/data/data/com.tom.rv2ide/files/usr}"
write_setup_log "SESSION" "HOME: ${HOME:-/data/data/com.tom.rv2ide/files/home}"
write_setup_log "SESSION" "CONFIG: InstallDir=$install_dir, SdkVersion=$sdkver_org, JdkVersion=$jdk_version, NdkVersion=$ndk_version"
write_setup_log "SESSION" "OPTIONS: WithFlutter=$with_flutter, WithCmdline=$with_cmdline, AssumeYes=$assume_yes"
write_setup_log "SESSION" "================================================================================"

if [ ! -f "$install_dir" ]; then
  print_info "Installation directory does not exist. Creating directory..."
  mkdir -p "$install_dir"
fi

if [ ! command -v "$pkgm" ] &>/dev/null; then
  print_err "'$pkgm' command not found. Try installing 'termux-tools' and 'apt'."
  exit 1
fi

# Update repositories and packages
print_info "Update packages..."

$pkgm update >> "$SETUP_LOG" 2>&1 || true
if [ "$assume_yes" == "true" ]; then
  $pkgm upgrade -y >> "$SETUP_LOG" 2>&1 || true
else
  $pkgm upgrade
fi

# Install required packages
print_info "Installing required packages.."
# shellcheck disable=SC2086
install_packages $pkgs && print_success "Packages installed"
echo ""

# Ensure resilient POSIX 'which' wrapper is available
if ! command -v which &>/dev/null; then
  write_setup_log "TOOL" "Installing lightweight which fallback script to $PREFIX/bin/which"
  cat << EOF > "$PREFIX/bin/which"
#!$PREFIX/bin/sh
all=false
ret=0
for arg in "\$@"; do
  case "\$arg" in
    -a) all=true; continue ;;
    --) continue ;;
    -*) continue ;;
  esac
  p=\$(command -v "\$arg" 2>/dev/null)
  if [ -n "\$p" ]; then
    printf "%s\n" "\$p"
    [ "\$all" != "true" ] && exit 0
  else
    ret=1
  fi
done
exit \$ret
EOF
  chmod +x "$PREFIX/bin/which" 2>/dev/null || true
fi

# Download the manifest.json file
print_info "Downloading manifest file..."
downloaded_manifest="$install_dir/manifest.json"
if curl -fL -o "$downloaded_manifest" "$manifest" >> "$SETUP_LOG" 2>&1; then
  print_success "Manifest file downloaded"
else
  print_warn "Failed to download manifest from $manifest. Proceeding with fallbacks..."
fi
echo ""

# Install the Android SDK
download_comp "Android SDK" "android-sdk" "$install_dir" "android-sdk"

# Install Android SDK Platform 36 (Primary target platform)
install_platform_sdk 36

# Install build tools
download_comp "Android SDK Build Tools" "build-tools" "$install_dir/android-sdk" "android-sdk-build-tools"

# Setup build-tools compatibility symlinks (28.0.3 and 35.0.0 -> installed version)
bt_dir="$install_dir/android-sdk/build-tools"
if [ -d "$bt_dir/35.0.2" ]; then
  [ ! -e "$bt_dir/28.0.3" ] && ln -sfn 35.0.2 "$bt_dir/28.0.3" 2>/dev/null || true
  [ ! -e "$bt_dir/35.0.0" ] && ln -sfn 35.0.2 "$bt_dir/35.0.0" 2>/dev/null || true
elif [ -d "$bt_dir" ]; then
  bt_first=$(find "$bt_dir" -maxdepth 1 -mindepth 1 -type d ! -name "28.0.3" ! -name "35.0.0" 2>/dev/null | head -n 1)
  if [ -n "$bt_first" ]; then
    [ ! -e "$bt_dir/28.0.3" ] && ln -sfn "$(basename "$bt_first")" "$bt_dir/28.0.3" 2>/dev/null || true
    [ ! -e "$bt_dir/35.0.0" ] && ln -sfn "$(basename "$bt_first")" "$bt_dir/35.0.0" 2>/dev/null || true
  fi
fi

# Install platform tools
download_comp "Android SDK Platform Tools" "platform-tools" "$install_dir/android-sdk" "android-sdk-platform-tools"

if [ "$with_cmdline" = true ]; then
  # Install the Command Line tools
  download_comp "Command-line tools" "cmdline-tools" "$install_dir/android-sdk" "cmdline-tools"
fi

# Install JDK
print_info "Installing package: 'openjdk-$jdk_version'"
install_packages "openjdk-$jdk_version" && print_info "JDK $jdk_version has been installed."

# Dynamically detect JAVA_HOME
if [ -z "$JAVA_HOME" ] || [ "$JAVA_HOME" = "/" ] || [ ! -d "$JAVA_HOME" ]; then
  if [ -d "$PREFIX/lib/jvm/java-$jdk_version-openjdk" ]; then
    JAVA_HOME="$PREFIX/lib/jvm/java-$jdk_version-openjdk"
  else
    JAVA_HOME=$(find "$PREFIX/lib/jvm" -maxdepth 1 -type d -name "java-${jdk_version}-*" 2>/dev/null | head -n 1)
    [ -z "$JAVA_HOME" ] && JAVA_HOME=$(find "$PREFIX/lib/jvm" -maxdepth 1 -type d -name 'java-*-openjdk' 2>/dev/null | sort -V | tail -1)
  fi
fi
jdk_dir="${JAVA_HOME:-$PREFIX/lib/jvm/java-$jdk_version-openjdk}"
export JAVA_HOME="$jdk_dir"

print_info "NDK $ndk_version"

print_info "Updating ide-environment.properties..."
print_info "JAVA_HOME=$jdk_dir"
print_info "ANDROID_SDK_ROOT=$install_dir/android-sdk"
echo ""
props_dir="$SYSROOT/etc"
props="$props_dir/ide-environment.properties"

if [ ! -d "$props_dir" ]; then
  mkdir -p "$props_dir"
fi

props_content="JAVA_HOME=$jdk_dir
ANDROID_SDK_ROOT=$install_dir/android-sdk
ANDROID_HOME=$install_dir/android-sdk"

if [ ! -e "$props" ]; then
  printf "%s\n" "$props_content" >"$props" && print_success "Properties file updated successfully!"
else
  if is_yes "$props file already exists. Would you like to overwrite it?"; then
    printf "%s\n" "$props_content" >"$props" && print_success "Properties file updated successfully!"
  else
    print_err "Manually edit $props file and set JAVA_HOME and ANDROID_SDK_ROOT."
  fi
fi

setup_ndk

rm -vf "$downloaded_manifest" >> "$SETUP_LOG" 2>&1 || rm -f "$downloaded_manifest"
print_success "Downloads completed. You are ready to go!"

if [ "$with_flutter" = "true" ]; then
  print_info "Initiating Flutter SDK setup..."
  dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${0:-.}}")" 2>/dev/null && pwd || echo ".")"
  flutter_args=("-y")
  [ "$verbose" = "true" ] && flutter_args+=("--verbose")
  if [ "$ndk_version" = "0" ] && [ "$ndk" != "true" ]; then
    flutter_args+=("--no-ndk")
  elif [ -n "$ndk_version" ] && [ "$ndk_version" != "0" ]; then
    flutter_args+=("--ndk-version" "$ndk_version")
  elif [ "$ndk" = "true" ]; then
    flutter_args+=("-wn")
  fi
  flutter_exit=0
  SETUP_LOG="$SETUP_LOG_DIR/fluttersetup.log"
  if [ -f "$dir/fluttersetup.sh" ]; then
    bash "$dir/fluttersetup.sh" "${flutter_args[@]}" || flutter_exit=$?
  elif [ -f "$PREFIX/bin/fluttersetup.sh" ]; then
    bash "$PREFIX/bin/fluttersetup.sh" "${flutter_args[@]}" || flutter_exit=$?
  elif [ -f "$PREFIX/bin/fluttersetup" ]; then
    bash "$PREFIX/bin/fluttersetup" "${flutter_args[@]}" || flutter_exit=$?
  else
    print_err "fluttersetup.sh not found in $dir or $PREFIX/bin!"
    flutter_exit=1
  fi
  if [ $flutter_exit -ne 0 ]; then
    this_abort "Flutter SDK setup failed with exit code $flutter_exit."
  fi
fi

write_setup_log "SUMMARY" "================================================================================"
write_setup_log "SUMMARY" "SETUP_STATUS=SUCCESS"
write_setup_log "SUMMARY" "COMPONENTS: Android SDK, Build Tools ($sdkver_org), Platform Tools ($sdkver_org), JDK $jdk_version, NDK $ndk_version"
write_setup_log "SUMMARY" "LOG_FILE: $SETUP_LOG"
write_setup_log "SUMMARY" "COMPLETED_AT: $(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo 'now')"
write_setup_log "SUMMARY" "================================================================================"

print_success "All setup operations completed successfully!"
print_success "Detailed setup log: $SETUP_LOG"
print_success "All logs saved in : $SETUP_LOG_DIR"
