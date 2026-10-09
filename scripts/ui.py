#!/usr/bin/env python3
"""Drive the app on the emulator through adb + uiautomator.

  ui.py dump                 list visible nodes (text / content-desc / resource-id / bounds)
  ui.py tap <needle>         tap the first node whose text, content-desc or id contains <needle>
  ui.py shot <name>          save a screenshot to out/screenshots/<name>.png
  ui.py key <KEYCODE>        send a key event (BACK, HOME, MENU, ...)
  ui.py type <n> <text>      replace the contents of the n-th text field (0-based) with <text>
  ui.py hidekb               close the on-screen keyboard if it is showing
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "out", "screenshots")
ADB = os.path.join(os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Android/Sdk"), "platform-tools", "adb")


def adb(*args, binary=False):
    out = subprocess.run([ADB, *args], capture_output=True, check=False).stdout
    return out if binary else out.decode(errors="replace")


def nodes():
    root = None
    for _ in range(3):  # uiautomator returns nothing while the screen is animating
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        try:
            root = ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml"))
            break
        except ET.ParseError:
            time.sleep(1)
    if root is None:
        sys.exit("ui.py: could not dump the view hierarchy")
    found = []
    for n in root.iter("node"):
        a = n.attrib
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        label = a.get("text") or a.get("content-desc") or ""
        rid = a.get("resource-id", "").split("/")[-1]
        if label or (rid and a.get("clickable") == "true"):
            found.append(dict(label=label, id=rid, cls=a.get("class", "").split(".")[-1],
                              click=a.get("clickable") == "true", checked=a.get("checked"),
                              center=((x1 + x2) // 2, (y1 + y2) // 2)))
    return found


def fields():
    """Centres and current text of every text field, top to bottom."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    root = ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml"))
    out = []
    for n in root.iter("node"):
        if n.attrib.get("class", "").endswith("EditText"):
            x1, y1, x2, y2 = map(int, re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.attrib["bounds"]).groups())
            out.append((((x1 + x2) // 2, (y1 + y2) // 2), n.attrib.get("text", "")))
    return out


def main():
    cmd, arg = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "")
    if cmd == "dump":
        for n in nodes():
            flags = ("C" if n["click"] else " ") + ("x" if n["checked"] == "true" else " ")
            print(f"{flags} {n['cls']:<18} {n['center'][0]:>4},{n['center'][1]:<5} {n['id']:<28} {n['label'][:90]!r}")
    elif cmd == "tap":
        for n in nodes():
            if arg.lower() in n["label"].lower() or arg == n["id"]:
                adb("shell", "input", "tap", str(n["center"][0]), str(n["center"][1]))
                print(f"tapped {n['label'] or n['id']!r} at {n['center']}")
                return
        sys.exit(f"no node matching {arg!r}")
    elif cmd == "shot":
        os.makedirs(OUT, exist_ok=True)
        path = os.path.join(OUT, arg + ".png")
        open(path, "wb").write(adb("exec-out", "screencap", "-p", binary=True))
        print(path)
    elif cmd == "key":
        adb("shell", "input", "keyevent", "KEYCODE_" + arg)
    elif cmd == "hidekb":
        if "mInputShown=true" in adb("shell", "dumpsys", "input_method"):
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.6)
    elif cmd == "type":
        (x, y), old = fields()[int(arg)]
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(0.6)
        if old:  # select everything, then delete the selection
            adb("shell", "input", "keycombination", "113", "29")
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
            # Number fields ignore select-all; walk to the end and delete what is left.
            adb("shell", "input", "keyevent", "KEYCODE_MOVE_END", *(["KEYCODE_DEL"] * len(old)))
        text = sys.argv[3]
        if text:
            adb("shell", "input", "text", "'" + text.replace("'", "'\\''").replace(" ", "%s") + "'")
        print(f"typed {text!r} into field {arg}")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
