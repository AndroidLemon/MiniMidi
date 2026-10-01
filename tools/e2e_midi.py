#!/usr/bin/env python3
"""End-to-end check: tap every visible pad via adb and assert the matching MIDI arrives on this Mac.

Usage:  .venv/bin/python tools/e2e_midi.py [port-substring]
Phone:  USB mode = MIDI, USB debugging on, MiniMidi in the foreground, settings panel closed.
"""
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import mido

ADB = os.environ.get("ADB") or shutil.which("adb") or os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
FIRST_NOTE = 36
CHANNEL = 9  # mido channels are 0-based: 9 is MIDI channel 10


def adb(*args):
    return subprocess.run([ADB, *args], check=True, capture_output=True, text=True).stdout


def visible_pads():
    """{pad number: (x, y) centre in screen px}, read from the accessibility tree."""
    adb("shell", "uiautomator", "dump", "/sdcard/minimidi-ui.xml")
    root = ET.fromstring(adb("exec-out", "cat", "/sdcard/minimidi-ui.xml"))
    pads = {}
    for node in root.iter("node"):
        m = re.match(r"Pad (\d+),", node.get("content-desc", ""))
        if m:
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
            pads[int(m.group(1))] = ((x1 + x2) // 2, (y1 + y2) // 2)
    return pads


def open_port(hint):
    names = mido.get_input_names()
    matches = [n for n in names if hint.lower() in n.lower()]
    if len(matches) != 1:
        sys.exit(f"Need exactly one MIDI input matching {hint!r}; found {matches}. All inputs: {names}")
    return mido.open_input(matches[0])


def wait_for(port, backlog, kind, note, timeout=1.0):
    """Keep every received message in `backlog` so a later wait can still find it."""
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        backlog.extend(port.iter_pending())
        for i, msg in enumerate(backlog):
            if msg.type == kind and msg.note == note:
                return backlog.pop(i)
        time.sleep(0.002)
    return None


def main():
    hint = sys.argv[1] if len(sys.argv) > 1 else ""
    pads = visible_pads()
    if sorted(pads) not in (list(range(1, 9)), list(range(1, 17))):
        sys.exit(f"Expected pads 1..8 or 1..16 on screen, found {sorted(pads)}")
    failures = []
    with open_port(hint) as port:
        for _ in port.iter_pending():
            pass  # drain anything stale
        backlog = []
        for pad, (x, y) in sorted(pads.items()):
            note = FIRST_NOTE + pad - 1
            adb("shell", "input", "tap", str(x), str(y))
            on = wait_for(port, backlog, "note_on", note)
            off = wait_for(port, backlog, "note_off", note)
            print(f"pad {pad:2d}  on={on}  off={off}")
            if on is None:
                failures.append(f"pad {pad}: no note_on for note {note}")
            elif on.channel != CHANNEL or not 1 <= on.velocity <= 127:
                failures.append(f"pad {pad}: bad note_on {on}")
            if off is None:
                failures.append(f"pad {pad}: no note_off for note {note}")
    if failures:
        print("\n".join(["FAIL"] + failures))
        sys.exit(1)
    print(f"OK: {len(pads)} pads → notes {FIRST_NOTE}..{FIRST_NOTE + max(pads) - 1} on channel 10")


if __name__ == "__main__":
    main()
