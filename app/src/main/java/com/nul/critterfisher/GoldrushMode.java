package com.nul.critterfisher;

import com.nul.critterfisher.Detect.Frame;

import java.util.concurrent.locks.LockSupport;

/**
 * Island Goldrush advisor. Never taps - it reads the visible map and shows
 * territory shares plus strategy reminders on the panel.
 */
public class GoldrushMode implements Mode {
    private final Bot.Host host;
    private volatile boolean running = false, alive = true;
    private final Bot.Status st = new Bot.Status();
    private int scans = 0, tip = 0;
    private double lastTip = 0;

    static final String[] TIPS = {
            "Top target: the center Gold Vein (4x4, 1,800/h). Taking it from the leader swings ~3,600/h.",
            "Value per defender: Vein 1.8 > Large Mine 1.26 > Fields ~1.1 > Small Field 0.6. Skip 1x1s unless they open a path.",
            "Ranking = total gold generated, so captures early in the week pay the most. Hold veins to the end.",
            "Grab Large Field (Box) tiles before the daily reset - they pay bonus pinballs/capsules.",
            "Stay at peace with your quieter neighbour; never fight on two fronts.",
            "Stop daily attacks around 20-25 unless retaking a vein - after 40 each attack costs 6x.",
            "Use rallies (2,000 drinks, no Tired penalty) for the big tiles.",
    };

    public GoldrushMode(Bot.Host host) { this.host = host; st.bait = "open the Island Goldrush map"; }

    @Override public String name() { return "Goldrush"; }
    @Override public String counterLabel() { return "scans"; }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; }
    @Override public void kill() { alive = false; running = false; }
    @Override public Bot.Status status() { return st; }

    @Override public void run() {
        while (alive) {
            if (!running) {
                st.running = false;
                st.phase = "paused";
                host.onStatus(st);
                LockSupport.parkNanos(300_000_000L);
                continue;
            }
            Frame f = host.next(200);
            double t = System.nanoTime() / 1e9;
            if (f != null && Detect.isGoldrushMap(f)) {
                float[] s = Detect.factionShares(f);
                scans++;
                st.catches = scans;
                st.phase = "reading map";
                st.bait = String.format("On screen: B %.0f%% · Y %.0f%% · R %.0f%% · P %.0f%%",
                        s[0] * 100, s[1] * 100, s[2] * 100, s[3] * 100);
            } else {
                st.phase = "not on the map";
            }
            if (t - lastTip > 12) {
                st.message = TIPS[tip++ % TIPS.length];
                lastTip = t;
            }
            st.running = true;
            host.onStatus(st);
            LockSupport.parkNanos(700_000_000L);
        }
    }
}
