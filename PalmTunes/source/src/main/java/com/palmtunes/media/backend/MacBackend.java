package com.palmtunes.media.backend;

import com.palmtunes.media.MediaBackend;
import com.palmtunes.media.MediaCommand;
import com.palmtunes.media.MediaException;
import com.palmtunes.media.MediaInfo;
import com.palmtunes.media.PlaybackState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * macOS.
 * <p>
 * Apple does not offer a supported public API for "whatever is playing", so this backend is a
 * stack of the options that actually work:
 * <ol>
 *   <li><b>Spotify</b> and <b>Music</b> (Apple Music) through their AppleScript dictionaries.
 *       Reliable, but macOS asks once for Automation permission.</li>
 *   <li><b>nowplaying-cli</b> (optional, {@code brew install nowplaying-cli}) for everything else,
 *       such as YouTube Music or Deezer in a browser. It relies on a private system framework that
 *       Apple has locked down in recent macOS releases, so it may report nothing on your version.</li>
 * </ol>
 * Browser tabs (YouTube, YouTube Music, Spotify Web...) are read by {@link BrowserTabs} when no app is playing.
 */
public final class MacBackend implements MediaBackend {

    private static final String SEP = "\u001f";

    private enum Source { SPOTIFY, MUSIC, BROWSER, NOW_PLAYING }

    private record Candidate(Source source, MediaInfo info) {
    }

    private Source selected;
    private BrowserTabs.Tab selectedTab;
    private boolean jsDenied;
    private String nowPlayingCli;
    private boolean nowPlayingSearched;

    private String musicArtKey = "";
    private String musicArtUrl;
    private boolean permissionDenied;

    @Override
    public String name() {
        return "macOS (Spotify/Music/browser-tab scripting + nowplaying-cli)";
    }

    @Override
    public Optional<MediaInfo> poll() throws MediaException {
        List<Candidate> candidates = new ArrayList<>();
        permissionDenied = false;

        queryApp(Source.SPOTIFY, "Spotify", spotifyScript()).ifPresent(candidates::add);
        queryApp(Source.MUSIC, "Music", musicScript()).ifPresent(candidates::add);

        boolean anyPlaying = candidates.stream().anyMatch(c -> c.info.state() == PlaybackState.PLAYING);
        BrowserTabs.Tab browserTab = null;
        if (!anyPlaying) {
            browserTab = queryBrowsers();
            if (browserTab != null) {
                candidates.add(new Candidate(Source.BROWSER, browserTab.info()));
                anyPlaying = browserTab.info().state() == PlaybackState.PLAYING;
            }
        }
        if (!anyPlaying) {
            queryNowPlaying().ifPresent(np -> {
                boolean duplicate = candidates.stream().anyMatch(c -> c.info.title().equals(np.info.title()));
                if (!duplicate) candidates.add(np);
            });
        }

        Candidate best = null;
        for (Candidate c : candidates) {
            if (c.info.state() == PlaybackState.PLAYING) {
                best = c;
                break;
            }
            if (best == null && c.info.state() == PlaybackState.PAUSED) {
                best = c;
            }
        }
        if (best == null && !candidates.isEmpty()) {
            best = candidates.get(0);
        }

        if (best == null) {
            selected = null;
            selectedTab = null;
            if (jsDenied) {
                throw MediaException.permanent("Your browser has a music tab open, but blocks reading it. In the browser's menu enable "
                        + "View > Developer > Allow JavaScript from Apple Events (Safari: Develop menu), then reopen the panel.");
            }
            if (permissionDenied) {
                throw MediaException.permanent("macOS blocked control of your music app. Allow it in System Settings > "
                        + "Privacy & Security > Automation, then reopen the panel.");
            }
            return Optional.empty();
        }
        selected = best.source;
        selectedTab = best.source == Source.BROWSER ? browserTab : null;
        if (best.source == Source.MUSIC) {
            return Optional.of(withMusicArt(best.info));
        }
        return Optional.of(best.info);
    }

