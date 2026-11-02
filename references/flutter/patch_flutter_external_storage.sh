#!/data/data/com.tom.rv2ide/files/usr/bin/bash
# ==============================================================================
# Android Code Studio - Flutter External Storage Execution Patch
# ==============================================================================
# This script enables running and building Flutter projects directly from
# /storage/emulated/0/ (external shared storage) without hitting:
#   "ProcessPackageException: ProcessException: Found candidates, but lacked
#    sufficient permissions to execute .../gradlew"
#
# It patches flutter_tools to route gradlew execution through the internal
# storage bash interpreter ($PREFIX/bin/bash), completely bypassing the Linux
# kernel's VFS noexec mount restriction on /storage/emulated/0.
#
# ZERO EXTERNAL DEPENDENCIES: Uses the Dart binary already bundled in Flutter SDK.
# ==============================================================================

set -e

# 1. Resolve environment paths
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

if [ -z "${FLUTTER_ROOT:-}" ]; then
    if [ -d "$PREFIX/opt/flutter" ]; then
        FLUTTER_ROOT="$PREFIX/opt/flutter"
    elif [ -d "/data/user/0/com.tom.rv2ide/files/usr/opt/flutter" ]; then
        FLUTTER_ROOT="/data/user/0/com.tom.rv2ide/files/usr/opt/flutter"
    elif [ -d "/data/data/com.tom.rv2ide/files/usr/opt/flutter" ]; then
        FLUTTER_ROOT="/data/data/com.tom.rv2ide/files/usr/opt/flutter"
    else
        echo "Error: Flutter SDK not found in $PREFIX/opt/flutter." >&2
        exit 1
    fi
fi

GRADLE_DART="$FLUTTER_ROOT/packages/flutter_tools/lib/src/android/gradle.dart"
if [ ! -f "$GRADLE_DART" ]; then
    echo "Error: $GRADLE_DART not found!" >&2
    exit 1
fi

# Locate bundled Dart binary (guaranteed to exist in Flutter SDK)
DART_BIN=""
if [ -x "$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart" ]; then
    DART_BIN="$FLUTTER_ROOT/bin/cache/dart-sdk/bin/dart"
elif [ -x "$PREFIX/opt/flutter/bin/cache/dart-sdk/bin/dart" ]; then
    DART_BIN="$PREFIX/opt/flutter/bin/cache/dart-sdk/bin/dart"
elif command -v dart >/dev/null 2>&1; then
    DART_BIN="$(command -v dart)"
else
    echo "Error: Dart compiler not found. Ensure Flutter SDK is installed." >&2
    exit 1
fi

echo "=========================================================="
echo " Flutter External Storage Runner Patch"
echo "=========================================================="
echo "Prefix:        $PREFIX"
echo "Flutter Root:  $FLUTTER_ROOT"
echo "Target File:   $GRADLE_DART"
echo "Dart Compiler: $DART_BIN"
echo ""

# 2. Patch gradle.dart using bundled Dart
echo "[1/3] Patching gradle.dart in flutter_tools..."
TMP_DART_PATCH="$PREFIX/tmp/patch_gradle_$$.dart"
mkdir -p "$PREFIX/tmp" 2>/dev/null || true

cat > "$TMP_DART_PATCH" <<'DARTEOF'
import 'dart:io';

