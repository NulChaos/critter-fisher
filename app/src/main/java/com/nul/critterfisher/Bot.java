package com.nul.critterfisher;

import com.nul.critterfisher.Detect.Bar;
import com.nul.critterfisher.Detect.Frame;
import com.nul.critterfisher.Detect.State;

import java.util.concurrent.locks.LockSupport;

/** The fishing loop. Runs on its own thread. */
public class Bot implements Runnable {

    public interface Host {
        /** Newest frame, waiting up to timeoutMs for one newer than the last returned. */
        Frame next(long timeoutMs);
        /** Tap at a reference-space point. Returns false if the tap service is off. */
        boolean tap(float[] refPoint);
        void onStatus(Status s);
    }

    /** Snapshot for the overlay. */
    public static class Status {
        public State state = State.UNKNOWN;
        public boolean running;
        public String message = "", bait = "";
        public int catches, fps;
        public float zoneL = -1, zoneR = -1, innerL, innerR, marker = -1, tapX = -1;
        public long tapAtMs;
    }

    private final Host host;
    public volatile boolean running = false;
    public volatile boolean alive = true;
    /** Set from the panel to try bait on the next idle screen. */
    public volatile boolean forceBait = false;

    private final Tracker tracker = new Tracker();
    private final Status st = new Status();
    private double lastTap = 0, baitPauseUntil = 0, frameDt = 0.03;
    private double unknownSince = -1, lastStatus = 0, catchSince = -1, lastCatchTap = 0;
    private int unknownTaps = 0, castsWithoutProgress = 0, frames = 0;
    private double fpsWindow = 0;
    private long lastFrameNs = 0;
    private State prev = null;

    public Bot(Host host) { this.host = host; }

    static double now() { return System.nanoTime() / 1e9; }

    private void sleepMs(long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (alive && System.nanoTime() < end) LockSupport.parkNanos(2_000_000L);
    }

    private void sleepUntil(double t) {
        while (true) {
            double left = t - now();
            if (left <= 0) return;
            LockSupport.parkNanos((long) Math.min(left * 1e9, 2_000_000L));
        }
    }

    private void msg(String m) { st.message = m; push(true); }

    private void push(boolean force) {
        double t = now();
        if (!force && t - lastStatus < 0.08) return;
        lastStatus = t;
        st.running = running;
        host.onStatus(st);
    }

    private boolean tap(float[] p) {
        boolean ok = host.tap(p);
        if (!ok) msg("Tap service is OFF - enable it in Accessibility settings");
        return ok;
    }

    @Override public void run() {
        while (alive) {
            if (!running) {
                prev = null;
                tracker.reset();
                push(false);
                sleepMs(150);
                continue;
            }
            Frame f = host.next(80);
            if (f == null) { sleepMs(20); continue; }
            double t = now();
            if (f.timeNs != lastFrameNs) {
                frames++;
                if (lastFrameNs != 0) {
                    double d = (f.timeNs - lastFrameNs) / 1e9;
                    if (d > 0 && d < 0.5) frameDt = 0.8 * frameDt + 0.2 * d;
                }
                lastFrameNs = f.timeNs;
            }
            if (t - fpsWindow >= 1) { st.fps = frames; frames = 0; fpsWindow = t; }

            Bar[] bo = new Bar[1];
            State s = Detect.stateOf(f, bo);
            st.state = s;
            if (s != State.UNKNOWN) { unknownSince = -1; unknownTaps = 0; }
            if (s != State.CATCH) catchSince = -1;
            updateBaitInfo(t);
            if (s != State.MINIGAME) { tracker.reset(); st.zoneL = -1; st.marker = -1; }

            switch (s) {
                case IDLE:
                    if (prev == State.MINIGAME || prev == State.REEL || prev == State.HOOKED
                            || prev == State.UNKNOWN || prev == State.CATCH) {
                        st.catches++;
                        msg("Catch #" + st.catches);
                    }
                    handleIdle(f);
                    break;
                case BAIT_POPUP:
                    tap(Detect.P_SAFE);
                    sleepMs(500);
                    break;
                case WAITING:
                    castsWithoutProgress = 0;
                    break;
                case REEL:
                    castsWithoutProgress = 0;
                    tap(Detect.P_CAST_BTN);
                    sleepMs(Settings.reelTapMs);
                    break;
                case MINIGAME:
                    castsWithoutProgress = 0;
                    handleMinigame(f, bo[0]);
                    break;
                case HOOKED:
                    break;
                case CATCH:
                    if (catchSince < 0) catchSince = t;
                    if (t - catchSince > 0.7 && t - lastCatchTap > 0.8) {
                        tap(Detect.P_DISMISS);
                        lastCatchTap = t;
                    }
                    break;
                default:
                    if (unknownSince < 0) unknownSince = t;
                    else if (t - unknownSince > 1.5) {
                        if (unknownTaps >= 8) {
                            running = false;
                            msg("Stuck on an unknown screen - paused");
                        } else {
                            tap(Detect.P_DISMISS);    // dismiss whatever popup this is
                            unknownTaps++;
                            unknownSince = now();
                        }
                    }
            }
            prev = s;
            push(false);
        }
    }

