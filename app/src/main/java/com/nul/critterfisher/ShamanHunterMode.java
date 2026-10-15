package com.nul.critterfisher;

import com.nul.critterfisher.Detect.Frame;

import java.util.concurrent.locks.LockSupport;

/**
 * Shaman hunter for Vienzobo Arena (200x). Loop:
 *   lobby (Vienzobo selected) -> Start Now -> scan the boss portrait for a few seconds ->
 *   no Shaman: leave with the bottom X and rejoin;
 *   Shaman: tap Lock On (auto-aims at the highest-value Zobo and fires) + Enrage, re-use them
 *   when they run out while the Shaman is still there, and leave once it is gone.
 * Never shoots manually - all shots come from Lock On. Refuses to run outside Vienzobo.
 */
public class ShamanHunterMode implements Mode {
    private final Bot.Host host;
    private volatile boolean running = false, alive = true;
    private final Bot.Status st = new Bot.Status();
    private int hunts = 0, hops = 0;
    private double lastStatus = 0;

    public ShamanHunterMode(Bot.Host host) { this.host = host; st.bait = "Shamans 0 · rooms 0"; }

    @Override public String name() { return "Shaman hunt"; }
    @Override public String counterLabel() { return "Shamans"; }
    @Override public boolean isRunning() { return running; }
    @Override public void kill() { alive = false; running = false; }
    @Override public Bot.Status status() { return st; }

    @Override public void setRunning(boolean r) {
        if (r && Settings.shamanHunts > 0 && hunts >= Settings.shamanHunts) { hunts = 0; hops = 0; }
        if (r && Settings.shamanMaxRooms > 0 && hops >= Settings.shamanMaxRooms) hops = 0;
        running = r;
    }

    static double now() { return System.nanoTime() / 1e9; }

    private void push(boolean force) {
        double t = now();
        if (!force && t - lastStatus < 0.1) return;
        lastStatus = t;
        st.running = running;
        st.catches = hunts;
        st.bait = String.format("Shamans %d%s · rooms %d%s", hunts,
                Settings.shamanHunts > 0 ? "/" + Settings.shamanHunts : "", hops,
                Settings.shamanMaxRooms > 0 ? "/" + Settings.shamanMaxRooms : "");
        host.onStatus(st);
    }

    private void phase(String p) { st.phase = p; push(false); }
    private void msg(String m) { st.message = m; push(true); }

    private void stop(String why) {
        running = false;
        st.phase = "paused";
        msg(why);
    }

    private void sleepMs(long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        while (alive && running && System.nanoTime() < end) LockSupport.parkNanos(5_000_000L);
    }

    private boolean tap(float[] p) {
        if (!host.tap(p)) { stop("Tap service is OFF - enable it in Accessibility settings"); return false; }
        return true;
    }

    /** Vienzobo check: lobby selection must be Vienzobo, and the title must match if readable. */
    private String arenaProblem(Frame f) {
        if (ZoboFarmMode.lobbyArena < 0) return "Open the Zobo Shooter menu and select Vienzobo first.";
        if (ZoboFarmMode.lobbyArena != 7) return "Select Vienzobo Arena (200x) in the menu - the hunter only runs there.";
        boolean[] title = Detect.titleMask(f);
        if (Detect.titleReadable(title)
                && Detect.titleSimilarity(title, Detect.VIENZOBO_TITLE) < 0.75f) {
            return "This isn't Vienzobo Arena - stopped.";
        }
        return null;
    }

