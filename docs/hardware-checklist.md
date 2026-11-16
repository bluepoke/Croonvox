# Hardware checklist

Manual checks on a real device for behaviour that unit tests can't cover (spec #1, testing decisions). Run them after changing the Audio Engine, and note device, Android version and build.

## Session recording (#8)

Prerequisites: a Track imported from a local file, wired headphones, and a way to make an acoustic loopback (earbud held against the microphone).

1. **Permission on first record.** Fresh install: open a Track, tap **Sing**. No permission prompt appears. Tap **Record**: the microphone prompt appears. Deny it: a message explains why it is needed and nothing records. Tap **Record** again and allow it: recording starts.
2. **Meter, clipping and gain.** With the microphone allowed, the meter moves when you speak. Shout close to the mic: the meter turns red and the clipping warning shows for about a second. Lower the input gain and check that the meter drops. Plug in a USB microphone: its gain starts at its own value (0 dB the first time). Unplug it: the built-in mic gets its previous gain back. After restarting the app, both values are still there.
3. **Start Position.** Seek to 1:00, record 10 s, stop. Review starts playing at 1:00 and shows "Take from 1:00, 0:10 long". Keep it: the Take is listed as "From 1:00".
4. **Countdown.** Turn the countdown on and tap Record: it shows 3, 2, 1, and recording starts with playback at the Start Position. Tap Cancel during the countdown: nothing records. The setting is still on after leaving and reopening the Session.
5. **Review.** **Record again** starts a new Take from the same Start Position, and the previous Take is gone. **Discard** leaves no Take. **Keep** lists the Take as "Take N". **Play again** and **Pause** work during Review.
   - Sing at a normal level into the built-in mic: the dB meter reaches about two thirds of the scale.
   - In Review your voice is clearly audible over the backing at the default balance (backing −20 dB).
   - Moving the balance slider changes the backing level live.
   - The balance setting is still there in the next Session, and it also applies when playing kept Takes.
6. **Alignment (loopback).** Load a song with clear transients and record 20 s while holding an earbud against the mic. In Review, the recorded copy should sit on the played audio. It may be off by a few ms, because only the engine's latency estimate is applied until Latency Calibration (#9).
7. **Xrun during a Take (ADR 13).** Record a loopback Take of 3 minutes while using the phone heavily: scroll, switch apps, lock and unlock. Check `adb logcat -s CroonvoxEngine` for `frames corrected after output xruns`. Play the kept Take: it must stay aligned to the end, with no growing offset after a glitch.
8. **Stored Takes.** Play a kept Take from the list: it plays against the Backing Audio from its Start Position and stops at its end. Rename it, then delete it after confirming. Restart the app: the remaining Takes are listed and still play.
9. **End of song.** Start recording 5 s before the end: Review opens by itself when the song ends.
10. **Route change.** Unplug the headphones while recording: the app doesn't crash, the recording ends and Review shows the part recorded so far. Playback and recording work again on the new route.
11. **Leaving the Session.** Press Back during playback: audio stops. The Track detail screen plays again with its normal controls.
