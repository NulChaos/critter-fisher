package com.nul.critterfisher;

import android.content.Context;
import android.content.SharedPreferences;

/** Live-tunable settings, shared between the overlay UI and the bot thread. */
public class Settings {
    public static volatile int tapLatencyMs = 50;   // tap -> game reaction
    public static volatile int aimInsetPct = 22;    // % of zone kept clear on each side
    public static volatile boolean useBait = true;
    public static volatile int baitRecheckSec = 150;
    public static volatile boolean showMarkers = true;
    public static volatile int reelTapMs = 100;

    public static void load(Context c) {
        SharedPreferences p = c.getSharedPreferences("cf", Context.MODE_PRIVATE);
        tapLatencyMs = p.getInt("lat", tapLatencyMs);
        aimInsetPct = p.getInt("inset", aimInsetPct);
        useBait = p.getBoolean("bait", useBait);
        baitRecheckSec = p.getInt("recheck", baitRecheckSec);
        showMarkers = p.getBoolean("markers", showMarkers);
        reelTapMs = p.getInt("reel", reelTapMs);
    }

    public static void save(Context c) {
        c.getSharedPreferences("cf", Context.MODE_PRIVATE).edit()
                .putInt("lat", tapLatencyMs).putInt("inset", aimInsetPct)
                .putBoolean("bait", useBait).putInt("recheck", baitRecheckSec)
                .putBoolean("markers", showMarkers).putInt("reel", reelTapMs).apply();
    }
}
