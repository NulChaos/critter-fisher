package com.nul.critterfisher;

/**
 * Screen recognition. All coordinates are in a 923 x 1999 reference space
 * (measured from the user's screenshots) and scaled to the captured frame.
 * Ported 1:1 from fishbot.py v2, which was tested against real screenshots.
 */
public class Detect {
    public static final float REF_W = 923f, REF_H = 1999f;

    public static final float[] P_CAST_BTN = {461, 1700};
    public static final float[] P_BTN_SAMPLE = {380, 1640};
    public static final float[] P_REEL_PANEL = {300, 1378};
    public static final float[] P_BAG = {825, 1600};
    public static final float[] P_SCATTER = {730, 1350};    // centre of the green button
    public static final float[] P_SAFE = {460, 1050};
    public static final float[] P_DISMISS = {300, 1560};   // below the catch card
    public static final float[] P_CARD_L = {120, 950}, P_CARD_R = {800, 950};
    public static final float[] P_CLOVER = {95, 1600};         // opens the Luck Level popup
    public static final float[] P_LUCK_CLOSE = {461, 1910};    // X button of that popup
    public static final float[] P_LUCK_TITLE = {100, 520}, P_LUCK_WHITE1 = {460, 600},
            P_LUCK_WHITE2 = {460, 1500};
    public static final float BAR_Y = 1463, BAR_X0 = 245, BAR_X1 = 705;
    public static final float MARKER_Y0 = 1400, MARKER_Y1 = 1510;
    public static final float[] CLOVER_BOX = {62, 1565, 130, 1637};
    public static final float STRIP_Y = 1520;
    public static final float[] P_PUSH = {460, 1600};          // pinball PUSH button   // where the on-screen markers are drawn

    public enum State { IDLE, BAIT_POPUP, WAITING, REEL, HOOKED, MINIGAME, CATCH, LUCK_POPUP, UNKNOWN }

    /** A captured frame: RGBA bytes with row stride. */
    public static class Frame {
        public byte[] data;
        public int w, h, stride;
        public long timeNs;

        int sx(float x) { return (int) (x / REF_W * w); }
        int sy(float y) { return (int) (y / REF_H * h); }
        int r(int x, int y) { return data[y * stride + x * 4] & 0xff; }
        int g(int x, int y) { return data[y * stride + x * 4 + 1] & 0xff; }
        int b(int x, int y) { return data[y * stride + x * 4 + 2] & 0xff; }
    }

