#!/usr/bin/env python3
"""End-to-end check over adb + USB MIDI. Three passes, each PASS/FAIL:
  pads   tap every visible pad; assert note_on (ch 10, the phone's fixed velocity) and note_off (ch 10) arrive here
  bank   Next bank, tap pad 1, expect note 52 on ch 10, Previous bank
  learn  EDIT, pad 1, LEARN, send note 60 on ch 2 from this Mac, DONE, tap pad 1, expect 60 on ch 2;
         then restore pad 1 (RESET PAD) and expect 36 on ch 10 again

Usage:  .venv/bin/python tools/e2e_midi.py [port-substring]
Phone:  USB debugging on, MiniMidi in the foreground on bank A, settings panel closed,
        Fixed velocity ON (any level: the script reads it off the screen and asserts it).
        The script itself switches the USB mode to MIDI (`adb shell svc usb setFunctions midi`).
With several adb devices (e.g. over Wi-Fi) pick one the way adb does:
        ANDROID_SERIAL=adb-RFCW709RGRH-XxXrqF._adb-tls-connect._tcp .venv/bin/python tools/e2e_midi.py
If an adb call fails, adb's stderr is printed and the script exits non-zero, as it does on any FAIL.
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
VELOCITY = None  # read from the phone's "VELOCITY: FIXED n" strip in main()


def adb(*args):
    return subprocess.run([ADB, *args], check=True, capture_output=True, text=True).stdout


def nodes():
    """[(content-desc, text, (x, y) centre)] for every node in the accessibility tree."""
    adb("shell", "uiautomator", "dump", "/sdcard/minimidi-ui.xml")
    root = ET.fromstring(adb("exec-out", "cat", "/sdcard/minimidi-ui.xml"))
    out = []
    for n in root.iter("node"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        out.append((n.get("content-desc", ""), n.get("text", ""), ((x1 + x2) // 2, (y1 + y2) // 2)))
    return out


def find(label, tree):
    """Centre of the node whose content-desc (or, failing that, text) is exactly `label`, else None."""
    for desc, text, centre in tree:
        if desc == label:
            return centre
    for desc, text, centre in tree:
        if text == label:
            return centre
    return None


class Missing(Exception):
    pass


def tap(label, tree=None):
    centre = find(label, tree or nodes())
    if centre is None:
        raise Missing(f"{label!r} not on screen")
    adb("shell", "input", "tap", str(centre[0]), str(centre[1]))


def visible_pads():
    """{pad number: (x, y) centre in screen px}, read from the accessibility tree."""
    pads = {}
    for desc, _, centre in nodes():
        m = re.match(r"Pad (\d+),", desc)
        if m:
            pads[int(m.group(1))] = centre
    return pads


def matching(names, hint):
    return [n for n in names if hint.lower() in n.lower()]


def wait_for_port(hint, timeout=5.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end and not matching(mido.get_input_names(), hint):
        time.sleep(0.25)


def open_port(hint, output=False):
    names = mido.get_output_names() if output else mido.get_input_names()
    matches = matching(names, hint)
    if len(matches) != 1:
        sys.exit(f"Need exactly one MIDI {'output' if output else 'input'} matching {hint!r}; found {matches}. All: {names}")
    return (mido.open_output if output else mido.open_input)(matches[0])


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


def settle(port, backlog):
    """Discard everything in flight (e.g. the sound an edit-mode pad tap also makes)."""
    time.sleep(0.4)
    list(port.iter_pending())
    backlog.clear()


def check_hit(port, backlog, note, channel, velocity=None, what=""):
    """Wait for note_on + note_off of `note`; return a list of failure strings."""
    on = wait_for(port, backlog, "note_on", note)
    off = wait_for(port, backlog, "note_off", note)
    print(f"  {what:<14} on={on}  off={off}")
    bad = []
    if on is None:
        bad.append(f"{what}: no note_on for note {note}")
    elif on.channel != channel or (velocity is not None and on.velocity != velocity):
        bad.append(f"{what}: bad note_on {on} (want ch {channel + 1}" + (f", velocity {velocity})" if velocity else ")"))
    if off is None:
        bad.append(f"{what}: no note_off for note {note}")
    elif off.channel != channel:
        bad.append(f"{what}: bad note_off {off} (want ch {channel + 1})")
    return bad


def pass_pads(port, backlog, pads):
    bad = []
    for pad, (x, y) in sorted(pads.items()):
        adb("shell", "input", "tap", str(x), str(y))
        bad += check_hit(port, backlog, FIRST_NOTE + pad - 1, CHANNEL, VELOCITY, f"pad {pad}")
    return bad


def pass_bank(port, backlog, pads):
    bad = []
    try:
        tap("Next bank")
        try:
            adb("shell", "input", "tap", *map(str, pads[1]))
            bad += check_hit(port, backlog, FIRST_NOTE + 16, CHANNEL, VELOCITY, "bank B pad 1")
        finally:
            tap("Previous bank")
    except Missing as e:
        bad.append(str(e))
    return bad


def fresh_pad1():
    """Pad 1's centre as strings, re-read after the layout changed (e.g. the edit panel docked)."""
    time.sleep(0.3)
    return list(map(str, visible_pads()[1]))


