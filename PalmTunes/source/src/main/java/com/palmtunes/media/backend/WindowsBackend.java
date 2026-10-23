package com.palmtunes.media.backend;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.palmtunes.media.MediaBackend;
import com.palmtunes.media.MediaCommand;
import com.palmtunes.media.MediaException;
import com.palmtunes.media.MediaInfo;
import com.palmtunes.media.PlaybackState;
import com.palmtunes.media.SourceNames;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Windows: reads the System Media Transport Controls, the same service that powers the volume
 * flyout's "now playing" card. Spotify, Apple Music, Deezer, and every Chromium/Firefox tab
 * (YouTube Music, SoundCloud...) publish there, so one backend covers them all.
 * <p>
 * The WinRT API is only reachable from Windows PowerShell 5.1, so we keep one hidden
 * {@code powershell.exe} alive and talk to it over a line protocol (see {@code smtc.ps1}).
 */
public final class WindowsBackend implements MediaBackend {

    private static final long STARTUP_TIMEOUT_MS = 20_000;
    private static final long REQUEST_TIMEOUT_MS = 6_000;

    private Process process;
    private OutputStream stdin;
    private BlockingQueue<String> lines;
    private Path scriptPath;
    private final Deque<String> recentOutput = new ArrayDeque<>();
    private int haveSeq = 0;

    @Override
    public String name() {
        return "Windows SMTC";
    }

    @Override
    public Optional<MediaInfo> poll() throws MediaException {
        String reply = request("poll " + haveSeq);
        ParsedState parsed = parseState(reply);
        if (parsed.seq >= 0 && parsed.artDelivered) {
            haveSeq = parsed.seq;
        }
        return Optional.ofNullable(parsed.info);
    }

    @Override
    public void send(MediaCommand command, long argMs) throws MediaException {
        String line = switch (command) {
            case PLAY_PAUSE -> "playpause";
            case NEXT -> "next";
            case PREVIOUS -> "prev";
            case SEEK -> "seek " + Math.max(0, argMs);
            case TOGGLE_SHUFFLE -> "shuffle";
            case CYCLE_REPEAT -> "repeat";
        };
        String reply = request(line);
        JsonObject obj = parseObject(reply);
        if (obj.has("error")) {
            throw MediaException.transientFailure(obj.get("error").getAsString());
        }
    }

    @Override
    public synchronized void close() {
        stopProcess();
    }

    // ------------------------------------------------------------------------------------------
    // Process handling
    // ------------------------------------------------------------------------------------------

    private String request(String line) throws MediaException {
        ensureRunning();
        try {
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            stopProcess();
            throw MediaException.transientFailure("PowerShell helper stopped: " + e.getMessage());
        }
        String reply = awaitJsonLine(REQUEST_TIMEOUT_MS);
        if (reply == null) {
            stopProcess();
            throw MediaException.transientFailure("PowerShell helper did not answer in time");
        }
        return reply;
    }

    private void ensureRunning() throws MediaException {
        if (process != null && process.isAlive()) {
            return;
        }
        stopProcess();
        try {
            if (scriptPath == null || !Files.exists(scriptPath)) {
                scriptPath = extractScript();
            }
            Path ps = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                    "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            String exe = Files.exists(ps) ? ps.toString() : "powershell.exe";
            ProcessBuilder pb = new ProcessBuilder(List.of(
                    exe, "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-File", scriptPath.toString()));
            pb.redirectErrorStream(true);
            process = pb.start();
        } catch (IOException e) {
            throw MediaException.permanent("Could not start Windows PowerShell: " + e.getMessage());
        }
        stdin = process.getOutputStream();
        lines = new LinkedBlockingQueue<>();
        recentOutput.clear();
        haveSeq = 0;
        startReader(process.getInputStream(), lines);

        String first = awaitJsonLine(STARTUP_TIMEOUT_MS);
        if (first == null || !first.contains("\"ready\"")) {
            String detail = String.join(" ", recentOutput);
            boolean dead = process != null && !process.isAlive();
            stopProcess();
            throw MediaException.permanent("Windows media API unavailable"
                    + (dead && !detail.isBlank() ? ": " + abbreviate(detail, 160) : ""));
        }
    }

    private void startReader(InputStream in, BlockingQueue<String> queue) {
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("{")) {
                        queue.add(line);
                    } else if (!line.isBlank()) {
                        synchronized (recentOutput) {
                            recentOutput.addLast(line.trim());
                            while (recentOutput.size() > 6) recentOutput.removeFirst();
                        }
                    }
                }
            } catch (IOException ignored) {
                // process died; the poller notices through isAlive()
            }
        }, "PalmTunes-PowerShell-Reader");
        reader.setDaemon(true);
        reader.start();
    }

    private String awaitJsonLine(long timeoutMs) {
        try {
            return lines.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void stopProcess() {
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
        stdin = null;
    }

    private static Path extractScript() throws IOException {
        try (InputStream in = WindowsBackend.class.getResourceAsStream("/assets/palmtunes/backend/smtc.ps1")) {
            if (in == null) {
                throw new IOException("smtc.ps1 missing from the mod jar");
            }
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "palmtunes");
            Files.createDirectories(dir);
            Path script = dir.resolve("smtc-" + ProcessHandle.current().pid() + ".ps1");
            Files.write(script, in.readAllBytes());
            script.toFile().deleteOnExit();
            return script;
        }
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ------------------------------------------------------------------------------------------
    // Parsing (package-private for tests)
    // ------------------------------------------------------------------------------------------

    record ParsedState(MediaInfo info, int seq, boolean artDelivered) {
    }

    static JsonObject parseObject(String line) throws MediaException {
        try {
            JsonElement el = JsonParser.parseString(line);
            if (!el.isJsonObject()) {
                throw MediaException.transientFailure("Unexpected reply from PowerShell helper");
            }
            return el.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new MediaException("Unreadable reply from PowerShell helper", false, e);
        }
    }

    static ParsedState parseState(String line) throws MediaException {
        JsonObject o = parseObject(line);
        if (o.has("error")) {
            throw MediaException.transientFailure(o.get("error").getAsString());
        }
        if (o.has("none")) {
            return new ParsedState(null, -1, false);
        }

        String title = str(o, "title");
        String artist = str(o, "artist");
        String album = str(o, "album");
        String app = str(o, "app");
        PlaybackState state = PlaybackState.parse(str(o, "status"));
        int seq = o.has("seq") ? o.get("seq").getAsInt() : -1;
        boolean artFinal = o.has("artFinal") && o.get("artFinal").getAsBoolean();

        byte[] art = null;
        String artB64 = str(o, "art");
        if (!artB64.isEmpty()) {
            try {
                art = Base64.getDecoder().decode(artB64);
            } catch (IllegalArgumentException ignored) {
                art = null;
            }
        }
        boolean delivered = art != null || artFinal;

        if (state == PlaybackState.STOPPED && title.isBlank() && artist.isBlank()) {
            return new ParsedState(null, seq, delivered);
        }

        long position = o.has("positionMs") ? o.get("positionMs").getAsLong() : -1;
        long duration = o.has("durationMs") ? o.get("durationMs").getAsLong() : -1;
        int shuffle = o.has("shuffle") ? o.get("shuffle").getAsInt() : -1;
        int repeat = o.has("repeat") ? o.get("repeat").getAsInt() : -1;
        MediaInfo info = new MediaInfo(title, artist, album, SourceNames.friendly(app),
                state, position, duration, null, art, shuffle, repeat, java.util.List.of());
        return new ParsedState(info, seq, delivered);
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }
}
