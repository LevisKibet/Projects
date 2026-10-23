# PalmTunes

A client-side Forge mod for **Minecraft 1.20.1** that puts a glass music panel in your hand.

While you play, a small pulse sits at the top of the screen: bars that move in time while your music
plays, settle when it is paused, and drift in a calm idle wave (with a hint to open a music app) when
nothing can be found. The bars are a rhythm animation, not a real spectrum; Minecraft cannot hear other programs.

Press **M**. The camera flies out of your eyes into a Warframe-style three-quarter view from your left,
your right arm lifts palm-up, and a frosted glass panel hovers above it showing what your computer is playing right now: cover art, title,
artist, progress, and previous / play-pause / next. Click the buttons, drag the progress bar to
seek, or use <kbd>Space</kbd> and the <kbd>←</kbd>/<kbd>→</kbd> keys. Press **M** or **Esc** to put your hand down.

PalmTunes does not talk to Spotify, Apple Music, YouTube Music or Deezer directly and never asks
you to log in. It reads the media session your operating system already knows about, the same
one behind your keyboard's media keys.

## Platform support

| OS | How it works | What you need |
|----|--------------|---------------|
| **Windows 10/11** | System Media Transport Controls through a hidden Windows PowerShell | Nothing. Spotify, Apple Music, Deezer, and YouTube Music / SoundCloud / Deezer web in Chrome, Edge, Firefox, Brave all show up. |
| **Linux** | MPRIS over D-Bus via `playerctl` | `sudo apt install playerctl` (or your distro's equivalent). Flatpak/Snap launchers need D-Bus access to `org.mpris.MediaPlayer2.*`. |
| **macOS** | AppleScript for **Spotify** and **Music** (Apple Music); optional `nowplaying-cli` for the rest | macOS asks once to allow control of Spotify/Music (System Settings → Privacy & Security → Automation). For browsers: `brew install nowplaying-cli`. |

**macOS caveat:** Apple has no public "now playing" API, and the private one that `nowplaying-cli`
uses was locked down in recent macOS releases. Spotify and Apple Music work reliably through
AppleScript; browser-based players may not show up on your macOS version.

## Building

You need **JDK 17** (Gradle can download it for you).

```bash
./gradlew build          # jar ends up in build/libs/
./gradlew runClient      # start a dev client with the mod loaded
./gradlew test           # unit tests for the media layer
```

If you only have a newer JDK and cannot download 17, add `-PuseCurrentJdk`; the bytecode is
still Java 17.

Drop `build/libs/palmtunes-0.1.0.jar` into your `mods` folder (Forge 47.x for 1.20.1). It is
client-only; do not install it on a server.

## Tuning the look

The pose and camera are driven by numbers, because they are far quicker to nudge than to
recompile. Edit `config/palmtunes-client.toml` and reopen the panel:

* `[arm]` `pitch`, `yaw`, `roll`: how the arm is raised, swung and twisted (radians).
* `[camera]` `forward` (negative = behind you), `side` (negative = your left), `height`, `lookAhead`, `lookSide`, `lookHeight`, `transitionTicks`: the shot and how long the move takes.
* `[hud]` `enabled`, `trackText` (ALWAYS / ON_CHANGE / NEVER), `idleHint`, `scale`: the pulse at the top.
* `[panel]` `width`, `gap`, `offsetX`, `push`, `angle` (the slant), `depthBias`, `palmGlow`: panel size and placement. The config is read at start-up; restart the game after editing.
* `[media]` `pollIntervalMs`, `fetchRemoteArt`.

Default key is **M**; rebind it under Controls → PalmTunes.

## How it is put together

```
com.palmtunes
├── media/                 no Minecraft code: easy to test
│   ├── MediaManager        polls the backend while the panel is open, publishes MediaSnapshot
│   ├── backend/            WindowsBackend (PowerShell + SMTC), LinuxBackend (playerctl), MacBackend
│   ├── ArtFetcher/Decoder  cover art -> rounded 128px square + accent colour
│   └── …
├── client/
│   ├── PalmController      open/close state and animation, camera-mode bookkeeping
│   ├── PalmScreen          invisible Screen: frees the cursor, receives clicks and keys
│   ├── PalmLayer           render layer on the player: finds the palm in camera space
│   ├── PalmPanelRenderer   draws the glass panel there and publishes its screen rectangle
│   ├── PalmInteraction     mouse -> panel coordinates -> media commands
│   ├── GlassTextures       every texture is painted with Java2D at start-up
│   └── PalmPose / PalmCameraController
└── mixin/
    ├── PlayerModelMixin    raises the right arm after vanilla poses it
    └── CameraMixin         moves the camera (not just its angles) over the shoulder
```

The panel is drawn in camera space, so it always faces you and mouse picking is just a rectangle
test against the projected corners.

## Known limits

* Only the **local** player's arm changes; other players do not see your palm.
* The raised arm is a straight Minecraft arm, so the "palm" is the inner face of the forearm.
* Whatever item is in your right hand is still drawn at the hand.
* Browser tabs are identified by browser, not site ("Playing in Chrome"), because the OS does not say which tab.
* The pulse polls your player about every 1.5 s while you are in a world; turn it off with `[hud] enabled = false`.
* No volume control yet (that is a different, per-OS API).

## Cinematic camera
After `[cinematic] afkSeconds` without input (and no hostile within `hostileRadius`), the camera films flowers, lanterns, placed items, friendly mobs, and wide overhead/sky/crane shots; only the visualizer stays on screen. Any input or a nearby hostile ends it. Focus shots add depth of field and motion blur (shaders ported from AmbientMotion). Test with `/palmtunes cinematic`.

## Browser playback on macOS
YouTube, YouTube Music, Spotify Web, SoundCloud, Deezer etc. in Chrome, Brave, Edge, Arc, Vivaldi or Safari are read when no native app is playing. Enable *View > Developer > Allow JavaScript from Apple Events* (Safari: *Develop* menu) once, and allow the Automation prompt.

## Shuffle, repeat and up-next
The panel has shuffle and repeat buttons (keys S and R) and an "Up next" list. Support depends on the source: Spotify and Apple Music (macOS) give shuffle/repeat, Apple Music also a queue (when not shuffled); YouTube Music in a browser gives all three; YouTube shows its suggested next videos; Windows and Linux give shuffle/repeat where the player exposes them. Where a source doesn't share something, that control is dimmed.
