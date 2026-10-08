package com.nul.critterfisher;

import android.content.Context;
import android.content.SharedPreferences;

/** Live-tunable settings, shared between the overlay UI and the bot thread. */
public class Settings {
    public static volatile int tapLatencyMs = 50;   // tap -> game reaction
    public static volatile int aimInsetPct = 22;    // % of zone kept clear on each side
    public static volatile boolean useBait = true;
    public static volatile int baitRecheckSec = 90;
    public static volatile boolean showMarkers = true;
    public static volatile int reelTapMs = 100;
    public static volatile int pinballLaunches = 100;     // 0 = no limit
    public static volatile int pinballIntervalMs = 1200;
    public static volatile int pinballPopupWaitSec = 8;
    public static volatile int mode = 0;
    public static volatile int stopAfterMin = 0;          // 0 = never
    public static volatile int stopAfterCatches = 0;      // fishing, 0 = never
    public static volatile boolean alertOnPause = true;
    public static volatile boolean tapJitter = true;
    public static volatile boolean minimized = false;
    public static volatile boolean autoSwitch = true;
    public static volatile boolean autoStart = false;
    public static volatile int calcCurrency = 2, calcTarget = 100;                  // 0 fishing, 1 pinball

    public static void load(Context c) {
        SharedPreferences p = c.getSharedPreferences("cf", Context.MODE_PRIVATE);
        tapLatencyMs = p.getInt("lat", tapLatencyMs);
        aimInsetPct = p.getInt("inset", aimInsetPct);
        useBait = p.getBoolean("bait", useBait);
        baitRecheckSec = p.getInt("recheck", baitRecheckSec);
        showMarkers = p.getBoolean("markers", showMarkers);
        reelTapMs = p.getInt("reel", reelTapMs);
        pinballLaunches = p.getInt("pbN", pinballLaunches);
        pinballIntervalMs = p.getInt("pbMs", pinballIntervalMs);
        pinballPopupWaitSec = p.getInt("pbWait", pinballPopupWaitSec);
        mode = p.getInt("mode", mode);
        stopAfterMin = p.getInt("stopMin", stopAfterMin);
        stopAfterCatches = p.getInt("stopN", stopAfterCatches);
        alertOnPause = p.getBoolean("alert", alertOnPause);
        tapJitter = p.getBoolean("jitter", tapJitter);
        minimized = p.getBoolean("mini", minimized);
        autoSwitch = p.getBoolean("autoSw", autoSwitch);
        autoStart = p.getBoolean("autoSt", autoStart);
        calcCurrency = p.getInt("calcCur", calcCurrency);
        calcTarget = p.getInt("calcN", calcTarget);
    }

    public static void save(Context c) {
        c.getSharedPreferences("cf", Context.MODE_PRIVATE).edit()
                .putInt("lat", tapLatencyMs).putInt("inset", aimInsetPct)
                .putBoolean("bait", useBait).putInt("recheck", baitRecheckSec)
                .putBoolean("markers", showMarkers).putInt("reel", reelTapMs)
                .putInt("pbN", pinballLaunches).putInt("pbMs", pinballIntervalMs)
                .putInt("pbWait", pinballPopupWaitSec).putInt("mode", mode)
                .putInt("stopMin", stopAfterMin).putInt("stopN", stopAfterCatches)
                .putBoolean("alert", alertOnPause).putBoolean("jitter", tapJitter)
                .putBoolean("mini", minimized).putBoolean("autoSw", autoSwitch)
                .putBoolean("autoSt", autoStart).putInt("calcCur", calcCurrency)
                .putInt("calcN", calcTarget).apply();
    }
}
