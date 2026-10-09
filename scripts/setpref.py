#!/usr/bin/env python3
"""Change settings of the running debug build on the emulator without tapping through the UI.

  setpref.py key=value [key=value ...]

Each pair is sent to the debug-only receiver of the app, so the change takes effect live.
Values are typed by form: true/false -> boolean, 12 -> int, 1.5 -> float, anything else ->
string (prefix with s: to force a string, e.g. smtp_user=s:1234).
"""
import os
import re
import shlex
import subprocess
import sys

ADB = os.path.join(os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Android/Sdk"), "platform-tools", "adb")
RECEIVER = "dev.hardline/dev.hardline.debug.PrefsReceiver"


def kind(value):
    if value.startswith("s:"):
        return "string", value[2:]
    if value in ("true", "false"):
        return "bool", value
    if re.fullmatch(r"-?\d+", value):
        return "int", value
    if re.fullmatch(r"-?\d+\.\d+", value):
        return "float", value
    return "string", value


for pair in sys.argv[1:]:
    key, raw = pair.split("=", 1)
    type_, value = kind(raw)
    command = f"am broadcast -n {RECEIVER} --es key {shlex.quote(key)} --es type {type_} --es value {shlex.quote(value)}"
    out = subprocess.run([ADB, "shell", command], capture_output=True, timeout=30).stdout.decode(errors="replace")
    if "Broadcast completed" not in out:
        sys.exit(f"setpref: {key} was not delivered: {out.strip()}")
print("set", " ".join(sys.argv[1:]))
