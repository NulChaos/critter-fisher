package com.nul.critterfisher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.InputStream;

/**
 * Finds the Shaman by shape (OpenCV template matching), whether it is on screen (its tiki
 * face) or off screen (its face inside the red pointer at the screen edge). Templates were
 * cut from the user's screenshots at a 720-px-wide scale; matching runs at half that.
 * Prototype results: Shaman frames 0.79-0.95, everything else <= 0.54.
 */
public class ShamanDetector {
    static final double WORK = 0.35;                      // 720-wide reference -> 252-wide work image (fast)
    static final double[] SCALES = {0.75, 0.9, 1.05, 1.2};
    public static final float THRESHOLD = 0.60f;          // "decent" match
    public static final float STRONG = 0.66f;             // one of these is enough

    private final Mat[][] templates;                      // [template][scale]
    private final Mat rgba = new Mat(), rgb = new Mat(), small = new Mat(), result = new Mat();
    public volatile float lastScore = 0;
    public volatile long lastMs = 0;
    public volatile int lastX = -1, lastY = -1;          // reference coords of the best match

    public static ShamanDetector create(Context c) {
        try {
            if (!OpenCVLoader.initLocal()) return null;
            return new ShamanDetector(c);
        } catch (Throwable t) {
            return null;
        }
    }

    private ShamanDetector(Context c) throws Exception {
        String[] names = {"shaman_sprite.png", "shaman_portrait.png", "shaman_portrait_edge.png"};
        templates = new Mat[names.length][SCALES.length];
        for (int i = 0; i < names.length; i++) {
            Bitmap b;
            try (InputStream in = c.getAssets().open(names[i])) { b = BitmapFactory.decodeStream(in); }
            Mat t = new Mat();
            Utils.bitmapToMat(b, t);                          // RGBA
            Imgproc.cvtColor(t, t, Imgproc.COLOR_RGBA2RGB);
            for (int k = 0; k < SCALES.length; k++) {
                Mat s = new Mat();
                double f = WORK * SCALES[k];
                Imgproc.resize(t, s, new Size(Math.max(4, t.cols() * f), Math.max(4, t.rows() * f)),
                        0, 0, Imgproc.INTER_AREA);
                templates[i][k] = s;
            }
        }
    }

    /** Best match score (0..1) for the Shaman in this frame's arena area. */
    public synchronized float score(Detect.Frame f) {
        long t0 = System.nanoTime();
        try { return scoreInner(f); } finally { lastMs = (System.nanoTime() - t0) / 1_000_000L; }
    }

    private float scoreInner(Detect.Frame f) {
        if (rgba.rows() != f.h || rgba.cols() != f.stride / 4) {
            rgba.release();
            rgba.create(f.h, f.stride / 4, CvType.CV_8UC4);
        }
        rgba.put(0, 0, f.data);
        Mat view = rgba.submat(0, f.h, 0, f.w);
        Imgproc.cvtColor(view, rgb, Imgproc.COLOR_RGBA2RGB);
        // to the 720-wide reference scale, then to the work scale
        double toWork = (720.0 / f.w) * WORK;
        Imgproc.resize(rgb, small, new Size(f.w * toWork, f.h * toWork), 0, 0, Imgproc.INTER_AREA);
        int top = (int) (170 * WORK), bottom = Math.min(small.rows(), (int) (1450 * WORK));
        Mat arena = small.submat(new Rect(0, top, small.cols(), bottom - top));
        float best = 0;
        int bx = -1, by = -1;
        for (Mat[] perScale : templates) {
            for (Mat t : perScale) {
                if (t.rows() >= arena.rows() || t.cols() >= arena.cols()) continue;
                Imgproc.matchTemplate(arena, t, result, Imgproc.TM_CCOEFF_NORMED);
                Core.MinMaxLocResult mm = Core.minMaxLoc(result);
                if (mm.maxVal > best) {
                    best = (float) mm.maxVal;
                    bx = (int) mm.maxLoc.x; by = (int) mm.maxLoc.y;
                }
            }
        }
        view.release();
        arena.release();
        lastScore = best;
        // back to reference (923 x 1999) coordinates for display / logs
        lastX = bx < 0 ? -1 : (int) (bx / WORK * Detect.REF_W / 720.0);
        lastY = by < 0 ? -1 : (int) ((by / WORK + 170) * Detect.REF_H / 1560.0);
        return best;
    }
}
