# WFR Dash Bridge (Android app)

An Android app that connects to Android Auto's own **head unit server** on the same phone, re-encodes the picture for the 450 dash and streams it there.

## Setup on the phone

1. **Install the app.**
2. **Turn on Android Auto's developer mode.** Open Android Auto's settings, tap **Version** 10 times and accept.
3. **Start the head unit server.** In Android Auto settings, open the ⋮ menu and choose **Start head unit server**. Repeat this after every phone restart.
4. **Check the driver seat.** Android Auto > Advanced > **Driver seat location** must match the app's seat setting (left by default).
5. **Start the bridge.** Open **WFR Dash Bridge**, allow location / nearby devices and notifications, check the settings, and tap **Start bridge**.
   - The first time, Android shows a picker for the dash's Wi-Fi (`RE_*`). Choose it.
   - After that the exact name and access point are saved, and it joins without asking.
6. **Ride.** The screen can be off. The bridge runs as a foreground service with a notification; stop it from the notification or the app.


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
