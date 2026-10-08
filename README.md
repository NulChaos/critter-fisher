# Critter Fisher

Fishing Contest bot for Clash of Critters with a floating overlay.
Runs on the phone: screen capture (MediaProjection) to see the game, an accessibility
service to tap. No root, no adb, nothing inside the game is modified.

Every push to `main` builds a signed APK and publishes it under **Releases**.

## Setup
1. Install the APK from the latest release.
2. Open Critter Fisher → *Allow display over other apps*.
3. *Turn on "Critter Fisher taps"* in Accessibility. If Android says it's a restricted
   setting: App info → ⋮ → *Allow restricted settings*, then try again.
4. *Start floating panel*, choose **entire screen**.
5. Open the Fishing Contest and press ▶ on the panel.

## Panel
- ▶ / ❚❚ start/pause · ⚙ live settings · ✕ quit
- Mini bar: yellow = zone, white = aim window, cyan = marker, pink = where the last tap landed
- Settings: tap latency, aim inset, reel-in tap speed, bait on/off, bait recheck time,
  on-screen markers
