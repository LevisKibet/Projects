package com.palmtunes.media.backend;

import com.palmtunes.media.MediaBackend;
import com.palmtunes.media.MediaCommand;
import com.palmtunes.media.MediaException;
import com.palmtunes.media.MediaInfo;
import com.palmtunes.media.PlaybackState;
import com.palmtunes.media.SourceNames;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Linux: MPRIS over D-Bus, through the {@code playerctl} command line tool.
 * <p>
 * Spotify, VLC, Rhythmbox, Deezer/YouTube Music desktop wrappers and every Chromium/Firefox
 * window implement MPRIS, so this covers them. playerctl is in every distro's package manager.
 */
public final class LinuxBackend implements MediaBackend {

    private static final String SEP = "\u001f";
    private static final String FORMAT = String.join(SEP,
            "{{playerInstance}}", "{{status}}", "{{title}}", "{{artist}}", "{{album}}",
            "{{mpris:artUrl}}", "{{mpris:length}}", "{{position}}");

    private boolean checked;
    private String selectedInstance;
    private int lastRepeat = -1;

    @Override
    public String name() {
        return "Linux MPRIS (playerctl)";
    }

    @Override
    public Optional<MediaInfo> poll() throws MediaException {
        ensurePlayerctl();
        ProcessUtil.Result result;
        try {
            result = ProcessUtil.run(List.of("playerctl", "--all-players", "metadata", "--format", FORMAT), 3000);
        } catch (IOException e) {
            throw MediaException.permanent("playerctl not found. Install it (for example: sudo apt install playerctl)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw MediaException.transientFailure("Interrupted");
        }
        if (!result.ok()) {
            // playerctl exits 1 with "No players found" when nothing is running.
            selectedInstance = null;
            return Optional.empty();
        }
        Optional<Candidate> best = pick(parse(result.output()));
        if (best.isEmpty()) {
            selectedInstance = null;
            return Optional.empty();
        }
        selectedInstance = best.get().instance;
        MediaInfo info = best.get().info;
        lastRepeat = -1;
        try {
            String sh = ProcessUtil.run(List.of("playerctl", "--player=" + selectedInstance, "shuffle"), 1500).output().trim();
            String lp = ProcessUtil.run(List.of("playerctl", "--player=" + selectedInstance, "loop"), 1500).output().trim();
            int shuffle = sh.equalsIgnoreCase("on") ? 1 : sh.equalsIgnoreCase("off") ? 0 : -1;
            int repeat = lp.equalsIgnoreCase("playlist") ? 1 : lp.equalsIgnoreCase("track") ? 2 : lp.equalsIgnoreCase("none") ? 0 : -1;
            lastRepeat = repeat;
            info = new MediaInfo(info.title(), info.artist(), info.album(), info.sourceApp(), info.state(),
                    info.positionMs(), info.durationMs(), info.artUrl(), info.artBytes(), shuffle, repeat, List.of());
        } catch (IOException e) {
            // keep the plain info
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.of(info);
    }

    @Override
    public void send(MediaCommand command, long argMs) throws MediaException {
        ensurePlayerctl();
        List<String> cmd = new ArrayList<>(List.of("playerctl"));
        if (selectedInstance != null) {
            cmd.add("--player=" + selectedInstance);
        }
        switch (command) {
            case PLAY_PAUSE -> cmd.add("play-pause");
            case NEXT -> cmd.add("next");
            case PREVIOUS -> cmd.add("previous");
            case TOGGLE_SHUFFLE -> {
                cmd.add("shuffle");
                cmd.add("Toggle");
            }
            case CYCLE_REPEAT -> {
                cmd.add("loop");
                cmd.add(lastRepeat == 0 ? "Playlist" : lastRepeat == 1 ? "Track" : "None");
            }
            case SEEK -> {
                cmd.add("position");
                cmd.add(String.format(java.util.Locale.ROOT, "%.3f", Math.max(0, argMs) / 1000.0));
            }
        }
        try {
            ProcessUtil.Result r = ProcessUtil.run(cmd, 3000);
            if (!r.ok()) {
                throw MediaException.transientFailure(r.output().trim());
            }
        } catch (IOException e) {
            throw MediaException.permanent("playerctl not found. Install it (for example: sudo apt install playerctl)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void ensurePlayerctl() throws MediaException {
        if (checked) {
            return;
        }
        if (!ProcessUtil.canStart(List.of("playerctl", "--version"))) {
            throw MediaException.permanent("playerctl not found. Install it (for example: sudo apt install playerctl)");
        }
        checked = true;
    }

    // ------------------------------------------------------------------------------------------
    // Parsing (package-private for tests)
    // ------------------------------------------------------------------------------------------

    record Candidate(String instance, MediaInfo info) {
    }

    static List<Candidate> parse(String output) {
        List<Candidate> out = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split(SEP, -1);
            if (f.length < 8) {
                continue;
            }
            String instance = f[0].trim();
            PlaybackState state = PlaybackState.parse(f[1]);
            long lengthMs = micros(f[6]);
            long positionMs = micros(f[7]);
            String artUrl = f[5].isBlank() ? null : f[5].trim();
            MediaInfo info = new MediaInfo(f[2], f[3], f[4], SourceNames.friendly(instance),
                    state, positionMs, lengthMs, artUrl, null);
            out.add(new Candidate(instance, info));
        }
        return out;
    }

    /** Prefer a player that is actually playing, then a paused one with something loaded. */
    static Optional<Candidate> pick(List<Candidate> candidates) {
        Candidate paused = null;
        Candidate any = null;
        for (Candidate c : candidates) {
            switch (c.info.state()) {
                case PLAYING -> {
                    return Optional.of(c);
                }
                case PAUSED -> {
                    if (paused == null) paused = c;
                }
                default -> {
                    if (any == null && !c.info.title().isBlank()) any = c;
                }
            }
        }
        if (paused != null) return Optional.of(paused);
        return Optional.ofNullable(any);
    }

    private static long micros(String s) {
        try {
            long v = Long.parseLong(s.trim());
            return v < 0 ? -1 : v / 1000;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
