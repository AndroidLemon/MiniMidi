#!/usr/bin/env python3
"""End-to-end check over adb + USB MIDI. Three default passes, each PASS/FAIL:
  pads   tap every visible pad; assert note_on (ch 10, the phone's fixed velocity) and note_off (ch 10) arrive here
  bank   Next bank, tap pad 1, expect note 52 on ch 10, Previous bank
  learn  EDIT, pad 1, LEARN, send note 60 on ch 2 from this Mac, DONE, tap pad 1, expect 60 on ch 2;
         then restore pad 1 (RESET PAD) and expect 36 on ch 10 again

A fourth pass runs only on request (--repeat) and then replaces the three above (same startup checks):
  repeat semi-manual note repeat check against this Mac's MIDI clock. The script sends Start and clock at
         120 BPM (one every 1/48 s, deadline-paced, every tick timestamped), and waits up to 30 s for you to
         hold REPEAT + pad 1 on the phone (about 3 s); start right after launching it, since Claude Code's `!`
         only shows output at the end. From the first hit it records note 36 ch 10 for 5 s, then sends Stop.
         Asserts: at least max(2, int(3*2*24/per*0.7)) note_ons; every note_on after the first at a
         multiple of the rate's tick count, or one tick after it when that count is >= 4 (late, never
         early; rate read from the screen, "Repeat rate 1/16" = 6 ticks); each note_on after
         the first preceded by a note_off; a final note_off after release. Prints count, mean/max
         deviation from the grid tick time (ms) and mean interval (125 ms at 1/16 @ 120 BPM).

Usage:  .venv/bin/python tools/e2e_midi.py [port-substring] [--repeat]
Phone:  USB debugging on, MiniMidi in the foreground on bank A, settings panel closed,
        Fixed velocity ON (any level: the script reads it off the screen and asserts it).
        The script itself switches the USB mode to MIDI (`adb shell svc usb setFunctions midi`).
With several adb devices (e.g. over Wi-Fi) pick one the way adb does:
        ANDROID_SERIAL=adb-RFCW709RGRH-XxXrqF._adb-tls-connect._tcp .venv/bin/python tools/e2e_midi.py
Repeat pass:
        ANDROID_SERIAL=adb-RFCW709RGRH-XxXrqF._adb-tls-connect._tcp .venv/bin/python tools/e2e_midi.py SAMSUNG --repeat
If an adb call fails, adb's stderr is printed and the script exits non-zero, as it does on any FAIL.
"""
import os
import re
import shutil
import subprocess
import sys
import threading
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


# Ticks per repeat for each rate label shown on the phone ("Repeat rate <label>"), 24 ticks per quarter note.
RATE_TICKS = {"1/4": 24, "1/8": 12, "1/8T": 8, "1/16": 6, "1/16T": 4, "1/32": 3, "1/32T": 2}
BPM = 120
TICK_S = 60.0 / BPM / 24  # 1/48 s
START_WAIT_S = 30  # seconds to wait for the user's first hit
RECORD_S = 5.0


def run_clock(out, ticks, stop):
    """Send 'clock' every TICK_S on absolute perf_counter deadlines (no sleep drift); log (index, send time).
    Tick 0 is the first clock after Start, as in the app."""
    base = time.perf_counter()
    i = 0
    while not stop.is_set():
        delay = base + i * TICK_S - time.perf_counter()
        if delay > 0:
            stop.wait(delay)
            if stop.is_set():
                break
        out.send(mido.Message("clock"))
        ticks.append((i, time.perf_counter()))
        i += 1


