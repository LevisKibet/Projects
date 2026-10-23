package com.palmtunes.media.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Runs short-lived helper processes with a hard timeout. */
final class ProcessUtil {

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "PalmTunes-Watchdog");
        t.setDaemon(true);
        return t;
    });

    private ProcessUtil() {
    }

    record Result(int exitCode, String output) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    /**
     * Runs a command and returns its combined stdout+stderr (UTF-8).
     *
     * @throws IOException if the program cannot be started (usually: not installed)
     */
    static Result run(List<String> command, long timeoutMs) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(nullFile()));
        Process process = pb.start();
        ScheduledFuture<?> kill = WATCHDOG.schedule(process::destroyForcibly, timeoutMs, TimeUnit.MILLISECONDS);
        try {
            byte[] bytes = process.getInputStream().readAllBytes();
            int code = process.waitFor();
            return new Result(code, new String(bytes, StandardCharsets.UTF_8));
        } finally {
            kill.cancel(false);
            process.destroy();
        }
    }

    private static java.io.File nullFile() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }

    /** True if the named program can be started at all. */
    static boolean canStart(List<String> command) {
        try {
            run(command, 4000);
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