    /** Average colour of a small square around a reference point. */
    public static int[] patch(Frame f, float[] p) {
        int cx = f.sx(p[0]), cy = f.sy(p[1]);
        int rad = Math.max(2, f.w / 300);
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = cy - rad; y <= cy + rad; y++) {
            for (int x = cx - rad; x <= cx + rad; x++) {
                if (x < 0 || y < 0 || x >= f.w || y >= f.h) continue;
                r += f.r(x, y); g += f.g(x, y); b += f.b(x, y); n++;
            }
        }
        if (n == 0) return new int[]{0, 0, 0};
        return new int[]{(int) (r / n), (int) (g / n), (int) (b / n)};
    }

    static boolean isGreen(int[] c) { return c[1] > 150 && c[0] < 150 && c[2] < 100; }
    static boolean isGrey(int[] c) {
        return Math.abs(c[0] - c[1]) < 15 && Math.abs(c[1] - c[2]) < 15 && c[0] > 60 && c[0] < 170;
    }
    static boolean isPurple(int[] c) { return c[2] > 180 && c[0] > 140 && c[1] < 120; }

    /** Result of reading the minigame bar, in reference coordinates. */
    public static class Bar {
        public float marker, zoneL, zoneR;
    }

    public static Bar findBar(Frame f) {
        float k = REF_W / f.w;
        int bx0 = f.sx(BAR_X0 - 30), bx1 = f.sx(BAR_X1 + 30);
        int y0 = f.sy(MARKER_Y0), y1 = f.sy(MARKER_Y1);
        // red bobber marker: median x of red pixels
        int[] hist = new int[bx1 - bx0 + 1];
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = bx0; x < bx1; x++) {
                if (f.r(x, y) > 190 && f.g(x, y) < 120 && f.b(x, y) < 120) {
                    hist[x - bx0]++;
                    count++;
                }
            }
        }
        int minRed = Math.max(6, (int) (20 * (f.w / 1189f) * (f.w / 1189f)));
        if (count < minRed) return null;
        int half = count / 2, acc = 0, medX = 0;
        for (int i = 0; i < hist.length; i++) {
            acc += hist[i];
            if (acc > half) { medX = i; break; }
        }
        float marker = (medX + bx0) * k;
        // yellow zone along the bar row, ignoring columns hidden by the marker
        int yy = f.sy(BAR_Y);
        float zl = Float.MAX_VALUE, zr = -1;
        int n = 0;
        for (int x = f.sx(BAR_X0); x < f.sx(BAR_X1); x++) {
            if (f.r(x, yy) > 220 && f.g(x, yy) > 200 && f.b(x, yy) < 120) {
                float rx = x * k;
                if (Math.abs(rx - marker) <= 32) continue;
                zl = Math.min(zl, rx);
                zr = Math.max(zr, rx);
                n++;
            }
        }
        if (n < 3) return null;
        Bar bar = new Bar();
        bar.marker = marker;
        bar.zoneL = zl;
        bar.zoneR = zr;
        return bar;
    }

    /** 24x24 mask of the pale-yellow luck number on the clover. */
    public static boolean[] cloverMask(Frame f) {
        int x0 = f.sx(CLOVER_BOX[0]), y0 = f.sy(CLOVER_BOX[1]);
        int x1 = f.sx(CLOVER_BOX[2]), y1 = f.sy(CLOVER_BOX[3]);
        boolean[] m = new boolean[24 * 24];
        for (int j = 0; j < 24; j++) {
            int y = y0 + (int) ((y1 - y0 - 1) * (j / 23f));
            for (int i = 0; i < 24; i++) {
                int x = x0 + (int) ((x1 - x0 - 1) * (i / 23f));
                m[j * 24 + i] = f.r(x, y) > 215 && f.g(x, y) > 225 && f.b(x, y) < 215;
            }
        }
        return m;
    }

    public static boolean sameMask(boolean[] a, boolean[] b) {
        int inter = 0, union = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] || b[i]) union++;
            if (a[i] && b[i]) inter++;
        }
        return union == 0 || inter / (float) union > 0.85f;
    }

    static boolean isCardPurple(int[] c) { return c[0] > 100 && c[0] < 190 && c[1] < 40 && c[2] > 180; }

    /** Fraction of pixels in a reference-space box that match a colour test. */
    interface Px { boolean ok(int r, int g, int b); }

    static float coverage(Frame f, float x0, float y0, float x1, float y1, Px t) {
        int n = 0, hit = 0;
        for (int y = f.sy(y0); y < f.sy(y1); y++) {
            for (int x = f.sx(x0); x < f.sx(x1); x++) {
                n++;
                if (t.ok(f.r(x, y), f.g(x, y), f.b(x, y))) hit++;
            }
        }
        return n == 0 ? 0 : hit / (float) n;
    }

    /** Green "Scatter Bait" button coverage (high = bag popup open with bait available). */
    public static float scatterGreen(Frame f) {
        return coverage(f, 600, 1318, 860, 1382, (r, g, b) -> g > 150 && r < 170 && b < 100 && g - r > 40);
    }

    /** Yellow "Notify Team" button coverage (high = bag popup is open at all). */
    public static float notifyYellow(Frame f) {
        return coverage(f, 600, 1425, 860, 1480, (r, g, b) -> r > 220 && g > 170 && b < 90);
    }

    public static boolean bagPopupOpen(Frame f) { return notifyYellow(f) > 0.3f; }
    public static boolean scatterAvailable(Frame f) { return scatterGreen(f) > 0.3f; }

    /** Pinball Machine screen: yellow PUSH button, cyan ball counter, bottom tab bar. */
    public static boolean isPinballScreen(Frame f) {
        int[] a = patch(f, new float[]{400, 1575}), b = patch(f, new float[]{520, 1630});
        int[] bar = patch(f, new float[]{340, 1770}), tab = patch(f, new float[]{95, 1880});
        return a[0] > 230 && a[1] > 210 && a[2] < 130 && b[0] > 230 && b[1] > 210 && b[2] < 150
                && bar[2] > 220 && bar[1] > 190 && bar[0] < 110
                && tab[0] > 180 && tab[1] > 220 && tab[2] > 220;
    }

    // ------------------------------------------------------------ Goldrush map
    /** Faction colours on the Island Goldrush map: blue, yellow, red, purple. */
    public static final int[][] FACTION_RGB = {{40, 160, 252}, {243, 212, 37}, {215, 68, 84}, {218, 118, 248}};
    public static final String[] FACTION_NAMES = {"Blue", "Yellow", "Red", "Purple"};

    /** Share of each faction colour in the map area (index 4 = total coverage of the area). */
    public static float[] factionShares(Frame f) {
        int[] n = new int[4];
        int total = 0;
        int step = Math.max(1, f.w / 270);
        for (int y = f.sy(330); y < f.sy(1650); y += step) {
            for (int x = 0; x < f.w; x += step) {
                total++;
                int r = f.r(x, y), g = f.g(x, y), b = f.b(x, y), best = -1, bd = 55 * 55;
                for (int i = 0; i < 4; i++) {
                    int dr = r - FACTION_RGB[i][0], dg = g - FACTION_RGB[i][1], db = b - FACTION_RGB[i][2];
                    int d = dr * dr + dg * dg + db * db;
                    if (d < bd) { bd = d; best = i; }
                }
                if (best >= 0) n[best]++;
            }
        }
        int owned = n[0] + n[1] + n[2] + n[3];
        float[] out = new float[5];
        for (int i = 0; i < 4; i++) out[i] = owned == 0 ? 0 : n[i] / (float) owned;
        out[4] = total == 0 ? 0 : owned / (float) total;
        return out;
    }

    /** Island Goldrush map: dark top bar plus mostly faction-coloured territory. */
    public static boolean isGoldrushMap(Frame f) {
        int[] top = patch(f, new float[]{700, 60});
        if (top[0] > 90 || top[1] > 90 || top[2] > 110) return false;
        float[] s = factionShares(f);
        int present = 0;
        for (int i = 0; i < 4; i++) if (s[i] > 0.04f) present++;
        return s[4] > 0.30f && present >= 2;
    }

    public enum Screen { FISHING, PINBALL, GOLDRUSH, OTHER }

    /** Which game screen is showing (used for automatic mode switching). */
    public static Screen screenOf(Frame f) {
        if (isPinballScreen(f)) return Screen.PINBALL;
        if (isGoldrushMap(f)) return Screen.GOLDRUSH;   // before fishing: map colours can fool it
        State s = stateOf(f, null);
        if (s != State.UNKNOWN) return Screen.FISHING;
        return Screen.OTHER;
    }

    static boolean isWhite(int[] c) { return c[0] > 225 && c[1] > 225 && c[2] > 215; }

    public static boolean isLuckPopup(Frame f) {
        int[] t = patch(f, P_LUCK_TITLE);
        return isWhite(patch(f, P_LUCK_WHITE1)) && isWhite(patch(f, P_LUCK_WHITE2))
                && t[2] > 130 && t[2] - t[0] > 40 && t[1] < 120;
    }

    /**
     * Counts rows in the Luck Level popup that have a timer (clock icon) - i.e. active
     * scattered baits. Felicia's Rod has no timer, so it isn't counted.
     */
    public static int countBaitRows(Frame f) {
        int x0 = f.sx(700), x1 = f.sx(726);
        int y0 = f.sy(1000), y1 = f.sy(1800);
        int minRun = Math.max(2, f.sy(18) - f.sy(0));
        int rows = 0, run = 0;
        for (int y = y0; y < y1; y++) {
            boolean hit = false;
            for (int x = x0; x < x1 && !hit; x++) {
                int r = f.r(x, y), g = f.g(x, y), b = f.b(x, y);
                hit = b > 130 && b - r > 45 && r < 140 && g < 125;
            }
            if (hit) run++;
            else {
                if (run >= minRun) rows++;
                run = 0;
            }
        }
        if (run >= minRun) rows++;
        return rows;
    }

    public static State stateOf(Frame f, Bar[] barOut) {
        if (isLuckPopup(f)) return State.LUCK_POPUP;
        int[] btn = patch(f, P_BTN_SAMPLE);
        if (isCardPurple(patch(f, P_CARD_L)) && isCardPurple(patch(f, P_CARD_R))
                && btn[0] < 110 && btn[1] < 60) return State.CATCH;
        if (isGreen(btn) && (bagPopupOpen(f) || scatterAvailable(f))) return State.BAIT_POPUP;
        if (isGreen(btn)) return State.IDLE;
        if (isGrey(btn)) return State.WAITING;
        if (isPurple(btn)) {
            Bar bar = findBar(f);
            if (bar != null) {
                if (barOut != null) barOut[0] = bar;
                return State.MINIGAME;
            }
            int[] p = patch(f, P_REEL_PANEL);
            if (p[2] > 120 && p[1] < 110 && p[2] > p[0]) return State.REEL;
            return State.HOOKED;
        }
        return State.UNKNOWN;
    }
}
