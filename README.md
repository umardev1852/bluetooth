# BT Walkie

Two-way voice calls between two Android phones over a direct Bluetooth link.
No SIM, no Wi-Fi, no internet. It only works while the phones are within Bluetooth range
(roughly 10 m indoors, more in open space).

## Build it

1. Unzip the project and open the `BtWalkie` folder in Android Studio (File > Open).
2. Wait for Gradle sync to finish (it downloads what it needs on first run).
3. Plug in an Android phone with USB debugging on and press Run, or use
   Build > Build APK(s) to get an APK you can copy to both phones.

Install the app on BOTH phones.

## Use it

1. On both phones, pair them in Settings > Bluetooth.
2. Open BT Walkie on both and allow the microphone and Bluetooth permissions.
3. Phone A: tap "Wait for a call".
4. Phone B: tap "Call a paired phone" and choose phone A.
5. Talk. Use "Mute" or "Use loudspeaker" as needed. Tap "Hang up" to stop.

## Notes and limits

- Keep the app open and the screen on during a call (the screen stays on automatically).
- Without headphones, hold the phone to your ear (default) or use loudspeaker.
  Loudspeaker can cause echo on some phones.
- Not a normal phone call: it does not use your number or the phone dialer.
- Ideas for next steps: run the call in a foreground service so it survives the screen
  turning off, add a ringing sound, add audio compression (Opus), add a nicer design.