    private void handleIdle(Frame f) {
        if (forceBait || (Settings.useBait && now() > baitPauseUntil)) {
            forceBait = false;
            tryScatter(f);
            return;
        }
        if (!tap(Detect.P_CAST_BTN)) { sleepMs(1000); return; }
        castsWithoutProgress++;
        if (castsWithoutProgress > 4) {
            running = false;
            castsWithoutProgress = 0;
            msg("Cast isn't starting - out of rods? Paused.");
            return;
        }
        sleepMs(1000);
    }

    private String baitReason = "ready";
    /** When our own baits were scattered (each lasts 10 minutes). */
    private final java.util.ArrayDeque<Double> myBaits = new java.util.ArrayDeque<>();

    private void updateBaitInfo(double t) {
        if (!Settings.useBait) st.bait = "bait: off";
        else if (t < baitPauseUntil) {
            int left = (int) (baitPauseUntil - t);
            st.bait = String.format("bait: %s, retry in %d:%02d", baitReason, left / 60, left % 60);
        } else st.bait = "bait: ready (used on next idle screen)";
    }

    private void tryScatter(Frame f) {
        st.bait = "bait: opening bag...";
        push(true);
        boolean[] before = Detect.cloverMask(f);
        if (!tap(Detect.P_BAG)) { baitPauseUntil = now() + 5; baitReason = "tap service off"; return; }
        sleepMs(800);
        Frame pop = host.next(200);
        if (pop == null || !Detect.isGreen(Detect.patch(pop, Detect.P_SCATTER))) {
            baitPauseUntil = now() + 300;
            baitReason = "no bait / button not found";
            int[] c = pop == null ? new int[3] : Detect.patch(pop, Detect.P_SCATTER);
            msg(String.format("Scatter button not seen (color %d,%d,%d)", c[0], c[1], c[2]));
            tap(Detect.P_SAFE);
            sleepMs(600);
            return;
        }
        tap(Detect.P_SCATTER);
        sleepMs(1300);
        tap(Detect.P_SAFE);
        sleepMs(700);
        Frame after = host.next(200);
        if (after == null) return;
        if (Detect.sameMask(before, Detect.cloverMask(after))) {
            double tn = now();
            while (!myBaits.isEmpty() && tn - myBaits.peekFirst() > 600) myBaits.pollFirst();
            if (!myBaits.isEmpty()) {
                // limit reached: retry right after our oldest bait expires
                baitPauseUntil = myBaits.peekFirst() + 603;
                baitReason = "at limit, " + myBaits.size() + " of mine active";
            } else {
                baitPauseUntil = tn + Settings.baitRecheckSec;
                baitReason = "luck didn't change (limit?)";
            }
            msg("Tapped Scatter Bait, luck number didn't change");
        } else {
            myBaits.addLast(now());
            msg("Scattered bait - luck up");
        }
    }

    private void handleMinigame(Frame f, Bar bar) {
        double t = now();
        if (t - lastTap < 0.45) return;
        double ft = f.timeNs / 1e9;
        if (Math.abs(ft - t) > 1.0) ft = t;
        tracker.add(ft, bar.marker, bar.zoneL, bar.zoneR);
        int inset = Settings.aimInsetPct;
        st.zoneL = tracker.zoneL;
        st.zoneR = tracker.zoneR;
        st.innerL = tracker.innerL(inset);
        st.innerR = tracker.innerR(inset);
        st.marker = bar.marker;

        double lat = Settings.tapLatencyMs / 1000.0;
        double hit = tracker.nextHitTime(t + lat + 0.004, inset);
        if (hit < 0) return;
        double fireAt = hit - lat;
        if (fireAt - t <= frameDt * 1.6 + 0.02) {
            int dir = tracker.direction();
            float px = tracker.predict(dir, hit - ft);
            sleepUntil(fireAt);
            tap(Detect.P_CAST_BTN);
            lastTap = now();
            st.tapX = px;
            st.tapAtMs = System.currentTimeMillis();
            msg(String.format("Tap -> zone %.0f-%.0f", tracker.zoneL, tracker.zoneR));
            tracker.reset();
        }
    }
}
