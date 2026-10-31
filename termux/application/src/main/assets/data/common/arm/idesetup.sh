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
    local ndkVersion="28.2.13676358"
    if [ -f "$HOME/android-sdk/ndk/$ndkVersion/ndk-build" ]; then
        return 0
    fi
    return 1
}

download_and_extract_ndk() {
    local name="$1"
    local url="$2"
    local extract_dir="$3"
    local download_file="$4"
    local extract_method="$5"
    local max_retries=3
    local retry_count=0
    
    if [ ! -d "$extract_dir" ]; then
        mkdir -p "$extract_dir"
        if [ $? -ne 0 ]; then
            print_err "Failed to create directory: $extract_dir"
            return 1
        fi
    fi
    
    if [ -f "$download_file" ]; then
        print_info "File $(basename "$download_file") already exists."
        if is_yes "Do you want to skip the download process?"; then
            print_info "Skipping download..."
        else
            rm -f "$download_file"
            while [ $retry_count -lt $max_retries ]; do
                retry_count=$((retry_count + 1))
                print_info "Downloading $name... (Attempt $retry_count/$max_retries)"
                curl -L -o "$download_file" "$url" --http1.1
                if [ $? -eq 0 ] && [ -f "$download_file" ]; then
                    break
                else
                    print_err "Download attempt $retry_count failed"
                    rm -f "$download_file"
                    if [ $retry_count -eq $max_retries ]; then
                        print_err "Failed to download $name after $max_retries attempts"
                        return 1
                    fi
                    sleep 2
                fi
            done
        fi
    else
        while [ $retry_count -lt $max_retries ]; do
            retry_count=$((retry_count + 1))
            print_info "Downloading $name... (Attempt $retry_count/$max_retries)"
            curl -L -o "$download_file" "$url" --http1.1
            if [ $? -eq 0 ] && [ -f "$download_file" ]; then
                break
            else
                print_err "Download attempt $retry_count failed"
                rm -f "$download_file"
                if [ $retry_count -eq $max_retries ]; then
                    print_err "Failed to download $name after $max_retries attempts"
                    return 1
                fi
                sleep 2
            fi
        done
    fi
    
    if [ ! -f "$download_file" ]; then
        print_err "Downloaded file does not exist: $download_file"
        return 1
    fi
    
    cd "$extract_dir"
    if [ "$extract_method" == "unzip" ]; then
        print_info "Extracting with unzip..."
        unzip -q "$download_file"
        if [ $? -eq 0 ]; then
            print_success "Extraction completed successfully"
        else
            print_err "Extraction failed"
            cd - > /dev/null
            return 1
        fi
    else
        print_info "Extracting with tar..."
        tar xf "$download_file"
        if [ $? -eq 0 ]; then
            print_success "Extraction completed successfully"
        else
            print_err "Extraction failed"
            cd - > /dev/null
            return 1
        fi
    fi
    
    rm -f "$download_file"
    cd - > /dev/null
    
    return 0
}

#!/bin/bash

parse_ndk() {
    local arch="$1"
    local option="${2:---getfilename}" 
    local url="https://raw.githubusercontent.com/Roni077/acs-build-system/main/acs-manifest.json"

    # normalize option to lowercase
    option=$(echo "$option" | tr '[:upper:]' '[:lower:]')

    local field
    case "$option" in
        --getfilename)  field="filename" ;;
        --getversion)   field="version" ;;
        --geturl)       field="url" ;;
        --getencrypted) field="encrypted" ;;
        --getsha256)    field="sha256" ;;
        *)
            echo "Usage: parse_ndk <arch> [--getfilename|--getversion|--geturl|--getencrypted|--getsha256]" >&2
            return 1
            ;;
    esac

    curl -sL "$url" | jq -r --arg arch "$arch" --arg field "$field" \
        '.packages[] | select((.architecture==$arch or ($arch=="arm" and .architecture=="armeabi-v7a")) and (.filename | contains("native-kit") or contains("native-development-kit"))) | .[$field]' | head -n 1
}

