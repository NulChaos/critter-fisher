package com.nul.critterfisher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/** Accessibility service used only to inject taps. */
public class TapService extends AccessibilityService {
    public static volatile TapService instance;

    @Override protected void onServiceConnected() { instance = this; }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }

    /** Tap at absolute screen pixels. Returns false if the service is off. */
    public static boolean tap(float x, float y) {
        TapService s = instance;
        if (s == null) return false;
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 30))
                .build();
        return s.dispatchGesture(g, null, null);
    }
}
