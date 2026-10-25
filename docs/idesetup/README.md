# IDESetup Documentation & Decrypted Scripts Reference

This directory contains the decrypted source code and architectural documentation for **`idesetup`**, the internal bootstrap setup script executed during the first-run onboarding of **Android Code Studio**.

---

## Table of Contents
1. [Overview](#1-overview)
2. [Files in this Directory](#2-files-in-this-directory)
3. [ARM64 vs. ARMv7 Architecture Comparison](#3-arm64-vs-armv7-architecture-comparison)
4. [Reverse-Engineering & Decryption Methodology](#4-reverse-engineering--decryption-methodology)
5. [Execution Architecture](#5-execution-architecture)
6. [CLI Options Reference](#6-cli-options-reference)
7. [Embedded Endpoints & NDK Token](#7-embedded-endpoints--ndk-token)
8. [Step-by-Step Installation Process](#8-step-by-step-installation-process)
9. [Modifying & Rebuilding `idesetup`](#9-modifying--rebuilding-idesetup)

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

| File | Architecture | Lines | Payload Size | Description |
| :--- | :--- | :--- | :--- | :--- |
| [`decrypted_idesetup_arm64.sh`](./decrypted_idesetup_arm64.sh) | `arm64-v8a` | 564 | 14,250 B | The 64-bit script extracted from `assets/data/common/arm64/idesetup`. |
| [`decrypted_idesetup_arm.sh`](./decrypted_idesetup_arm.sh) | `armeabi-v7a` | 707 | 18,520 B | The 32-bit script extracted from `assets/data/common/arm/idesetup`. |
| [`decrypted_idesetup.sh`](./decrypted_idesetup.sh) | Default (`arm64-v8a`) | 564 | 14,250 B | Retained for backward compatibility. |
| [`README.md`](./README.md) | All | — | — | This technical documentation and reference guide. |

---

## 3. ARM64 vs. ARMv7 Architecture Comparison

The two scripts are **not identical**. ARMv7 (`armeabi-v7a`) is 143 lines longer and features a completely standalone pure-Bash implementation for NDK management.

| Feature / Behavior | ARM64 (`arm64-v8a`) | ARMv7 (`armeabi-v7a`) |
| :--- | :--- | :--- |
| **Binary Asset** | `assets/data/common/arm64/idesetup` (ELF64) | `assets/data/common/arm/idesetup` (ELF32) |
| **Binary Size** | 30,280 bytes | 38,044 bytes |
| **Ciphertext Payload Offset** | `data + 0x94e` | `data + 0xdfa` |
| **Decrypted Script Size** | 14,250 bytes (564 lines) | 18,520 bytes (707 lines) |
| **NDK Toolchain Method** | Calls external compiled `acs` CLI binary | Pure Bash implementation (`parse_ndk` + `jq`) |
| **Download Resiliency** | Single `curl` download attempt | Built-in `download_and_extract_ndk` with **3-attempt retry loop** |
| **NDK Manifest Endpoint** | `AndroidCSOfficial/acs-build-system` | `AndroidIDE-Rv2Official/rv2ide-native-kit` |
| **NDK Version Argument** | Dynamic version passed via `-n` / `--ndk` | Flag-based `-wn` / `--with-ndk` (hardcoded `28.2.13676358`) |
| **Integrity Check** | Handled by `acs` CLI | Direct SHA-256 verification using `sha256sum \| awk` |

### Why They Diverge:
ARMv7 (`armeabi-v7a`) was added later as an experimental architecture. Because the author did not have or compile a 32-bit version of the external `acs` helper tool for ARMv7, they implemented the NDK manifest parser and downloader directly in pure Bash (`parse_ndk` and `simple_downloader`), making the 32-bit ARM script fully self-contained.

---

## 4. Reverse-Engineering & Decryption Methodology

### Encryption Background
In upstream [AndroidIDE](https://github.com/AndroidIDEOfficial/AndroidIDE), `idesetup` was an open, plain-text bash script (`idesetup.sh`). 

When forked into **AndroidIDE-Rv2 / Android Code Studio**, it was compiled into ELF executables using **`shc`** (Generic Shell Script Compiler v4.x by Md Jahidul Hamid):
- **Cipher**: Alleged RC4 (ARC4) stream cipher.
- **Integrity Tokens**: Derives keystream through multiple rounds using validation strings (`"location has changed!"` and `"shell has changed!"`).
- **Path Locking**: Hardcoded interpreter path `#!/data/data/com.tom.rv2ide/files/usr/bin/bash`.

### Decryption Implementation
Both scripts were decrypted directly from their respective ELF binaries by:
1. Parsing the ELF symbol table (`.symtab`) to extract `stte_0`, `key`, `arc4`, and the encrypted `.data` section.
2. Initializing the 256-byte substitution state `stte` and loading the master key.
3. Simulating the exact state machine transitions across each intermediate token decryption.
4. Extracting and decrypting the `text` payload buffer in-memory.

---

## 5. Execution Architecture

The application executes `idesetup` during the onboarding phase:

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

## 6. CLI Options Reference

The `idesetup` scripts support the following command-line flags:

| Flag | Long Option | Description | ARM64 Support | ARMv7 Support | Default |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `-s` | `--sdk` | Android SDK version to install (e.g. `34.0.4`, `35.0.1`) | ✅ | ✅ | `34.0.4` |
| `-j` | `--jdk` | OpenJDK version to install (`17` or `21`) | ✅ | ✅ | `17` |
| `-n` | `--ndk` | Specific NDK version to install (e.g. `28.2.13676358`) | ✅ | ❌ | `0` (disabled) |
| `-wn`| `--with-ndk` | Flag enabling NDK setup | ✅ | ✅ | `false` |
| `-c` | `--with-cmdline-tools` | Download Android SDK command-line tools | ✅ | ✅ | `true` |
| `-g` | `--with-git` | Install `git` package | ✅ | ✅ | Off |
| `-o` | `--with-openssh` | Install `openssh` package | ✅ | ✅ | Off |
| `-y` | `--assume-yes` | Non-interactive mode (automatically answer yes to all prompts) | ✅ | ✅ | `false` |
| `-i` | `--install-dir`| Target installation directory | ✅ | ✅ | `$HOME` |
| `-m` | `--manifest` | Custom manifest URL for tool downloads | ✅ | ✅ | See below |
| `-a` | `--arch` | Target CPU architecture (`arm64`, `arm`) | ✅ | ✅ | Auto-detected |
| `-p` | `--package-manager` | Package manager command | ✅ | ✅ | `pkg` |
| `-l` | `--curl` | Curl package name | ✅ | ✅ | `libcurl` |
| `-h` | `--help` | Display script help message | ✅ | ✅ | — |

---

## 7. Embedded Endpoints & NDK Token

The decrypted scripts reveal the following remote endpoints and extraction credentials:

### 1. Main Tools Manifest
```bash
manifest="https://raw.githubusercontent.com/AndroidIDE-Rv2Official/androidide-rv2-tools/main/manifest.json"
```

### 2. NDK Manifest Endpoints
- **ARM64**:
  ```bash
  NDK_MANIFEST_FILE_URL="https://raw.githubusercontent.com/AndroidCSOfficial/acs-build-system/refs/heads/main/acs-manifest.json"
  PACKAGE_ID="android-native-kit"
  ```
- **ARMv7**:
  ```bash
  NDK_MANIFEST_FILE_URL="https://github.com/AndroidIDE-Rv2Official/rv2ide-native-kit/raw/refs/heads/main/native-kit.json"
  ```

### 3. NDK Extraction Token (Both Architectures)
```bash
local _token="795203ab38cefaa8663642d85fdb22bb8c7fc0e10d079da3209adec3ce39e0e3"
```
Passed to `$PREFIX/bin/ndk_extractor --extract "$_token" "$_arch" "$_dest" --decompress`.

### 4. Direct NDK Release Fallback
```bash
local ndkUrl="https://github.com/Mohammed-Baqer-null/AndroidIDE-Rv2-ndk/releases/download/v27.1.12297006/android-ndk-r27b-aarch64.zip"
```

---

## 8. Step-by-Step Installation Process

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
   - Downloads `ndk_extractor` (via `acs` CLI on ARM64, or via `wget` + `sha256sum` on ARMv7).
   - Unpacks NDK archive into `$HOME/android-sdk/ndk/<version>`.
   - Creates a bash wrapper script for `$HOME/android-sdk/ndk/<version>/ndk-build`.
9. **Cleanup**: Removes temporary downloaded manifests and archives.

---

## 9. Modifying & Rebuilding `idesetup`

If you want to customize `idesetup` (e.g., to use your own download mirrors, update tokens, or remove package name restrictions):

### Option A: Use the Plain Bash Script Directly (Recommended)
You can modify [`IdesetupSession.kt`](../../termux/application/src/main/java/com/tom/rv2ide/terminal/IdesetupSession.kt) to execute [`decrypted_idesetup_arm64.sh`](./decrypted_idesetup_arm64.sh) or [`decrypted_idesetup_arm.sh`](./decrypted_idesetup_arm.sh) directly through `bash`, bypassing the need for `shc` ELF compilation entirely.

### Option B: Recompile with `shc`
If you wish to maintain the compiled binary format:
```bash
# On an ARM64 or ARMv7 Linux/Termux environment:
pkg install shc clang

# Compile script with target bash interpreter:
shc -f decrypted_idesetup_arm64.sh -o idesetup -s /data/data/com.tom.rv2ide/files/usr/bin/bash

# Copy output to project assets:
cp idesetup termux/application/src/main/assets/data/common/arm64/idesetup
```
