package com.nul.critterfisher;

import com.nul.critterfisher.Detect.Frame;

import java.util.Random;
import java.util.concurrent.locks.LockSupport;

/**
 * Pinball Machine (the "Goldrush" tab): presses PUSH at a human-like pace until the
 * launch budget is used. Never taps anything else - any popup or minigame pauses it.
 */
public class PinballBot implements Mode {
    private final Bot.Host host;
    private volatile boolean running = false, alive = true;
    private final Bot.Status st = new Bot.Status();
    private final Random rnd = new Random();
    private int launched = 0;
    private double offSince = -1, lastStatus = 0;

    public PinballBot(Bot.Host host) { this.host = host; st.bait = "launched 0"; }

    @Override public String name() { return "Pinball"; }
    @Override public String counterLabel() { return "launched"; }
    @Override public boolean isRunning() { return running; }
    @Override public void kill() { alive = false; running = false; }
    @Override public Bot.Status status() { return st; }

    @Override public void setRunning(boolean r) {
        int target = Settings.pinballLaunches;
        if (r && target > 0 && launched >= target) launched = 0;   // start a fresh batch
        offSince = -1;
        running = r;
    }

    static double now() { return System.nanoTime() / 1e9; }

    private void sleepMs(long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (alive && running && System.nanoTime() < end) LockSupport.parkNanos(2_000_000L);
    }

    private void push(boolean force) {
        double t = now();
        if (!force && t - lastStatus < 0.1) return;
        lastStatus = t;
        st.running = running;
        st.catches = launched;
        host.onStatus(st);
    }

    private void msg(String m) { st.message = m; push(true); }

    private void updateCounter() {
        int target = Settings.pinballLaunches;
        st.bait = target > 0 ? String.format("launched %d / %d", launched, target)
                : "launched " + launched + " (no limit)";
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
            if (f == null) { LockSupport.parkNanos(20_000_000L); continue; }
            if (Detect.isPinballScreen(f)) {
                offSince = -1;
                int target = Settings.pinballLaunches;
                if (target > 0 && launched >= target) {
                    running = false;
                    st.phase = "done";
                    msg("Done - launched " + launched);
                    continue;
                }
                float[] p = {Detect.P_PUSH[0] + (rnd.nextFloat() - 0.5f) * 40,
                        Detect.P_PUSH[1] + (rnd.nextFloat() - 0.5f) * 24};
                if (!host.tap(p)) {
                    running = false;
                    msg("Tap service is OFF - enable it in Accessibility settings");
                    continue;
                }
                launched++;
                st.phase = "launching";
                updateCounter();
                push(false);
                long base = Settings.pinballIntervalMs;
                sleepMs((long) (base * (0.85 + 0.3 * rnd.nextDouble())));
            } else {
                st.phase = "waiting for pinball screen";
                if (offSince < 0) offSince = now();
                else if (now() - offSince > Settings.pinballPopupWaitSec) {
                    running = false;
                    st.phase = "paused";
                    msg("Not on the pinball screen (popup or minigame?) - paused");
                }
                push(false);
                LockSupport.parkNanos(100_000_000L);
            }
        }
    }
}
