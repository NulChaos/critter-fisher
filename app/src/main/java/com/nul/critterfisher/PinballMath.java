package com.nul.critterfisher;

/**
 * Pinball expected-value math. Slot odds and payouts from the community wiki
 * (clashofcritters.wiki.gg/wiki/Pinball); rewards scale linearly with the multiplier.
 */
public class PinballMath {
    public static final String[] NAMES = {"Event items (cans/bulbs)", "Fertilizer", "Fishing rods",
            "Skateboards", "Energy drinks", "Pickaxes"};
    static final double[] HIT_P = {0.255, 0.22, 0.22, 0.22, 0.22, 0.017};
    static final int[] PER_HIT = {4, 2, 1, 1, 1, 15};      // per hit at x1
    public static final int[] MULTS = {1, 2, 5, 10, 20};
    static final double Z95 = 1.645;

    /** Average pinballs needed for the target (same at every multiplier). */
    public static int average(int cur, int target) {
        return (int) Math.ceil(target / (HIT_P[cur] * PER_HIT[cur]));
    }

    /** P(at least `hits` successes in n launches), exact binomial. */
    static double atLeast(long n, int hits, double p) {
        if (hits <= 0) return 1;
        if (n < hits) return 0;
        double logP = Math.log(p), logQ = Math.log1p(-p), below = 0, logC = 0;
        for (int k = 0; k < hits; k++) {
            if (k > 0) logC += Math.log((double) (n - k + 1) / k);
            below += Math.exp(logC + k * logP + (n - k) * logQ);
        }
        return 1 - below;
    }

    /** Pinballs to be ~95% sure of reaching the target at a given multiplier. */
    public static int sure95(int cur, int target, int mult) {
        double p = HIT_P[cur];
        int hits = (int) Math.ceil(target / (double) (PER_HIT[cur] * mult));
        long lo = hits, hi = Math.max(hits, (long) (hits / p * 3 + 50));
        while (atLeast(hi, hits, p) < 0.95) hi *= 2;
        while (lo < hi) {
            long mid = (lo + hi) / 2;
            if (atLeast(mid, hits, p) >= 0.95) hi = mid; else lo = mid + 1;
        }
        return (int) (lo * mult);
    }

    public static String describe(int cur, int target) {
        StringBuilder b = new StringBuilder();
        b.append(String.format("%d %s ≈ %d pinballs on average\n95%% sure: ", target,
                NAMES[cur].toLowerCase(), average(cur, target)));
        for (int i = 0; i < MULTS.length; i++) {
            if (i > 0) b.append(" · ");
            b.append("x").append(MULTS[i]).append(' ').append(sure95(cur, target, MULTS[i]));
        }
        int low = sure95(cur, target, 1), high = sure95(cur, target, 5);
        b.append(String.format("\nTip: x1 needs the smallest buffer; x5 costs ~%d more "
                + "for the same certainty but 5x fewer presses.", high - low));
        return b.toString();
    }
}
