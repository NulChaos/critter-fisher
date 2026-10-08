package com.nul.critterfisher;

/**
 * Tracks the minigame marker, which moves at constant speed and bounces between
 * fixed ends, and predicts when it will be inside the yellow zone.
 * Times are System.nanoTime()-based seconds.
 */
public class Tracker {
    public static float markerMin = 267, markerMax = 689;   // measured from recording
    public float speed = 300f;                               // ref px per second

    private final double[] ts = new double[4];
    private final float[] xs = new float[4];
    private int n = 0;
    public float zoneL = -1, zoneR = -1;

    public void reset() { n = 0; zoneL = zoneR = -1; }

    public void add(double t, float x, float zl, float zr) {
        if (n > 0 && t - ts[n - 1] > 0.6) n = 0;
        if (n == 4) {
            System.arraycopy(ts, 1, ts, 0, 3);
            System.arraycopy(xs, 1, xs, 0, 3);
            n = 3;
        }
        ts[n] = t; xs[n] = x; n++;
        if (x < markerMin - 4) markerMin = x;
        if (x > markerMax + 4) markerMax = x;
        if (zoneL >= 0 && zl <= zoneR && zr >= zoneL) {
            zoneL = Math.min(zl, zoneL);       // same zone: merge (marker hides part of it)
            zoneR = Math.max(zr, zoneR);
        } else {
            zoneL = zl; zoneR = zr;
        }
        if (n >= 2) {
            double dt = ts[n - 1] - ts[n - 2];
            float x0 = xs[n - 2], x1 = xs[n - 1];
            if (dt > 0.012 && Math.min(x0, x1) - markerMin > 40 && markerMax - Math.max(x0, x1) > 40) {
                float v = (float) (Math.abs(x1 - x0) / dt);
                if (v > 100 && v < 1500) speed = 0.6f * speed + 0.4f * v;
            }
        }
    }

    public float lastX() { return n > 0 ? xs[n - 1] : -1; }

    public int direction() {
        if (n < 2) return 0;
        double dt = ts[n - 1] - ts[n - 2];
        float x0 = xs[n - 2], x1 = xs[n - 1];
        if (Math.abs(x1 - x0) < 2) return 0;
        float reach = (float) (speed * dt);
        if (x1 - markerMin < reach || markerMax - x1 < reach) return 0;   // may have bounced
        return x1 > x0 ? 1 : -1;
    }

    public float predict(int dir, double dt) {
        float x0 = xs[n - 1];
        float span = markerMax - markerMin;
        double s = dir > 0 ? (x0 - markerMin) : 2 * span - (x0 - markerMin);
        s = (s + speed * dt) % (2 * span);
        if (s < 0) s += 2 * span;
        return (float) (s <= span ? markerMin + s : markerMin + 2 * span - s);
    }

    public float innerL(int insetPct) { return zoneL + (zoneR - zoneL) * insetPct / 100f; }
    public float innerR(int insetPct) { return zoneR - (zoneR - zoneL) * insetPct / 100f; }

    /** Absolute time (s) to have the marker in the middle of the reachable target window. */
    public double nextHitTime(double earliest, int insetPct) {
        int d = direction();
        if (d == 0 || zoneL < 0) return -1;
        double t1 = ts[n - 1];
        float lo = innerL(insetPct), hi = innerR(insetPct);
        double dt = Math.max(0, earliest - t1);
        double start = -1;
        while (dt < 3.0) {
            float p = predict(d, dt);
            boolean inside = p >= lo && p <= hi;
            if (inside && start < 0) start = dt;
            else if (!inside && start >= 0) return t1 + (start + dt) / 2;
            dt += 0.002;
        }
        return start >= 0 ? t1 + start : -1;
    }
}
