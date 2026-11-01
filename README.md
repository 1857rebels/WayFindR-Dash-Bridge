# WFR Dash Bridge (Android app)

An Android app that does the Pi bridge's job on the phone itself. It needs no Pi and no wireless Android Auto dongle.
It connects to Android Auto's own **head unit server** on the same phone, re-encodes the picture for the dash and streams it there.

| Pi bridge | This app |
|---|---|
| Wireless AA dongle → `aa-headless` (aasdk) | `AaSession` + `AaTransport`: a head unit talking to `127.0.0.1:5277` |
| ffmpeg: decode, crop, blur fill, status strip, x264 | `VideoPipeline`: hardware decoder → OpenGL ES composite → hardware H.264 encoder |
| ffmpeg's probe output for "Re-centre" | `VideoPipeline.probe()`: `glReadPixels` of the same strip twice a second |
| `dash_wifi.py` (nmcli) | `DashWifi`: joins `RE_*` as a per-app network; mobile data stays up for Maps |
| `dash_bridge.py`, `k1g.py` | `DashSession`, `K1g`, `Rtp` (Kotlin ports; packets checked byte for byte against `k1g.py`) |
| `/etc/default/wayfindr-pi` | The app's settings screen (`Config`) |

Everything the Pi does is carried over:
- **Layout:** the fill layout with the blurred background, the left or right seat, and `FILL_LAYOUT` (default for the left seat: 0.876,87,41).
- **Status strip:** the clock, signal and battery strip.
- **Route card:** the dash's route card with the turn circle, which the joystick needs.
- **Joystick:**
  - RIGHT zooms in, LEFT zooms out, DOWN re-centres (automatic Re-centre detection).
  - In the dash's music mode: next, previous and play/pause.
  - UP switches to default nav mode with Maps; DOWN switches to Alternate view with Music (Spotify). Both are saved, so the app screen and the next reconnect follow. Codes 0x06 / 0x07, seen in the dash's music mode on 2026-11-01; music-mode presses also arrive in Alternate view.
- **Now playing:** sent to the dash's music widget.
- **Audio:** only system audio is offered, so music and navigation voice stay on the phone and go to your Bluetooth intercom.

New compared with the Pi:
- The dash's battery icon shows the phone's real battery level and charging state.
- When Android Auto isn't connected, a "Waiting for Android Auto" card is drawn into the same stream, so there's no second encoder.

## Status

**Not yet tried on the phone or the bike.** Checked on the laptop so far:
- **The APK builds.**
- **Dash packets** (route card, idle card, heartbeat, now playing, hostname, sequence byte) are byte-identical to the Pi's `k1g.py`. See `K1gTest`.
- **Android Auto protocol** (`AaSessionTest`). A fake phone, run as a TLS server, completes:
  - the version exchange and TLS handshake
  - service discovery: 6 channels; video 800x480, margins 240x180, dpi 110; touchscreen 560x300
  - channel open, video setup and focus
  - video frames and acks, including a 40 KB frame split over two encrypted frames
  - now playing, ping, a zoom-in tap at the right touchscreen point, the next-track key, and shutdown

Unknowns for the first real test:
1. Whether Android Auto's head unit server accepts a head unit from the same phone, and keeps projecting with the screen off. Headunit Reloaded's "self mode" suggests it does.
2. Whether the phone's hardware encoder output at 526x300 is accepted by the dash. Wayfindr already streams 526x300 from this phone, so it should be.
3. Whether the left-seat coordinates measured on the Pi still match. They should, as long as Android Auto lays out the same 560x300 at dpi 110.

## Setup on the phone

1. **Install the app.**
   ```bash
   adb install -r pi/hus-app/app/build/outputs/apk/debug/app-debug.apk
   ```
2. **Turn on Android Auto's developer mode.** Open Android Auto's settings, tap **Version** 10 times and accept.
3. **Start the head unit server.** In Android Auto settings, open the ⋮ menu and choose **Start head unit server**. Repeat this after every phone restart.
4. **Check the driver seat.** Android Auto > Advanced > **Driver seat location** must match the app's seat setting (left by default).
5. **Start the bridge.** Open **WFR Dash Bridge**, allow location / nearby devices and notifications, check the settings, and tap **Start bridge**.
   - The first time, Android shows a picker for the dash's Wi-Fi (`RE_*`). Choose it.
   - After that the exact name and access point are saved, and it joins without asking.
6. **Ride.** The screen can be off. The bridge runs as a foreground service with a notification; stop it from the notification or the app.

**Don't run the Pi at the same time.** Two head units on one dash would fight over it.

## Settings