simple_downloader() {
    local url="$1"
    local out="$2"
    if wget -q --no-check-certificate -O \
    "$out" \
    "$url"; then
        npr "Successfully downloaded $(basename $out)"
        return 0
    fi
    return 1
}
setup_ndk_extractor() {
    api_level_arch_detect
    local req_ver="${ndk_version:-28.2.13676358}"
    [ "$req_ver" = "0" ] && req_ver="28.2.13676358"

    if command -v acs >/dev/null 2>&1; then
        NDK_MANIFEST_FILE_URL="https://raw.githubusercontent.com/Roni077/acs-build-system/refs/heads/main/acs-manifest.json"
        PACKAGE_ID="android-native-kit"
            
        NDK_FILENAME=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$req_ver" -f filename)
        NDK_OUTPUTDIR="$HOME/acs/$NDK_FILENAME"
        NDK_VERSION=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$req_ver" -f version)   
        NDK_CHECKSUM=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$req_ver" -f sha256)
        NDK_ENCRYPTED=$(acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$req_ver" -f encrypted)
        
        echo
        npr "- Native Development Kit Manager - "
        npr "\t - Device abi: $ABI"
        npr "\t - Artifact name: $NDK_FILENAME"
        npr "\t - NDK version: $NDK_VERSION"
        npr "\t - NDK sha256sum: ${NDK_CHECKSUM:0:8}..."
        echo
        npr "Downloading $NDK_FILENAME..."
    
        OUTPUT_BIN="$PREFIX/bin/ndk_extractor"
        if ! acs -r $NDK_MANIFEST_FILE_URL -a "$ABI" -i "$PACKAGE_ID" -v "$req_ver" --download; then
            this_abort "Failed to download Native Development Kit, Version: $NDK_VERSION ! Please try again..."
        fi
        
        local _dest="$HOME/android-sdk/ndk/$req_ver"
        mkdir -p "$_dest"

        if [[ "$NDK_ENCRYPTED" == "false" ]] || [[ "$NDK_FILENAME" == *.tar.xz ]]; then
            npr "Extracting open NDK archive ($NDK_FILENAME)..."
            tar -xJf "$NDK_OUTPUTDIR" -C "$_dest" --strip-components=1 || this_abort "Failed to extract NDK tarball"
            rm -f "$NDK_OUTPUTDIR"
            touch "$OUTPUT_BIN"
            chmod +x "$OUTPUT_BIN"
            npr "Direct extraction succeeded and archive cleaned"
        else
            mv "$NDK_OUTPUTDIR" "$OUTPUT_BIN" || this_abort "Something went wrong while renaming $NDK_FILENAME"
            npr "Setting up token chain..."
            chmod +x $OUTPUT_BIN
        fi
        return 0
    fi
    
    NDK_FILENAME=$(parse_ndk $ABI --getfilename)   
    NDK_URL=$(parse_ndk $ABI --geturl)   
    NDK_CHECKSUM=$(parse_ndk $ABI --getsha256)   
    NDK_VERSION=$(parse_ndk $ABI --getversion)   
    NDK_ENCRYPTED=$(parse_ndk $ABI --getencrypted)   
       
    echo
    npr "- Native Development Kit Manager - "
    npr "\t - Device abi: $ABI"
    npr "\t - Artifact name: $NDK_FILENAME"
    npr "\t - NDK version: $NDK_VERSION"
    npr "\t - NDK sha256sum: ${NDK_CHECKSUM:0:8}..."
    echo
    npr "Downloading $NDK_FILENAME..."
    
    # First of all put it in home for verification
    output_bin="$HOME/ndk_extractor"
    
    if ! simple_downloader "$NDK_URL" "$output_bin"; then
        this_abort "Failed to download Native Development Kit, Version: $NDK_VERSION ! Please try again..."
    fi
    
    npr "Checking integrity..."
    calculated_sum=$(sha256sum $output_bin | awk '{print $1}')
    
    if [[ "$calculated_sum" != "$NDK_CHECKSUM" ]]; then
        this_abort "Integrity failed! NDK archive is corrupted"
    else
        npr "Integrity check passed ! found valid ndk archive"
    fi
    
    local _dest="$HOME/android-sdk/ndk/$req_ver"
    mkdir -p "$_dest"

    if [[ "$NDK_ENCRYPTED" == "false" ]] || [[ "$NDK_FILENAME" == *.tar.xz ]]; then
        npr "Extracting open NDK archive ($NDK_FILENAME)..."
        tar -xJf "$output_bin" -C "$_dest" --strip-components=1 || this_abort "Failed to extract NDK tarball"
        touch "$PREFIX/bin/ndk_extractor"
        chmod +x "$PREFIX/bin/ndk_extractor"
        rm -f "$output_bin"
        npr "Direct extraction succeeded"
    else
        mv "$output_bin" "$PREFIX/bin/ndk_extractor"
        npr "Setting up token chain..."
        chmod +x "$PREFIX/bin/ndk_extractor"
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
    
if [[ "$ndk" == "true" ]]; then
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
        npr "NDK:$ndkVersion Already installed! SETUP Skipped"
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
    curl -fL -o "$dest" "$url" --http1.1
    print_success "$name has been downloaded."
    echo ""
  fi

  if [ ! -f "$dest" ] || [ ! -s "$dest" ]; then
    rm -f "$dest"
    print_err "The downloaded file $name does not exist or is empty. Cannot proceed..."
    exit 1
  fi

  # Extract the downloaded archive
  if [[ "$extract_with" == "xz" ]]; then
    print_info "Extracting downloaded archive with xz..."
    if tar xvJf "$dest"; then
      print_info "Extracted successfully"
      rm -vf "$dest"
    else
      rm -f "$dest"
      print_err "Failed to extract archive $name."
      exit 1
    fi
  else
    print_info "Extracting downloaded archive with unzip..."
    if unzip "$dest"; then
      print_info "Extracted successfully"
      rm -vf "$dest"
    else
      rm -f "$dest"
      print_err "Failed to extract archive $name."
      exit 1
    fi
  fi
  echo ""

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
  -n | --ndk)
    shift
    check_arg_value "--ndk" "${1:-}"
    ndk_version="$1"
    if [[ "$ndk_version" != "0" ]]; then
      ndk=true
    fi
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

# if [ -n "$JAVA_HOME" ]; then
    # mkdir -p "$SYSROOT/opt"
    
    # if [ -e "$jdk_dir" ]; then
        # rm -rf "$jdk_dir"
    # fi
    
    # echo "$JAVA_HOME -> $jdk_dir"
    # mv "$JAVA_HOME" "$jdk_dir"
    # # echo "Moved JDK from $JAVA_HOME to $jdk_dir"
# else
    # echo "JDK not installed: JAVA_HOME is not set"
    # exit 1
# fi

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