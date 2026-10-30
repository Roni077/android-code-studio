#!/data/data/com.tom.rv2ide/files/usr/bin/bash

# Modified by Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
# ++ ndk support 


Color_Off='\033[0m'
Red='\033[0;31m'
Green='\033[0;32m'
Blue='\033[0;34m'
Orange="\e[38;5;208m"

yes='^[Yy][Ee]?[Ss]?$'

# Defualt values
arch=$(uname -m)
install_dir=$HOME
sdkver_org=35.0.2
with_cmdline=true
assume_yes=false
manifest="https://raw.githubusercontent.com/Roni077/acs-build-system/main/acs-manifest.json"
pkgm="pkg"
pkg_curl="libcurl"
pkgs="jq tar wget"
jdk_version="17"
ndk_version="0"
ndk=false
with_flutter=false


# echo "This IDE SETUP FOR armeabi-v7a is hd=[$ndk_version] | from args=[$1, $2, $3]"

if apt update -y; then
    apt --fix-broken install -y
fi

npr() {
    local TAG="$Green[ NDK SETUP ]$Color_Off"
    echo -e "$TAG $1"
}

this_abort() {
    local TAG="$Red[ ERROR ]$Color_Off"
    echo -e "$TAG $1"
    exit 1
}

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
            
        NDK_FILENAME=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$ndk_version" -f filename)
        NDK_OUTPUTDIR="$HOME/acs/$NDK_FILENAME"
        NDK_VERSION=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$ndk_version" -f version)   
        NDK_CHECKSUM=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$ndk_version" -f sha256)
        NDK_ENCRYPTED=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$ndk_version" -f encrypted)
        
        echo
        npr "- Native Development Kit Manager - "
        npr "\t - Device abi: $ABI"
        npr "\t - Artifact name: $NDK_FILENAME"
        npr "\t - NDK version: $NDK_VERSION"
        npr "\t - NDK sha256sum: ${NDK_CHECKSUM:0:8}..."
        echo
        npr "Downloading $NDK_FILENAME..."
    
        OUTPUT_BIN="$PREFIX/bin/ndk_extractor"
        
        # Run the acs system for downloading the native kit
        if ! acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$ndk_version" --download; then
            this_abort "Failed to download Native Development Kit, Version: $NDK_VERSION ! Please try again..."
        fi
        
        local _dest="$HOME/android-sdk/ndk/$ndk_version"
        mkdir -p "$_dest"

        if [[ "$NDK_ENCRYPTED" == "false" ]] || [[ "$NDK_FILENAME" == *.tar.xz ]]; then
            npr "Extracting open NDK archive ($NDK_FILENAME)..."
            tar -xJf "$NDK_OUTPUTDIR" -C "$_dest" --strip-components=1 || this_abort "Failed to extract NDK tarball"
            touch "$OUTPUT_BIN"
            chmod +x "$OUTPUT_BIN"
            npr "Direct extraction succeeded"
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
    local ndkUrl="https://github.com/Mohammed-Baqer-null/AndroidIDE-Rv2-ndk/releases/download/v27.1.12297006/android-ndk-r27b-aarch64.zip"
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
        if echo '#!/bin/sh
DIR=$(cd "$(dirname "$0")" && pwd)
/data/data/com.tom.rv2ide/files/usr/bin/bash "$DIR/build/ndk-build" "$@"' > "$HOME/android-sdk/ndk/$ndkVersion/ndk-build"; then
            chmod +x "$HOME/android-sdk/ndk/$ndkVersion/ndk-build"
            npr "Successfully applied 2 patches"
        fi
    else
        npr "NDK:$ndk_version Already installed! SETUP Skipped"
    fi
fi
}

print_info() {
  # shellcheck disable=SC2059
  printf "${Blue}$1$Color_Off\n"
}

print_err() {
  # shellcheck disable=SC2059
  printf "${Red}$1$Color_Off\n"
}

print_warn() {
  # shellcheck disable=SC2059
  printf "${Orange}$1$Color_Off\n"
}

print_success() {
  # shellcheck disable=SC2059
  printf "${Green}$1$Color_Off\n"
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
  echo "${0} -s 34.0.4 -c -j 17"
  echo "This will install Android SDK 34.0.4 with command line tools and JDK 17."
  echo ""
  echo "Options :"
  echo "-i   Set the installation directory. Defaults to \$HOME."
  echo "-s   Android SDK version to download."
  echo "-c   Download Android SDK with command line tools."
  echo "-m   Manifest file URL. Defaults to 'manifest.json' in 'androidide-tools' GitHub repository."
  echo "-j   OpenJDK version to install. Values can be '17' or '21'"
  echo "-g   Install package: 'git'."
  echo "-o   Install package: 'git'."
  echo "-y   Assume \"yes\" as answer to all prompts and run non-interactively."
  echo ""
  echo "For testing purposes:"
  echo "-a   CPU architecture. Extracted using 'uname -m' by default."
  echo "-p   Package manager. Defaults to 'pkg'."
  echo "-l   Name of curl package that will be installed before starting installation process. Defaults to 'libcurl'."
  echo ""
  echo "-h   Prints this message."
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
    curl -L -o "$dest" "$url" --http1.1
    print_success "$name has been downloaded."
    echo ""
  fi

  if [ ! -f "$dest" ]; then
    print_err "The downloaded file $name does not exist. Cannot proceed..."
    exit 1
  fi

  # Extract the downloaded archive
  if [[ "$extract_with" == "xz" ]]; then
    print_info "Extracting downloaded archive with xz..."
    tar xvJf "$dest" && print_info "Extracted successfully"
  else
    print_info "Extracting downloaded archive with unzip..."
    unzip "$dest" && print_info "Extracted successfully"
  fi
  echo ""

  # Delete the downloaded file
  rm -vf "$dest"

  # cd into the previous working directory
  cd -
}

