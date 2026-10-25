# IDESetup Documentation & Decrypted Script Reference

This directory contains the decrypted source code and architectural documentation for **`idesetup`**, the internal bootstrap setup script executed during the first-run onboarding of **Android Code Studio**.

---

## Table of Contents
1. [Overview](#1-overview)
2. [Files in this Directory](#2-files-in-this-directory)
3. [Reverse-Engineering & Decryption Background](#3-reverse-engineering--decryption-background)
4. [Execution Architecture](#4-execution-architecture)
5. [CLI Options Reference](#5-cli-options-reference)
6. [Embedded Endpoints & NDK Token](#6-embedded-endpoints--ndk-token)
7. [Step-by-Step Installation Process](#7-step-by-step-installation-process)
8. [Modifying & Rebuilding `idesetup`](#8-modifying--rebuilding-idesetup)

---

## 1. Overview

`idesetup` is responsible for provisioning the isolated Termux environment inside Android Code Studio so that users can compile real Android applications (Java, Kotlin, C/C++) directly on their Android devices.

When executed, it automatically installs and configures:
- **Android SDK**: Command-line tools, build-tools, platform-tools.
- **Java Development Kit**: OpenJDK 17 or OpenJDK 21.
- **Android Native Development Kit (NDK)**: C/C++ toolchain and `ndk-build`.
- **System Utilities**: `git`, `openssh`, `curl`, `jq`, `tar`, `wget`.
- **Environment Configuration**: Writes `JAVA_HOME` and system paths to `$SYSROOT/etc/ide-environment.properties`.

---

## 2. Files in this Directory

| File | Description |
| :--- | :--- |
| [`decrypted_idesetup.sh`](./decrypted_idesetup.sh) | The exact, complete 564-line decrypted bash script extracted from the binary assets. |
| [`README.md`](./README.md) | This technical documentation and reference guide. |

---

## 3. Reverse-Engineering & Decryption Background

### Why was `idesetup` encrypted?
In upstream [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE), `idesetup` was an open plain-text bash script (`idesetup.sh`). 

When forked into **AndroidIDE-Rv2 / Android Code Studio** by Mohammed Baqer (Tom), the script was extended with custom NDK support and compiled into an ELF binary using **`shc`** (Generic Shell Script Compiler):
- Asset path (ARM64): `termux/application/src/main/assets/data/common/arm64/idesetup`
- Asset path (ARMv7): `termux/application/src/main/assets/data/common/arm/idesetup`

### Encryption Technique
- **Compiler**: `shc` v4.x (by Md Jahidul Hamid).
- **Cipher**: ARC4 (Alleged RC4) stream cipher.
- **Key Derivation**: 
  - Master key (256 bytes) stored in the `.data` section.
  - Multi-round re-keying using plaintext validation tokens (`"location has changed!"` and `"shell has changed!"`).
- **Target Locking**: Hardcoded interpreter path `#!/data/data/com.tom.rv2ide/files/usr/bin/bash` with environment checks to prevent debugging or relocation.

The script was decrypted directly from the ARM64 ELF binary by tracing the ARC4 keystream states and reconstructing the 14,250-byte script payload.

---

## 4. Execution Architecture

The application executes `idesetup` during the onboarding phase as illustrated below:

```
[ First Launch ]
       │
       ▼
OnboardingActivity
       │
       ├─► IdeSetupConfigurationFragment (User selects SDK, JDK, NDK)
       │
       ▼ (Intent with EXTRA_ONBOARDING_RUN_IDESETUP)
TerminalActivity
       │
       ├─► IdesetupSession.createScript(Context)
       │         │
       │         ├─► Copies assets/data/common/${arch}/idesetup -> files/temp/idesetup
       │         └─► FileUtils.setFilePermissions(script, "rwx")
       │
       ▼
Termux Shell
       │
       ├─► Executes files/temp/idesetup with generated arguments
       ├─► ELF decrypts script payload in memory
       ├─► Runs bash / downloads packages / extracts NDK
       └─► Writes $SYSROOT/etc/ide-environment.properties
```

### Key Source Files:
- [`OnboardingActivity.kt`](../../core/app/src/main/java/com/tom/rv2ide/activities/OnboardingActivity.kt)
- [`IdeSetupConfigurationFragment.kt`](../../core/app/src/main/java/com/tom/rv2ide/fragments/onboarding/IdeSetupConfigurationFragment.kt)
- [`TerminalActivity.kt`](../../termux/application/src/main/java/com/tom/rv2ide/activities/TerminalActivity.kt)
- [`IdesetupSession.kt`](../../termux/application/src/main/java/com/tom/rv2ide/terminal/IdesetupSession.kt)

---

## 5. CLI Options Reference

The `idesetup` script supports the following command-line flags:

| Flag | Long Option | Description | Default |
| :--- | :--- | :--- | :--- |
| `-s` | `--sdk` | Android SDK version to install (e.g. `34.0.4`, `35.0.1`) | `34.0.4` |
| `-j` | `--jdk` | OpenJDK version to install (`17` or `21`) | `17` |
| `-n` | `--ndk` | Specific NDK version to install (e.g. `28.2.13676358`) | `0` (disabled) |
| `-wn`| `--with-ndk` | Flag enabling NDK setup | `false` |
| `-c` | `--with-cmdline-tools` | Download Android SDK command-line tools | `true` |
| `-g` | `--with-git` | Install `git` package | Off |
| `-o` | `--with-openssh` | Install `openssh` package | Off |
| `-y` | `--assume-yes` | Non-interactive mode (automatically answer yes to all prompts) | `false` |
| `-i` | `--install-dir`| Target installation directory | `$HOME` |
| `-m` | `--manifest` | Custom manifest URL for tool downloads | See below |
| `-a` | `--arch` | Target CPU architecture (`arm64`, `arm`) | Auto-detected via `uname -m` |
| `-p` | `--package-manager` | Package manager command | `pkg` |
| `-l` | `--curl` | Curl package name | `libcurl` |
| `-h` | `--help` | Display script help message | — |

---

## 6. Embedded Endpoints & NDK Token

The decrypted script reveals the following remote endpoints and extraction credentials:

### 1. Main Tools Manifest
```bash
manifest="https://raw.githubusercontent.com/AndroidIDE-Rv2Official/androidide-rv2-tools/main/manifest.json"
```

### 2. NDK Manifest & Package ID
```bash
NDK_MANIFEST_FILE_URL="https://raw.githubusercontent.com/AndroidCSOfficial/acs-build-system/refs/heads/main/acs-manifest.json"
PACKAGE_ID="android-native-kit"
```

### 3. NDK Extraction Token
```bash
local _token="795203ab38cefaa8663642d85fdb22bb8c7fc0e10d079da3209adec3ce39e0e3"
```
The token is passed to `$PREFIX/bin/ndk_extractor --extract "$_token" "$_arch" "$_dest" --decompress`.

### 4. Direct NDK Release Fallback
```bash
local ndkUrl="https://github.com/Mohammed-Baqer-null/AndroidIDE-Rv2-ndk/releases/download/v27.1.12297006/android-ndk-r27b-aarch64.zip"
```

---

## 7. Step-by-Step Installation Process

1. **Package Index Update**: Runs `apt update -y` and `apt --fix-broken install -y`.
2. **Device Architecture Detection**: Uses `ro.build.version.sdk` and `ro.product.cpu.abi` to identify `arm64-v8a` vs `armeabi-v7a`.
3. **Core Packages Installation**: Installs `jq`, `tar`, `wget`, `libcurl`, `git` (optional), `openssh` (optional).
4. **Manifest Retrieval**: Downloads `manifest.json` from the repository.
5. **Android SDK Installation**:
   - Downloads Android SDK archive and extracts via `tar xvJf`.
   - Downloads and extracts build-tools (e.g. 34.0.4) and platform-tools.
   - Downloads command-line tools if enabled.
6. **JDK Installation**: Installs `openjdk-17` or `openjdk-21` via `pkg install openjdk-<version>`.
7. **Environment Configuration**: Writes `JAVA_HOME` to `$SYSROOT/etc/ide-environment.properties`.
8. **NDK Setup (if selected)**:
   - Downloads `ndk_extractor` via `acs` CLI.
   - Unpacks NDK archive into `$HOME/android-sdk/ndk/<version>`.
   - Creates a bash wrapper script for `$HOME/android-sdk/ndk/<version>/ndk-build`.
9. **Cleanup**: Removes temporary downloaded manifests and archives.

---

## 8. Modifying & Rebuilding `idesetup`

If you want to customize `idesetup` (e.g., to use your own download mirrors, update tokens, or remove package name restrictions):

### Option A: Use the Plain Bash Script Directly (Recommended)
You can modify [`IdesetupSession.kt`](../../termux/application/src/main/java/com/tom/rv2ide/terminal/IdesetupSession.kt) to execute [`decrypted_idesetup.sh`](./decrypted_idesetup.sh) directly through `bash`, bypassing the need for `shc` ELF compilation entirely.

### Option B: Recompile with `shc`
If you wish to maintain the compiled binary format:
```bash
# On an ARM64 Linux or Termux environment:
pkg install shc clang

# Compile script with bash interpreter:
shc -f decrypted_idesetup.sh -o idesetup -s /data/data/com.tom.rv2ide/files/usr/bin/bash

# Copy output to project assets:
cp idesetup termux/application/src/main/assets/data/common/arm64/idesetup
```
