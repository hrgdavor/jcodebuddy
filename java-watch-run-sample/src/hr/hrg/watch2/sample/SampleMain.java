package hr.hrg.watch2.sample;

import hr.hrg.watch2.run.Reloader;
import hr.hrg.watch2.run.StopRegistry;
import hr.hrg.watch2.run.Stoppable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Sample main class for testing the java-watch-run hot-reload daemon.
 *
 * HOW TO USE FOR LATENCY MEASUREMENT
 * ────────────────────────────────────
 * 1. Start the daemon via run-watch-sample.ps1.
 * 2. Edit VERSION (or any other field/method), then save.
 * 3. Watch the console:
 *    - Daemon log line "ECJ compiling..." shows when compile started (T_compile).
 *    - The "⚡ Hot-reload" banner below shows T_main = System.currentTimeMillis().
 *    - "Total latency" printed below = T_main - T_save, where T_save is the time
 *      the watcher detected the file save.
 *    - "Compile+load" printed below = T_main - T_trigger, where T_trigger is
 *      set by Reloader right before ECJ runs (see Reloader.PROP_TRIGGER_MS).
 *
 * WHAT TO EDIT TO TRIGGER A RELOAD
 * ──────────────────────────────────
 * Change VERSION below, add a line to the heartbeat printout, or modify
 * DataProcessor / PersonData and save any .java file in src/.
 */
public class SampleMain {

    // ──────────────────────────────────────────────────────────────────────────
    //  ★  EDIT THIS STRING to verify the hot-reload picked up your change  ★
    // ──────────────────────────────────────────────────────────────────────────
    private static final String VERSION = "v6 – edit me and save!";

    // Captured the instant the class is loaded by the URLClassLoader.
    // Compared with the daemon's hotswap.trigger.ms to break down latency.
    private static final long CLASS_LOAD_MS = System.currentTimeMillis();

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Set to false by the Stoppable hook to stop the heartbeat loop. */
    private static volatile boolean running = false;
    private static volatile Thread  heartbeatThread = null;

    public static void main(String[] args) throws Exception {
        long mainEntryMs = System.currentTimeMillis();

        // ── 1. Register a Stoppable so the daemon can shut us down cleanly ────
        StopRegistry.register(new Stoppable() {
            @Override
            public void stop() {
                running = false;
                Thread t = heartbeatThread;
                if (t != null) t.interrupt();
            }
        });

        // ── 2. Compute latency metrics ────────────────────────────────────────
        long triggerMs   = parseLong(System.getProperty(Reloader.PROP_TRIGGER_MS), mainEntryMs);
        long saveMs      = parseLong(System.getProperty("hotswap.file.save.ms"), triggerMs);
        long reloadMs    = mainEntryMs - triggerMs;     // ECJ compile + classload + thread start
        long classLoadMs = mainEntryMs - CLASS_LOAD_MS; // time inside main() before this line
        long totalMs     = mainEntryMs - saveMs;        // time from developer save to main() output

        // ── 3. Print the reload banner ────────────────────────────────────────
        String ts = LocalTime.now().format(TIME_FMT);
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.printf ("║  ⚡ Hot-reload at %-34s ║%n", ts);
        System.out.printf ("║     VERSION  : %-36s ║%n", VERSION);
        System.out.printf ("║     Total latency   (save → output) : %4d ms          ║%n", totalMs);
        System.out.printf ("║     Internals       (compile+load)  : %4d ms          ║%n", reloadMs);
        System.out.printf ("║     main() overhead (static init)   : %4d ms          ║%n", classLoadMs);
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // ── 4. Jackson demo ───────────────────────────────────────────────────
        System.out.println("── Jackson demo ─────────────────────────────────────────");
        System.out.println(DataProcessor.process());
        System.out.println();

        // ── 5. Heartbeat loop (runs until next reload or Ctrl-C) ──────────────
        running = true;
        heartbeatThread = new Thread(() -> {
            System.out.printf("[%s] heartbeat started (version: %s)%n",
                    LocalTime.now().format(TIME_FMT), VERSION);
            while (running) {
                try {
                    Thread.sleep(5_000);
                    if (running) {
                        System.out.printf("[%s] still running → %s%n",
                                LocalTime.now().format(TIME_FMT), VERSION);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
            System.out.printf("[%s] heartbeat stopped (was: %s)%n",
                    LocalTime.now().format(TIME_FMT), VERSION);
        }, "sample-heartbeat");

        heartbeatThread.setDaemon(true);
        heartbeatThread.start();

        // main() returns here – the daemon's reload thread is now free.
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
