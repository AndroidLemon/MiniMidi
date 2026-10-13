# Network MIDI (RTP-MIDI) spike: results

Date: 2026-10-12. Throwaway code is on the branch `spike/rtp-midi` (not merged). Phone: Galaxy Z Fold5. Mac: Audio MIDI Setup network session, same home Wi-Fi.

The spike was a bare AppleMIDI responder:
- `_apple-midi._udp` via NsdManager, with the control port on 5004 and the data port on 5005;
- IN/OK, answering the Mac's clock sync (CK) and BY;
- one MIDI message per RTP packet, with no recovery journal;
- a `WIFI_MODE_FULL_LOW_LATENCY` lock.

The Mac's handshake succeeded first time.

## Measurement

`tools/e2e_midi.py --repeat` measures the round trip: the Mac's 120 BPM clock goes to the phone, and the repeats at 1/16 come back. "Deviation" is how far each repeat arrived from its grid tick.

| Link | Mean | Max | Off-grid | Stuck note at end |
|---|---|---|---|---|
| USB | 1.8 ms | 2.7 ms | 0 | no |
| Wi-Fi | 15.5 ms | 51.0 ms | 4 / 66 | yes |
| Wi-Fi, Mac AWDL down | 14.6 ms | 74.9 ms | 1 / 40 | yes |

The CK clock-sync half-round-trips logged while the link was idle ranged from 2.9 ms to 112 ms, with or without AWDL.

## Conclusions

- Turning off the Mac's AWDL didn't help, so the variation comes from the phone's radio or the network, not from the Mac.
- A one-way estimate for finger hits is about half of these figures: about 7–8 ms on average, with occasional late hits of 35 ms or more.
- The final note-off arrived before the last note-on. CoreMIDI's network driver delivers events by RTP timestamp, and the spike never starts its own clock sync, so ordering can slip. A real implementation needs phone-initiated CK and the recovery journal so a lost note-off can't hang.
- Next options: a BLE MIDI spike, which may be steadier because of its fixed connection interval (unproven), or keeping the cable.