    @Override public void run() {
        double screenUnknownSince = -1;
        while (alive) {
            if (!running) {
                st.phase = "paused";
                push(false);
                LockSupport.parkNanos(150_000_000L);
                continue;
            }
            Frame f = host.next(150);
            if (f == null) { LockSupport.parkNanos(50_000_000L); continue; }

            int lobby = Detect.zoboLobbyArena(f);
            if (lobby >= 0) {
                ZoboFarmMode.lobbyArena = lobby;
                screenUnknownSince = -1;
                if (lobby != 7) { stop("Select Vienzobo Arena (200x) in the menu, then press ▶."); continue; }
                if (Settings.shamanHunts > 0 && hunts >= Settings.shamanHunts) {
                    stop("Done - " + hunts + " Shaman hunts"); continue;
                }
                if (Settings.shamanMaxRooms > 0 && hops >= Settings.shamanMaxRooms) {
                    stop("Checked " + hops + " rooms - room limit reached"); continue;
                }
                phase("joining a room");
                if (!tap(Detect.P_ZOBO_START)) continue;
                hops++;
                waitForArena();
                continue;
            }
            if (Detect.isZoboArena(f)) {
                screenUnknownSince = -1;
                String problem = arenaProblem(f);
                if (problem != null) { stop(problem); continue; }
                handleRoom();
                continue;
            }
            // transition, popup or something else
            phase("waiting");
            if (screenUnknownSince < 0) screenUnknownSince = now();
            else if (now() - screenUnknownSince > 12) stop("Unknown screen for 12 s - paused");
            LockSupport.parkNanos(100_000_000L);
        }
    }

    private void waitForArena() {
        double end = now() + 8;
        while (alive && running && now() < end) {
            Frame f = host.next(150);
            if (f != null && Detect.isZoboArena(f)) return;
            LockSupport.parkNanos(50_000_000L);
        }
    }

    /** In an arena: scan for a Shaman, fight it if present, then leave. */
    private void handleRoom() {
        phase("scanning for Shaman");
        double scanEnd = now() + Settings.shamanScanMs / 1000.0;
        int seen = 0;
        while (alive && running && now() < scanEnd) {
            Frame f = host.next(150);
            if (f == null) continue;
            int b = Detect.bossPortrait(f);
            if (b == 2) { if (++seen >= 2) break; } else seen = 0;   // two frames in a row
            LockSupport.parkNanos(60_000_000L);
        }
        if (!running) return;
        if (seen >= 2) fight();
        leaveRoom();
    }

    private void fight() {
        msg("Shaman found! Using Lock On + Enrage");
        double start = now(), lastUse = -100, goneSince = -1;
        while (alive && running) {
            double t = now();
            if (t - lastUse > Settings.shamanReuseSec) {          // (re)activate power-ups
                if (!tap(Detect.P_LOCK_ON)) return;
                sleepMs(350);
                if (!tap(Detect.P_ENRAGE)) return;
                lastUse = now();
            }
            phase(String.format("fighting Shaman (%.0fs)", t - start));
            Frame f = host.next(150);
            if (f != null) {
                if (Detect.zoboLobbyArena(f) >= 0) return;          // we got kicked to the lobby
                if (Detect.bossPortrait(f) == 2) goneSince = -1;
                else if (goneSince < 0) goneSince = t;
                else if (t - goneSince > 2.5) {                      // Shaman gone: killed or left
                    hunts++;
                    msg("Shaman down (or gone) after " + (int) (t - start) + "s - next room");
                    sleepMs(1500);                                   // let reward effects play
                    return;
                }
            }
            if (t - start > Settings.shamanMaxFightSec) {
                msg("Fight time limit reached - moving on");
                return;
            }
            LockSupport.parkNanos(80_000_000L);
        }
    }

    private void leaveRoom() {
        if (!running) return;
        phase("leaving room");
        for (int attempt = 0; attempt < 3 && running; attempt++) {
            Frame f = host.next(150);
            if (f != null && Detect.zoboLobbyArena(f) >= 0) return;
            if (f != null && Detect.isZoboArena(f) || attempt > 0) {
                if (!tap(Detect.P_ZOBO_EXIT)) return;
            }
            double end = now() + 3;
            while (alive && running && now() < end) {
                Frame g = host.next(150);
                if (g != null && Detect.zoboLobbyArena(g) >= 0) return;
                LockSupport.parkNanos(60_000_000L);
            }
        }
    }
}