void main(List<String> args) {
  final path = args[0];
  final prefix = args[1];
  final file = File(path);
  if (!file.existsSync()) {
    stderr.writeln('Target file does not exist: $path');
    exit(1);
  }

  final content = file.readAsStringSync();

  // 1. Auto-migrate previous patch containing obsolete _platform identifier
  if (content.contains('_platform.isLinux') || content.contains('_platform.environment')) {
    print('  -> Found previous patch with obsolete _platform identifier. Migrating to globals.platform...');
    final migrated = content
        .replaceAll('_platform.isLinux', '(globals.platform.isLinux || globals.platform.isAndroid)')
        .replaceAll('_platform.environment', 'globals.platform.environment');
    file.writeAsStringSync(migrated);
    print('  -> Successfully migrated to globals.platform!');
    exit(0);
  }

  // 2. Already correctly patched with globals.platform
  if (content.contains('bypass noexec on external storage') && content.contains('globals.platform')) {
    print('  -> Already correctly patched with globals.platform! Skipping code modification.');
    exit(0);
  }

  // Backup original file if not backed up yet
  if (!File('$path.bak').existsSync()) {
    File('$path.bak').writeAsStringSync(content);
  }

  final bashBin = '$prefix/bin/bash';
  final target = '    final command = <String>[\n      gradleExecutablePath,';
  final targetAlt = '    final List<String> command = <String>[\n      gradleExecutablePath,';

  final replacement = '''    final command = <String>[
      // Termux / Android: route gradlew through bash to bypass noexec on external storage (/storage/emulated/0)
      if ((globals.platform.isLinux || globals.platform.isAndroid) && !gradleExecutablePath.endsWith('.bat')) ...<String>[
        globals.platform.environment['PREFIX'] != null
            ? '\${globals.platform.environment['PREFIX']}/bin/bash'
            : '$bashBin',
      ],
      gradleExecutablePath,''';

  String updated;
  if (content.contains(target)) {
    updated = content.replaceFirst(target, replacement);
  } else if (content.contains(targetAlt)) {
    updated = content.replaceFirst(targetAlt, replacement);
  } else {
    // Regex match in case of indentation variance
    final regex = RegExp(r'(\s+)(final\s+(?:List<String>\s+)?command\s*=\s*<String>\[\s*gradleExecutablePath,)');
    if (regex.hasMatch(content)) {
      updated = content.replaceFirstMapped(regex, (m) {
        final indent = m[1] ?? '    ';
        return '$indent' 'final command = <String>[\n'
            '$indent' '  // Termux / Android: route gradlew through bash to bypass noexec on external storage (/storage/emulated/0)\n'
            '$indent' '  if ((globals.platform.isLinux || globals.platform.isAndroid) && !gradleExecutablePath.endsWith(\'.bat\')) ...<String>[\n'
            '$indent' '    globals.platform.environment[\'PREFIX\'] != null ? \'\${globals.platform.environment[\'PREFIX\']}/bin/bash\' : \'$bashBin\',\n'
            '$indent' '  ],\n'
            '$indent' '  gradleExecutablePath,';
      });
    } else {
      stderr.writeln('  -> Error: target pattern not found in gradle.dart');
      exit(1);
    }
  }

  file.writeAsStringSync(updated);
  print('  -> Successfully patched _runGradleTask to invoke bash wrapper via Dart (globals.platform)!');
}
DARTEOF

"$DART_BIN" "$TMP_DART_PATCH" "$GRADLE_DART" "$PREFIX"
rm -f "$TMP_DART_PATCH"

# 3. Invalidate flutter_tools snapshot cache
echo "[2/3] Invalidating flutter_tools snapshot cache..."
rm -f "$FLUTTER_ROOT/bin/cache/flutter_tools.snapshot"
rm -f "$FLUTTER_ROOT/bin/cache/flutter_tools.stamp"
echo "  -> Removed stale snapshot and stamp."

# 4. Rebuild flutter_tools snapshot
echo "[3/3] Recompiling flutter_tools snapshot..."
export PATH="$PREFIX/bin:$PATH"
"$FLUTTER_ROOT/bin/flutter" --version >/dev/null 2>&1 || true

if [ -f "$FLUTTER_ROOT/bin/cache/flutter_tools.snapshot" ]; then
    echo "  -> Successfully generated new flutter_tools.snapshot!"
else
    echo "  -> Note: Snapshot will be automatically compiled on next flutter command run."
fi

echo ""
echo "=========================================================="
echo " PATCH COMPLETE!"
echo "=========================================================="
echo "You can now run 'flutter build apk' directly from:"
echo "  /storage/emulated/0/AndroidIDEProjects/<your_project>/"
echo "without any permission denied or ProcessPackageException errors!"
echo "=========================================================="