def restore_pad1(bad):
    """Put pad 1 back to its default mapping (the map persists on the phone). Never raises."""
    try:
        if find("Done editing", nodes()) is None:
            tap("EDIT")
        adb("shell", "input", "tap", *fresh_pad1())
        time.sleep(0.5)
        tap("Reset pad")
        tap("Done editing")
    except (Missing, subprocess.CalledProcessError, KeyError) as e:
        bad.append(f"restore of pad 1 failed, reset it by hand: {e!r}")


def pass_learn(port, backlog, pads, out):
    bad = []
    try:
        tap("EDIT")
        pad1 = fresh_pad1()  # the edit panel shrinks the grid: the coordinates read before EDIT are stale
        adb("shell", "input", "tap", *pad1)  # selects pad 1 (and plays it)
        time.sleep(0.5)
        tree = nodes()
        if find("Learn", tree) is None:
            return ["LEARN not shown: the phone's MIDI input port isn't connected (is the Mac's port visible to the phone?)"]
        tap("Learn", tree)
        time.sleep(0.3)
        out.send(mido.Message("note_on", note=60, channel=1, velocity=100))
        out.send(mido.Message("note_off", note=60, channel=1, velocity=0))
        time.sleep(0.5)
        tap("Done editing")
        settle(port, backlog)
        adb("shell", "input", "tap", *map(str, pads[1]))  # edit mode closed: the grid is full size again
        bad += check_hit(port, backlog, 60, 1, None, "learned pad 1")
    except Missing as e:
        bad.append(str(e))
    finally:
        # Restore the default mapping whatever happened above.
        restore_pad1(bad)
    if not bad:
        settle(port, backlog)
        adb("shell", "input", "tap", *map(str, pads[1]))
        bad += check_hit(port, backlog, FIRST_NOTE, CHANNEL, None, "reset pad 1")
    return bad


def main():
    hint = sys.argv[1] if len(sys.argv) > 1 else ""
    adb("shell", "svc", "usb", "setFunctions", "midi")
    wait_for_port(hint)
    end = time.monotonic() + 5  # let the app notice the new USB mode
    while time.monotonic() < end and find("MIDI connected", nodes()) is None:
        time.sleep(0.5)
    if find("Bank A", nodes()) is None:
        sys.exit('Expected the phone to show "Bank A"; switch to bank A (settings and edit closed) and rerun')
    global VELOCITY
    strip = [text for _, text, _ in nodes() if text.startswith("VELOCITY: FIXED ")]
    if not strip:
        sys.exit("Turn Fixed velocity on in settings (the velocity assert needs a known level) and rerun")
    VELOCITY = int(strip[0].rsplit(" ", 1)[1])
    pads = visible_pads()
    if sorted(pads) not in (list(range(1, 9)), list(range(1, 17))):
        sys.exit(f"Expected pads 1..8 or 1..16 on screen, found {sorted(pads)}")
    results = {}
    with open_port(hint) as port, open_port(hint, output=True) as out:
        list(port.iter_pending())  # drain anything stale
        backlog = []
        results["pads"] = pass_pads(port, backlog, pads)
        results["bank"] = pass_bank(port, backlog, pads)
        results["learn"] = pass_learn(port, backlog, pads, out)
    print()
    for name, bad in results.items():
        print(f"{'PASS' if not bad else 'FAIL'}  {name}")
        for b in bad:
            print(f"      {b}")
    if any(results.values()):
        sys.exit(1)
    print(f"OK: {len(pads)} pads -> notes {FIRST_NOTE}..{FIRST_NOTE + max(pads) - 1} ch 10 vel {VELOCITY}; bank and learn passes")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as e:
        sys.exit(f"adb failed: {' '.join(map(str, e.cmd))}\n{e.stderr}")