download_comp() {
  nm=$1
  jq_query=$2
  mdir=$3
  dname=$4

  # Extract the Android SDK URL
  print_info "Extracting URL for $nm from manifest..."
  url=$(jq -r "${jq_query}" "$downloaded_manifest")
  print_success "Found URL: $url"
  echo ""

  # Download and extract the Android SDK build tools
  download_and_extract "$nm" "$url" "$mdir" "$mdir/$dname.tar.xz" "xz"
}

# setup_ndk
# setup_ndk_extractor
# exit 0
## NOTE!
## When adding more installation configuration arguments,
# add them in com.itsaky.andridide.models.IdeSetupArgument as well
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

if [ "$arch" = "armv7l" ]; then
  arch="arm"
fi

# 64-bit CPU in 32-bit mode
if [ "$arch" = "armv8l" ]; then
  arch="arm"
fi

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
echo "CPU architecture          : ${arch}"
echo "------------------------------------------"

if ! is_yes "Confirm configuration"; then
  print_err "Aborting..."
  exit 1
fi

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

$pkgm update
if [ "$assume_yes" == "true" ]; then
  $pkgm upgrade -y
else
  $pkgm upgrade
fi

# Install required packages
print_info "Installing required packages.."
# shellcheck disable=SC2086
install_packages $pkgs && print_success "Packages installed"
echo ""

# Download the manifest.json file
print_info "Downloading manifest file..."
downloaded_manifest="$install_dir/manifest.json"
curl -L -o "$downloaded_manifest" "$manifest" && print_success "Manifest file downloaded"
echo ""

# Install the Android SDK
download_comp "Android SDK" ".android_sdk" "$install_dir" "android-sdk"

#sdkmanager "build-tools;35.0.1" "build-tools;36.0.0"

# Install build tools
download_comp "Android SDK Build Tools" ".build_tools | .${arch} | .${sdk_version}" "$install_dir/android-sdk" "android-sdk-build-tools"

# Install platform tools
download_comp "Android SDK Platform Tools" ".platform_tools | .${arch} | .${sdk_version}" "$install_dir/android-sdk" "android-sdk-platform-tools"

if [ "$with_cmdline" = true ]; then
  # Install the Command Line tools
  download_comp "Command-line tools" ".cmdline_tools" "$install_dir/android-sdk" "cmdline-tools"
fi

# Install JDK
print_info "Installing package: 'openjdk-$jdk_version'"
install_packages "openjdk-$jdk_version" && print_info "JDK $jdk_version has been installed."

jdk_dir="$JAVA_HOME"

print_info "NDK $ndk_version"

print_info "Updating ide-environment.properties..."
print_info "JAVA_HOME=$jdk_dir"
echo ""
props_dir="$SYSROOT/etc"
props="$props_dir/ide-environment.properties"

if [ ! -d "$props_dir" ]; then
  mkdir -p "$props_dir"
fi

if [ ! -e "$props" ]; then
  printf "JAVA_HOME=%s" "$jdk_dir" >"$props" && print_success "Properties file updated successfully!"
else
  if is_yes "$props file already exists. Would you like to overwrite it?"; then
    printf "JAVA_HOME=%s" "$jdk_dir" >"$props" && print_success "Properties file updated successfully!"
  else
    print_err "Manually edit $SYSROOT/etc/ide-environment.properties file and set JAVA_HOME and ANDROID_SDK_ROOT."
  fi
fi

setup_ndk

rm -vf "$downloaded_manifest"
print_success "Downloads completed. You are ready to go!"

if [ "$with_flutter" = "true" ]; then
  print_info "Initiating Flutter SDK setup..."
  dir="$(cd "$(dirname "${BASH_SOURCE[0]:-${0:-.}}")" 2>/dev/null && pwd || echo ".")"
  flutter_args=("-y")
  if [ "$ndk" = "true" ]; then
    flutter_args+=("-wn")
  fi
  if [ -f "$dir/fluttersetup.sh" ]; then
    bash "$dir/fluttersetup.sh" "${flutter_args[@]}"
  elif [ -f "$PREFIX/bin/fluttersetup.sh" ]; then
    bash "$PREFIX/bin/fluttersetup.sh" "${flutter_args[@]}"
  elif [ -f "$PREFIX/bin/fluttersetup" ]; then
    bash "$PREFIX/bin/fluttersetup" "${flutter_args[@]}"
  else
    print_err "fluttersetup.sh not found in $dir or $PREFIX/bin!"
  fi
fi