| Setting | Default | Pi equivalent |
|---|---|---|
| Wi-Fi name starts with / password | `RE_` / your dash's password | `DASH_PREFIX` / `DASH_PSK` |
| Dash Wi-Fi name | filled in once seen | the SSID in `/run/wayfindr/dash-ssid` |
| Route card | `turn` | `DASH_IDLE_CARD` |
| Seat | left | `AA_SEAT` |
| Map layout `scale,x,y` | empty = 0.876,87,41 (left) / 0.846,100,49 (right) | `FILL_LAYOUT` |
| On the dash: Maps / Music | Maps | `AA_LAUNCH` (now switchable live) |
| View: Default nav mode / Alternate view | Default | — (Alternate: map at 0.900,39,35, route card `nosymbol` = no turn circle, so no joystick zoom; switchable live) |
| Clock / signal / battery strip | on | `DASH_STATUS=top` |
| Clock strip `x,y,size` (default nav, and again under Alternate view) | empty = the standard 186,4 at size 1.35 | — (top-left of the strip in the 526×300 frame; size 0.5–2.5 scales the 108×26 strip, 1 = Android Auto's own pixels; size can be left off) |
| Alternate view map layout `scale,x,y` | 0.900,39,35 | — |
| Video bitrate | 200000 | `VIDEO_BITRATE` |
| Head unit server port | 5277 | — |

Fixed values match the Pi's working setup: 4 fps, AA dpi 110, margins 240x180, system audio only, and Maps opened full screen 3 s after the first frame. To change them, edit `Config.kt`.

The Dash Map Sizer (https://claude.ai/artifact/Fqcr6ZfJr4n6bkdYDRFwtT) produces the same `scale,x,y` values for the layout fields, and the clock strip's `x,y,size`, for each view. Its last output line says which Settings field each value goes in.

## Switching between Maps and Music on the bike

Use the bike's joystick; the phone can stay in your pocket.

1. **Press the joystick centre.** The dash opens its music controls.
2. **Press DOWN for Music.** The dash switches to Alternate view and Android Auto opens Spotify's full-screen player.
3. **Press UP for Maps.** The dash switches back to default nav mode and Android Auto opens Google Maps.

While the dash's music controls are open, LEFT is previous track, RIGHT is next track, and play/pause works too.

Good to know:
- **Zoom and re-centre only work in default nav mode.** Alternate view has no turn circle on the dash, and without it the dash doesn't send map presses. Press UP to get back to Maps before zooming.
- **The choice sticks.** The app saves the view and the app, so the phone's screen shows the same choice, and Android Auto reopens it after a reconnect.
- **From the phone app.** Set **On the dash** (Maps / Music) and **View** (Default nav / Alternate) separately on the app screen. The joystick changes both at once.
- **UP and DOWN don't answer calls.** This app doesn't handle calls, so during an incoming call UP and DOWN still switch Maps and Music.

## Music and screen capture

- **Music (Spotify)** presses Android Auto's media button, which opens the last-used media app.
  - With Spotify playing, it opens its full-screen player; that was so in every test on 2026-11-01, even from Spotify's browse screen.
  - If Spotify ever shows a browse screen instead, the app looks for Spotify's green now-playing button at (622, 332, 44×44) and taps it, then the player opens.
  - It taps only when that spot is green: in the player the same spot is the repeat button.
  - Measured for the left seat only.
- **Capture Android Auto screen** saves Android Auto's whole 800×480 picture, before the dash crop, for measuring other apps. With no dash it turns video on for 15 s to take it.
  ```bash
  adb pull /sdcard/Android/data/com.wayfindr.hus/files/captures
  ```
- **adb test hooks** (`adb shell am broadcast -a com.wayfindr.hus.<ACTION>`):
  - `CAPTURE`
  - `TEST_APP --es app maps|media`
  - `TEST_TAP --ei x N --ei y N`
  - `TEST_VIDEO --ez on true|false`
  - `TEST_BUTTON --ei code N`: acts as if the dash sent joystick code N (6 = UP, 7 = DOWN)

## Heat and battery

Measured on the S25 Ultra on 2026-11-01:
- **Android Auto ignores the head unit's frame rate.** It sent about 51 fps on a ride and 25 fps at rest, even though the app asks for 30 and the dash shows 4.
- **Acknowledgements can't slow it down.** With acks held back, 272 of 300 frames still arrived before the previous ack.
- **The fix is video focus, set when the session starts.**
  - The app gives Android Auto video focus only while a dash session runs, and keeps it for 20 s after one ends.
  - With no dash, Android Auto draws and encodes nothing, but music info and input stay connected.
  - Focus can't be changed mid-session: given later, it starts no video; taken away, Android Auto ends the session. So the app reconnects to Android Auto (under 1 s) when the dash comes or goes.
- **CPU, as a share of one core:** Android Auto, Maps, the codecs and this app add up to about 40% with video on and about 3% with it off.
- **With no dash:** the app also encodes nothing, and it backs off its Wi-Fi scans to every 16 s.

On the dash, the ~40% stays, because Android Auto itself renders and encodes at full rate. Other things that help:
- Keep the screen off.
- Avoid fast-charging the phone in a closed mount.

Test switch, to give Android Auto video without a dash:
```bash
adb shell am broadcast -a com.wayfindr.hus.TEST_VIDEO --ez on true
```
Turn it off again with `--ez on false`.

## Logs

- The app shows the last lines of the log on screen.
- The log also goes to `bridge.log`, and to logcat with the tag `HusBridge`:
  ```bash
  adb pull /sdcard/Android/data/com.wayfindr.hus/files/bridge.log
  ```
  ```bash
  adb logcat -s HusBridge
  ```
- Every 30 s a `stats:` line gives AA frames decoded, frames encoded, frames sent to the dash and frames the dash acked.
- What to look for on a first run:
  - `aa: TLS handshake complete`, `aa: service discovery from …` and `aa: first video frame`: the phone accepted the head unit.
  - `dash: authenticated`, then `dash: decoded the first keyframe`: the dash accepted the stream.
  - `aa: nothing on 127.0.0.1:5277 …`: the head unit server isn't running (step 3).

## Build and test

```bash
cd pi/hus-app && ./gradlew assembleDebug
```
```bash
cd pi/hus-app && ./gradlew testDebugUnitTest
```

It's a standalone Gradle project (AGP 9.2.1, no libraries) using the Android SDK from `local.properties`. Like the rest of `pi/`, it is gitignored and kept local.

## Files

| File | What it does |
|---|---|
| `BridgeService.kt` | Foreground service; runs the Android Auto loop, the dash loop, the joystick mapping and the stats. |
| `MainActivity.kt` | The app screen: status, start/stop, On the dash / View toggles, capture, setup steps, settings, log. Settings save when you leave the screen. |
| `Ui.kt` | Colours and view builders for that screen (cards, segmented toggles, fields, buttons); no libraries. |
| `Config.kt` | Settings and the measured Android Auto coordinates (crop, side bar, zoom buttons, Re-centre strip). |
| `aa/AaTransport.kt` | Android Auto framing and TLS (`SSLEngine`, aasdk's head unit certificate). |
| `aa/AaSession.kt` | The head unit: channels, service discovery, video, sensors, mic, system audio, media status, touch and keys. |
| `aa/Pb.kt` | Minimal protobuf writer/reader (field numbers from aasdk_proto). |
| `video/VideoPipeline.kt` | Decoder → GL composite (blur fill, map, status strip, waiting card, Re-centre probe) → encoder. |
| `dash/K1g.kt`, `DashSession.kt`, `Rtp.kt`, `DashWifi.kt` | The dash side, ported from the Pi bridge. |

## App icon

The Wayfindr icon with the arrow recoloured orange and an orange **BRIDGE** band. `tools/build_icon.py` makes the adaptive icon layers from the main app's `ic_launcher_foreground` (converted to PNG):
```bash
python tools/build_icon.py <folder with ic_launcher_foreground.png> app/src/main/res
```
It writes `ic_launcher_foreground` and `ic_launcher_monochrome` (for Android's themed icons) in every `mipmap-*` density; the background is the navy `#050D19` in `values/colors.xml`.

## To do

### Turn, distance and ETA on the dash's own widgets

Show Android Auto's navigation details in the dash's turn circle and ETA box, as the Wayfindr app does. The map itself would still come from Android Auto.

**What Android Auto can send.** It has a navigation status channel for car instrument clusters (as in aasdk). This app doesn't offer that channel in service discovery yet, so nothing arrives today. The data only comes while Google Maps (or likely Waze) is navigating a route.

| Data | Message | Confidence |
|---|---|---|
| Navigation on / off | navigation status | Certain (aasdk) |
| Next turn: road, turn type, side, roundabout exit number, turn angle, small turn image | next-turn event | Certain (aasdk) |
| Distance and time to the next turn, plus a display value and unit | next-turn distance event | Certain (aasdk) |
| Distance left to the destination, ETA / time left | newer navigation state / current position messages | Likely in recent Android Auto versions; not yet seen from this phone |

**Mapping to the dash.** These use the same K1G fields as Wayfindr (see the main CLAUDE.md):

| Android Auto | Dash TLV |
|---|---|
| Turn type + side + angle | `05 02` turn glyph, using the codes in Wayfindr's `DashGlyph` |
| Distance to the turn | `05 04` / `05 06` (metres, or km×10) |
| Distance left | `05 09` / `05 46` |
| ETA | `05 08`, "HHMM" |

**Plan.**
1. Offer the navigation status channel and log every message. Then start a Google Maps route at home with the bridge running, and read which fields this phone actually sends, especially distance left and ETA.
2. Send the turn glyph and distance to the dash, then ETA and distance left if step 1 shows them.

**Risks to check on the bike.**
- The joystick only works while the turn circle shows a symbol. Real glyphs should keep one there while navigating, but this needs confirming.
- The Pi found the dash sensitive to which route card it gets, so switching from the idle card to a live route card needs a test.

**Fallback.** If Android Auto doesn't send ETA or distance left, Google Maps' navigation notification on the phone also shows them. The app could read it with notification access.