def pass_repeat(port, out, rate):
    """Semi-manual: the user holds REPEAT + pad 1 (adb can't hold two fingers). Returns failure strings."""
    bad = []
    per = RATE_TICKS[rate]
    events = []  # (type, perf_counter receive time, channel) for note 36
    ticks = []
    stop = threading.Event()
    clock = threading.Thread(target=run_clock, args=(out, ticks, stop), daemon=True)

    def on_msg(msg):
        if msg.type in ("note_on", "note_off") and msg.note == FIRST_NOTE:
            kind = "note_off" if msg.type == "note_off" or msg.velocity == 0 else "note_on"
            events.append((kind, time.perf_counter(), msg.channel))

    list(port.iter_pending())
    port.callback = on_msg
    try:
        out.send(mido.Message("start"))
        clock.start()
        # Claude Code's `!` shows output only when the command ends, so don't rely on a prompt: wait for the first hit.
        print(f"\n  >>> Hold REPEAT and pad 1 within {START_WAIT_S} s, for about 3 s  (rate {rate}, {per} ticks, {BPM} BPM) <<<\n", flush=True)
        end = time.monotonic() + START_WAIT_S
        while time.monotonic() < end and not any(e[0] == "note_on" for e in events):
            time.sleep(0.01)
        if not events:
            bad.append(f"no hit on pad 1 within {START_WAIT_S} s")
        else:
            time.sleep(RECORD_S)
    finally:
        stop.set()
        clock.join(timeout=1.0)
        port.callback = None
        out.send(mido.Message("stop"))
    if not ticks:
        return ["no clock was sent"]
    if bad:
        return bad
    ons = [e for e in events if e[0] == "note_on"]
    print(f"  recorded {len(ons)} note_on, {len(events) - len(ons)} note_off for note {FIRST_NOTE}; {len(ticks)} clock ticks sent")
    # 3 s hold at 120 BPM = 2 beats/s = 48 ticks/s; allow 30% slack for the finger landing/lifting.
    want = max(2, int(3 * 2 * 24 / per * 0.7))
    if len(ons) < want:
        bad.append(f"expected at least {want} note_ons for note {FIRST_NOTE} at {rate}, got {len(ons)}")
    for kind, t, ch in events:
        if ch != CHANNEL:
            bad.append(f"{kind} on ch {ch + 1} at {t:.3f}, want ch {CHANNEL + 1}")
    # Every note_on after the first must directly follow a note_off (no overlap / stuck note).
    for n, ev in enumerate(events):
        if ev[0] == "note_on" and n > 0 and events[n - 1][0] != "note_off":
            bad.append(f"note_on at {ev[1]:.3f} not preceded by a note_off (overlapping or stuck)")
    if events and events[-1][0] != "note_off":
        bad.append("no final note_off after release (note stuck on)")
    # Timing: nearest sent tick by receive time. Latency only makes a repeat late, never early, so the tick index must be
    # a multiple of `per` (residue 0) or one tick after (residue 1, only when per >= 4: below that +1 is another grid line).
    by_index = dict(ticks)
    devs = []
    for k, (_, t, _) in enumerate(ons):
        if k == 0:
            continue  # the immediate hit on press, not grid-aligned
        idx = min(ticks, key=lambda x: abs(x[1] - t))[0]
        grid = idx - idx % per
        if idx % per not in ((0, 1) if per >= 4 else (0,)):
            bad.append(f"note_on #{k + 1} at tick {idx}: off the {per}-tick grid (idx % {per} = {idx % per})")
        if grid in by_index:
            devs.append(abs(t - by_index[grid]) * 1000)
    if devs:
        gaps = [(b[1] - a[1]) * 1000 for a, b in zip(ons[1:], ons[2:])]
        print(f"  timing: {len(devs)} repeats, deviation from grid tick mean {sum(devs) / len(devs):.1f} ms, max {max(devs):.1f} ms;"
              f" mean interval {sum(gaps) / len(gaps) if gaps else float('nan'):.1f} ms"
              f" (expect {per * TICK_S * 1000:.1f} ms)")
    return bad


def main():
    args = sys.argv[1:]
    repeat = "--repeat" in args
    args = [a for a in args if a != "--repeat"]
    hint = args[0] if args else ""
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
    if repeat:
        labels = [d[len("Repeat rate "):] for d, _, _ in nodes() if d.startswith("Repeat rate ")]
        if not labels or labels[0] not in RATE_TICKS:
            sys.exit(f"Could not read a known 'Repeat rate <label>' off the screen (got {labels}); is the REPEAT row visible?")
        with open_port(hint) as port, open_port(hint, output=True) as out:
            try:
                results["repeat"] = pass_repeat(port, out, labels[0])
            finally:
                out.send(mido.Message("stop"))
    else:
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
    if repeat:
        print("OK: note repeat locked to the Mac's MIDI clock")
        return
    print(f"OK: {len(pads)} pads -> notes {FIRST_NOTE}..{FIRST_NOTE + max(pads) - 1} ch 10 vel {VELOCITY}; bank and learn passes")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as e:
        sys.exit(f"adb failed: {' '.join(map(str, e.cmd))}\n{e.stderr}")
