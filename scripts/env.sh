# Source this to put the Android SDK tools on PATH: `source scripts/env.sh`.
# ANDROID_HOME and JAVA_HOME are respected when already set. Otherwise the SDK is expected in
# ~/Android/Sdk, and a JDK unpacked into tools/jdk21 (git-ignored) is used if there is one.
_hardline_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ -z "${JAVA_HOME:-}" && -x "$_hardline_root/tools/jdk21/bin/java" ]]; then
  export JAVA_HOME="$_hardline_root/tools/jdk21"
fi
PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
[[ -n "${JAVA_HOME:-}" ]] && PATH="$JAVA_HOME/bin:$PATH"
export PATH
unset _hardline_root
