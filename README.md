# BT Walkie

Voice calls between two Android phones over a direct Bluetooth link.
No SIM, no Wi-Fi, no internet. Works within Bluetooth range (about 10 m indoors).

## How to use

1. Install the APK on both phones and open the app once. Allow the microphone,
   "Nearby devices" and notification permissions.
2. Pair the two phones in Settings > Bluetooth (only needed once).
3. Keep Bluetooth on. On one phone, open the app and tap **Call** next to the other phone.
4. The other phone rings (even if the app is closed or the screen is off) and shows
   **Accept** / **Decline**.
5. During a call: Mute, Speaker, Hang up. The call keeps going when the screen turns off.

Tip: tap "Allow running in background" in the app so the phone does not stop the app to save battery.

## What is inside

- `CallService` - background service: waits for calls, rings, handshake (accept / decline / busy),
  voice in both directions (16 kHz, u-law compressed, 20 ms frames, jitter buffer, echo
  cancel / noise reduction where the phone supports them).
- `MainActivity` - the screens (paired phones list, calling, incoming, in-call).
- `Protocol` / `RelayClient` - message format and the "ask a phone which phones it is paired with" request used by long range calls.
- `CallHub` - shared state between the service and the screens.
- `BootReceiver` - starts waiting for calls again after the phone restarts.

## Long range (through other phones)

Bluetooth reaches about 10 m. To reach further, use phones in between as relays:

```
You  ---10 m---  Phone B  ---10 m---  Phone C
```

1. Phone B must be paired with you, and with Phone C. All phones need this version of the app.
2. On your phone tap **Long range: call through another phone**, choose Phone B, then choose Phone C
   from the list of phones paired with B. Tap **Go further** instead to continue through C to a phone D
   (up to 3 phones in the middle).
3. Phone C rings and shows Accept / Decline. Phone B needs no tapping: its screen shows "Passing the
   call along" and it stays busy until the call ends.

Relayed calls use 8 kHz audio (telephone quality) to save Bluetooth bandwidth, and every extra phone
adds a little delay. The phone in the middle must keep the app running (see the background tips above)
and cannot make its own calls meanwhile.

## Build

GitHub builds the APK automatically on every push (Actions tab > latest run > Artifacts).
The APK is signed with a fixed key (`app/debug.keystore`), so a new version installs over the old one.
