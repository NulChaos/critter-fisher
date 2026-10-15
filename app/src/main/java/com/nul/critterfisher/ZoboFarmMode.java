package com.nul.critterfisher;

import com.nul.critterfisher.Detect.Frame;

import java.util.Random;
import java.util.concurrent.locks.LockSupport;

/**
 * Zobo Shooter power-up farm (Lock On / Enrage). Shoots Zobos ONLY in Trozobo Arena (1x),
 * where each shot costs 1 coin. Two independent checks must both pass before any tap:
 *  1. the last arena selected in the Zobo Shooter lobby was Trozobo (first dot), and
 *  2. the arena title matches "Trozobo Arena".
 * Anything else pauses the farm without tapping.
 */
public class ZoboFarmMode implements Mode {
    /** Last arena selected in the lobby (-1 = not seen yet). Updated by screen detection. */
    public static volatile int lobbyArena = -1;

    private final Bot.Host host;
    private volatile boolean running = false, alive = true;
    private final Bot.Status st = new Bot.Status();
    private final Random rnd = new Random();
    private int shots = 0;
    private double offSince = -1, lastStatus = 0;

    public ZoboFarmMode(Bot.Host host) { this.host = host; st.bait = "shots 0"; }

    @Override public String name() { return "Zobo farm"; }
    @Override public String counterLabel() { return "shots"; }
    @Override public boolean isRunning() { return running; }
    @Override public void kill() { alive = false; running = false; }
    @Override public Bot.Status status() { return st; }

    @Override public void setRunning(boolean r) {
        int target = Settings.zoboShots;
        if (r && target > 0 && shots >= target) shots = 0;
        offSince = -1;
        running = r;
    }

    static double now() { return System.nanoTime() / 1e9; }

    private void push(boolean force) {
        double t = now();
        if (!force && t - lastStatus < 0.1) return;
        lastStatus = t;
        st.running = running;
        st.catches = shots;
        host.onStatus(st);
    }

    private void stop(String why) {
        running = false;
        st.phase = "paused";
        st.message = why;
        push(true);
    }

    private void sleepMs(long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (alive && running && System.nanoTime() < end) LockSupport.parkNanos(2_000_000L);
    }

    @Override public void run() {
        while (alive) {
            if (!running) {
                st.phase = "paused";
                push(false);
                LockSupport.parkNanos(150_000_000L);
                continue;
            }
            Frame f = host.next(100);
            if (f == null) { LockSupport.parkNanos(50_000_000L); continue; }

            int lobby = Detect.zoboLobbyArena(f);
            if (lobby >= 0) lobbyArena = lobby;

            if (Detect.isZoboArena(f)) {
                offSince = -1;
                float sim = Detect.titleSimilarity(Detect.titleMask(f), Detect.TROZOBO_TITLE);
                if (lobbyArena < 0) {
                    stop("Open Trozobo from the Zobo Shooter menu first, so I can confirm the arena.");
                    continue;
                }
                if (lobbyArena != 0 || sim < 0.75f) {
                    stop("Not Trozobo Arena - power-up farming only runs in the 1x room.");
                    continue;
                }
                int target = Settings.zoboShots;
                if (target > 0 && shots >= target) {
                    stop("Done - " + shots + " shots fired");
                    continue;
                }
                float[] t = Detect.zoboTarget(f);
                if (t == null) {
                    st.phase = "waiting for Zobos";
                    push(false);
                    LockSupport.parkNanos(150_000_000L);
                    continue;
                }
                if (!host.tap(t)) { stop("Tap service is OFF - enable it in Accessibility settings"); continue; }
                shots++;
                st.phase = "farming";
                st.bait = target > 0 ? String.format("shots %d / %d (1 coin each)", shots, target)
                        : "shots " + shots + " (no limit)";
                push(false);
                sleepMs((long) (Settings.zoboIntervalMs * (0.85 + 0.3 * rnd.nextDouble())));
            } else {
                st.phase = lobby >= 0 ? "in lobby" : "waiting for the arena";
                if (offSince < 0) offSince = now();
                else if (now() - offSince > 10) stop("Left the arena - paused");
                push(false);
                LockSupport.parkNanos(150_000_000L);
            }
        }
    }
}
