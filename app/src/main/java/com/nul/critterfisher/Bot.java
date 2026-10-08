package com.nul.critterfisher;

import com.nul.critterfisher.Detect.Bar;
import com.nul.critterfisher.Detect.Frame;
import com.nul.critterfisher.Detect.State;

import java.util.concurrent.locks.LockSupport;

/** The fishing loop. Runs on its own thread. */
public class Bot implements Mode {

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
        public String message = "", bait = "", phase = "";
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

    @Override public String name() { return "Fishing"; }
    @Override public String counterLabel() { return "catches"; }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; }
    @Override public void kill() { alive = false; running = false; }
    @Override public Status status() { return st; }

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
            st.phase = s.name().toLowerCase();
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
                case LUCK_POPUP:
                    tap(Detect.P_LUCK_CLOSE);
                    sleepMs(600);
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
        double t = now();
        boolean cloverChanged = verifiedMask != null
                && !Detect.sameMask(verifiedMask, Detect.cloverMask(f));
        if (forceBait || (Settings.useBait && (t >= nextBaitCheck
                || (cloverChanged && t - lastBaitCheck > 20)))) {
            forceBait = false;
            baitRoutine();
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

    // ------------------------------------------------------------------ bait
    public static final int MAX_BAIT = 4;      // game's stack limit
    private double nextBaitCheck = 0, lastBaitCheck = 0;
    private boolean[] verifiedMask = null;
    private int activeBait = -1;
    private String baitNote = "";

    private void updateBaitInfo(double t) {
        if (!Settings.useBait) { st.bait = "bait: off"; return; }
        String a = activeBait < 0 ? "?" : (activeBait + "/" + MAX_BAIT);
        int left = (int) Math.max(0, nextBaitCheck - t);
        st.bait = String.format("bait active %s%s · check in %d:%02d", a,
                baitNote.isEmpty() ? "" : " (" + baitNote + ")", left / 60, left % 60);
    }

    /** Waits until the screen shows the given state. Returns that frame, or null. */
    private Frame waitFor(State want, long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (alive && System.nanoTime() < end) {
            Frame f = host.next(60);
            if (f != null && Detect.stateOf(f, null) == want) return f;
        }
        return null;
    }

    /** Opens the Luck Level popup and counts active baits. -1 if it didn't open. */
    private int readActiveBait() {
        st.bait = "bait: reading Luck panel...";
        push(true);
        if (!tap(Detect.P_CLOVER)) return -1;
        Frame p = waitFor(State.LUCK_POPUP, 2500);
        if (p == null) return -1;
        sleepMs(350);                              // let the rows finish appearing
        p = host.next(100);
        int n = Detect.isLuckPopup(p) ? Detect.countBaitRows(p) : -1;
        tap(Detect.P_LUCK_CLOSE);                  // only tapped when the popup is confirmed open
        waitFor(State.IDLE, 2500);
        sleepMs(500);                              // closing animation
        return n;
    }

    /** Opens the bait bag popup, retrying. Returns the frame showing it, or null. */
    private Frame openBag() {
        for (int attempt = 0; attempt < 3 && alive; attempt++) {
            Frame cur = host.next(60);
            if (cur != null && Detect.bagPopupOpen(cur)) return cur;
            if (cur != null && Detect.stateOf(cur, null) != State.IDLE) {
                waitFor(State.IDLE, 1500);         // let the previous popup finish closing
            }
            sleepMs(400);
            if (!tap(Detect.P_BAG)) return null;
            long end = System.nanoTime() + 1_800_000_000L;
            while (alive && System.nanoTime() < end) {
                Frame f = host.next(60);
                if (f != null && Detect.bagPopupOpen(f)) {
                    sleepMs(250);                  // let the button finish animating in
                    return host.next(60);
                }
            }
        }
        return null;
    }

    /** 1 = scattered, 0 = out of bait, -1 = couldn't open the bag. */
    private int scatterOnce() {
        st.bait = "bait: opening bag...";
        push(true);
        Frame p = openBag();
        if (p == null) return -1;
        if (!Detect.scatterAvailable(p)) {
            sleepMs(300);
            p = host.next(60);
        }
        if (!Detect.scatterAvailable(p)) {         // popup is open but no usable button
            tap(Detect.P_SAFE);
            sleepMs(600);
            return 0;
        }
        tap(Detect.P_SCATTER);
        sleepMs(1200);
        Frame q = host.next(100);
        if (q != null && Detect.bagPopupOpen(q)) {
            tap(Detect.P_SAFE);
            waitFor(State.IDLE, 1500);
        }
        return 1;
    }

    /** Reads how many baits are active and scatters until the stack limit is reached. */
    private void baitRoutine() {
        lastBaitCheck = now();
        baitNote = "";
        int n = readActiveBait();
        boolean outOfBait = false, bagFailed = false;
        for (int round = 0; round < 3 && alive && running; round++) {
            if (n >= MAX_BAIT) break;
            int need = n < 0 ? 1 : MAX_BAIT - n;   // unknown: try one, then re-read
            int done = 0;
            for (int i = 0; i < need; i++) {
                int r = scatterOnce();
                if (r == 0) { outOfBait = true; break; }
                if (r < 0) { bagFailed = true; break; }
                done++;
            }
            int n2 = readActiveBait();
            msg(String.format("Scattered %d bait, active now %s", done, n2 < 0 ? "?" : n2 + "/" + MAX_BAIT));
            if (outOfBait || bagFailed) { n = n2; break; }
            if (n2 >= 0 && n >= 0 && n2 <= n) {    // nothing changed: game refused or lagging
                sleepMs(1500);
                n2 = readActiveBait();
                if (n2 <= n) { n = n2; baitNote = "game refused"; break; }
            }
            n = n2;
        }
        activeBait = n;
        if (outOfBait) baitNote = "out of bait";
        if (bagFailed) baitNote = "bag didn't open, retrying";
        if (n < 0) baitNote = "couldn't read Luck panel";
        double t = now();
        if (outOfBait) nextBaitCheck = t + 300;
        else if (bagFailed) nextBaitCheck = t + 20;
        else if (n >= MAX_BAIT) nextBaitCheck = t + Settings.baitRecheckSec;
        else nextBaitCheck = t + 45;
        Frame f = waitFor(State.IDLE, 1500);
        verifiedMask = f != null ? Detect.cloverMask(f) : null;
        if (n >= MAX_BAIT) msg("Luck maxed: " + n + "/" + MAX_BAIT + " bait active");
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