    @Override
    public void send(MediaCommand command, long argMs) throws MediaException {
        Source target = selected;
        if (target == null) {
            return;
        }
        double seconds = Math.max(0, argMs) / 1000.0;
        try {
            switch (target) {
                case SPOTIFY -> runScript(appCommand("Spotify", command, seconds));
                case MUSIC -> runScript(appCommand("Music", command, seconds));
                case BROWSER -> {
                    if (selectedTab != null) runScript(BrowserTabs.commandScript(selectedTab, command, seconds));
                }
                case NOW_PLAYING -> {
                    String cli = findNowPlayingCli();
                    if (cli == null) return;
                    List<String> cmd = new ArrayList<>(List.of(cli));
                    switch (command) {
                        case PLAY_PAUSE -> cmd.add("togglePlayPause");
                        case NEXT -> cmd.add("next");
                        case PREVIOUS -> cmd.add("previous");
                        case SEEK -> {
                            cmd.add("seek");
                            cmd.add(String.format(Locale.ROOT, "%.3f", seconds));
                        }
                        default -> {
                            return;
                        }
                    }
                    ProcessUtil.run(cmd, 3000);
                }
            }
        } catch (IOException e) {
            throw MediaException.transientFailure(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------
    // AppleScript apps
    // ------------------------------------------------------------------------------------------

    private Optional<Candidate> queryApp(Source source, String processName, List<String> script) {
        try {
            if (!isRunning(processName)) {
                return Optional.empty();
            }
            ProcessUtil.Result r = runScript(script);
            if (!r.ok()) {
                if (r.output().contains("-1743")) {
                    permissionDenied = true;
                }
                return Optional.empty();
            }
            MediaInfo info = parseAppLine(processName, r.output().trim());
            return info == null ? Optional.empty() : Optional.of(new Candidate(source, info));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Looks through the open browsers' music tabs; prefers a playing one. */
    private BrowserTabs.Tab queryBrowsers() {
        jsDenied = false;
        BrowserTabs.Tab best = null;
        for (BrowserTabs.Browser b : BrowserTabs.BROWSERS) {
            try {
                if (!isRunning(b.app())) {
                    continue;
                }
                ProcessUtil.Result r = runScript(BrowserTabs.listScript(b));
                if (!r.ok()) {
                    if (r.output().contains("-1743")) {
                        permissionDenied = true;
                    }
                    continue;
                }
                for (BrowserTabs.Tab t : BrowserTabs.parse(b, r.output())) {
                    if (t.jsDenied()) {
                        jsDenied = true;
                    }
                    if (t.info() == null) {
                        continue;
                    }
                    if (best == null || (t.info().state() == PlaybackState.PLAYING
                            && best.info().state() != PlaybackState.PLAYING)) {
                        best = t;
                    }
                }
            } catch (IOException e) {
                // browser not scriptable; ignore
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return best;
            }
        }
        return best;
    }

    private static boolean isRunning(String processName) throws IOException, InterruptedException {
        return ProcessUtil.run(List.of("/usr/bin/pgrep", "-x", processName), 2000).ok();
    }

    private static ProcessUtil.Result runScript(List<String> lines) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("/usr/bin/osascript");
        for (String line : lines) {
            cmd.add("-e");
            cmd.add(line);
        }
        return ProcessUtil.run(cmd, 4000);
    }

    private static List<String> spotifyScript() {
        return List.of(
                "tell application \"Spotify\"",
                "if player state is stopped then return \"\"",
                "set d to character id 31",
                "return (player state as text) & d & (name of current track) & d & (artist of current track) & d & "
                        + "(album of current track) & d & (duration of current track as text) & d & "
                        + "(player position as text) & d & (artwork url of current track) & d & "
                        + "(shuffling as text) & d & (repeating as text) & d & \"\"",
                "end tell");
    }

    private static List<String> musicScript() {
        return List.of(
                "tell application \"Music\"",
                "if player state is stopped then return \"\"",
                "set d to character id 31",
                "set q to \"\"",
                "try",
                "if shuffle enabled is false then",
                "set pl to current playlist",
                "set idx to index of current track",
                "set n to count of tracks of pl",
                "repeat with i from (idx + 1) to (idx + 4)",
                "if i <= n then set q to q & (name of track i of pl) & \" - \" & (artist of track i of pl) & (character id 30)",
                "end repeat",
                "end if",
                "end try",
                "return (player state as text) & d & (name of current track) & d & (artist of current track) & d & "
                        + "(album of current track) & d & (duration of current track as text) & d & "
                        + "(player position as text) & d & \"\" & d & (shuffle enabled as text) & d & (song repeat as text) & d & q",
                "end tell");
    }

    private static List<String> appCommand(String app, MediaCommand command, double seconds) {
        if (command == MediaCommand.TOGGLE_SHUFFLE) {
            return List.of("tell application \"" + app + "\" to set " + ("Spotify".equals(app) ? "shuffling to not shuffling"
                    : "shuffle enabled to not shuffle enabled"));
        }
        if (command == MediaCommand.CYCLE_REPEAT) {
            if ("Spotify".equals(app)) {
                return List.of("tell application \"Spotify\" to set repeating to not repeating");
            }
            return List.of("tell application \"Music\"", "if song repeat is off then", "set song repeat to all",
                    "else if song repeat is all then", "set song repeat to one", "else", "set song repeat to off",
                    "end if", "end tell");
        }
        String verb = switch (command) {
            case PLAY_PAUSE -> "playpause";
            case NEXT -> "next track";
            case PREVIOUS -> "previous track";
            case SEEK -> "set player position to " + String.format(Locale.ROOT, "%.3f", seconds);
            default -> "playpause";
        };
        return List.of("tell application \"" + app + "\" to " + verb);
    }

    /** Spotify exports duration in milliseconds, Music in seconds. */
    static MediaInfo parseAppLine(String app, String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] f = line.split(SEP, -1);
        if (f.length < 7) {
            return null;
        }
        PlaybackState state = PlaybackState.parse(f[0]);
        double duration = number(f[4]);
        double position = number(f[5]);
        long durationMs = duration < 0 ? -1 : Math.round("Spotify".equals(app) ? duration : duration * 1000.0);
        long positionMs = position < 0 ? -1 : Math.round(position * 1000.0);
        String artUrl = f[6].isBlank() ? null : f[6].trim();
        int shuffle = f.length > 7 ? bool(f[7]) : -1;
        int repeat = -1;
        if (f.length > 8) {
            String r = f[8].trim().toLowerCase(Locale.ROOT);
            repeat = switch (r) {
                case "true", "all" -> 1;
                case "one" -> 2;
                case "false", "off" -> 0;
                default -> -1;
            };
        }
        List<String> queue = new ArrayList<>();
        if (f.length > 9) {
            for (String q : f[9].split("\u001e")) {
                if (!q.isBlank()) {
                    queue.add(q.trim());
                }
            }
        }
        return new MediaInfo(f[1], f[2], f[3], "Spotify".equals(app) ? "Spotify" : "Apple Music",
                state, positionMs, durationMs, artUrl, null, shuffle, repeat, queue);
    }

    private static int bool(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.equals("true") ? 1 : t.equals("false") ? 0 : -1;
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Apple Music cover art: the dictionary only hands out raw bytes, so write them to a file.
    // ------------------------------------------------------------------------------------------

    private MediaInfo withMusicArt(MediaInfo info) {
        String key = info.trackKey();
        if (!key.equals(musicArtKey)) {
            musicArtKey = key;
            musicArtUrl = exportMusicArt();
        }
        if (musicArtUrl == null) {
            return info;
        }
        return info.withArt(musicArtUrl, null);
    }

    private String exportMusicArt() {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "palmtunes");
            Files.createDirectories(dir);
            Path file = dir.resolve("music-art-" + (System.nanoTime() & 0xFFFFFF) + ".bin");
            file.toFile().deleteOnExit();
            List<String> cmd = new ArrayList<>(List.of(
                    "/usr/bin/osascript",
                    "-e", "on run argv",
                    "-e", "set outPath to item 1 of argv",
                    "-e", "tell application \"Music\"",
                    "-e", "try",
                    "-e", "set artData to raw data of artwork 1 of current track",
                    "-e", "on error",
                    "-e", "return \"none\"",
                    "-e", "end try",
                    "-e", "end tell",
                    "-e", "try",
                    "-e", "set f to open for access (POSIX file outPath) with write permission",
                    "-e", "set eof f to 0",
                    "-e", "write artData to f",
                    "-e", "close access f",
                    "-e", "return \"ok\"",
                    "-e", "on error",
                    "-e", "try",
                    "-e", "close access (POSIX file outPath)",
                    "-e", "end try",
                    "-e", "return \"none\"",
                    "-e", "end try",
                    "-e", "end run",
                    file.toString()));
            ProcessUtil.Result r = ProcessUtil.run(cmd, 4000);
            if (r.ok() && r.output().trim().equals("ok") && Files.size(file) > 0) {
                return file.toUri().toString();
            }
        } catch (IOException e) {
            // no art; the UI shows a placeholder
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // nowplaying-cli (optional)
    // ------------------------------------------------------------------------------------------

    private Optional<Candidate> queryNowPlaying() {
        String cli = findNowPlayingCli();
        if (cli == null) {
            return Optional.empty();
        }
        try {
            ProcessUtil.Result r = ProcessUtil.run(
                    List.of(cli, "get", "title", "artist", "album", "duration", "elapsedTime", "playbackRate"), 3000);
            if (!r.ok()) {
                return Optional.empty();
            }
            MediaInfo info = parseNowPlaying(r.output());
            return info == null ? Optional.empty() : Optional.of(new Candidate(Source.NOW_PLAYING, info));
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    static MediaInfo parseNowPlaying(String output) {
        String[] lines = output.split("\\R", -1);
        if (lines.length < 6) {
            return null;
        }
        String title = nullable(lines[0]);
        if (title == null) {
            return null;
        }
        String artist = nullable(lines[1]);
        String album = nullable(lines[2]);
        double duration = number(nullable(lines[3]) == null ? "-1" : lines[3]);
        double elapsed = number(nullable(lines[4]) == null ? "-1" : lines[4]);
        double rate = number(nullable(lines[5]) == null ? "0" : lines[5]);
        PlaybackState state = rate > 0 ? PlaybackState.PLAYING : PlaybackState.PAUSED;
        return new MediaInfo(title, artist, album, "",
                state, elapsed < 0 ? -1 : Math.round(elapsed * 1000), duration <= 0 ? -1 : Math.round(duration * 1000),
                null, null);
    }

    private static String nullable(String s) {
        String t = s.trim();
        return t.isEmpty() || t.equals("null") || t.equals("(null)") ? null : t;
    }

    private String findNowPlayingCli() {
        if (nowPlayingSearched) {
            return nowPlayingCli;
        }
        nowPlayingSearched = true;
        List<String> dirs = new ArrayList<>(List.of("/opt/homebrew/bin", "/usr/local/bin", "/opt/local/bin"));
        String path = System.getenv("PATH");
        if (path != null) {
            dirs.addAll(List.of(path.split(":")));
        }
        for (String dir : dirs) {
            Path candidate = Path.of(dir, "nowplaying-cli");
            if (Files.isExecutable(candidate)) {
                nowPlayingCli = candidate.toString();
                break;
            }
        }
        return nowPlayingCli;
    }
}
