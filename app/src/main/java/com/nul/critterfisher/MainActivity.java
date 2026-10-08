package com.nul.critterfisher;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Setup screen: grant overlay + tap service, then start the floating panel. */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 42;
    private TextView overlayBtn, tapBtn, startBtn;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private TextView button(String s) {
        TextView b = text(s, 17, Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void paint(TextView b, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(12));
        b.setBackground(d);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(20));
        root.setBackgroundColor(0xFF10151F);

        TextView title = text("Critter Fisher", 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("Fishing Contest bot with a floating control panel.", 15, 0xFFAFC0DD));

        overlayBtn = button("");
        overlayBtn.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        root.addView(overlayBtn);

        tapBtn = button("");
        tapBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(tapBtn);
        root.addView(text("If Android says the setting is restricted: open App info for Critter Fisher → ⋮ menu (top right) → Allow restricted settings, then try again.",
                13, 0xFF8FA0BD));

        startBtn = button("3. Start floating panel");
        startBtn.setOnClickListener(v -> startCapture());
        root.addView(startBtn);
        root.addView(text("When asked, choose to share the ENTIRE screen. Then open the Fishing Contest and press ▶ on the panel. Keep the panel near the top of the screen so it doesn't cover the bar or buttons.",
                13, 0xFF8FA0BD));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        sv.setBackgroundColor(0xFF10151F);
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        boolean overlay = Settings.canDrawOverlays(this);
        boolean taps = TapService.instance != null;
        overlayBtn.setText((overlay ? "✓ " : "1. ") + "Allow display over other apps");
        paint(overlayBtn, overlay ? 0xFF24563A : 0xFF2F5BD3);
        tapBtn.setText((taps ? "✓ " : "2. ") + "Turn on \"Critter Fisher taps\" (Accessibility)");
        paint(tapBtn, taps ? 0xFF24563A : 0xFF2F5BD3);
        boolean ready = overlay && taps;
        startBtn.setText(BotService.active ? "Panel is running (tap to restart)" : "3. Start floating panel");
        paint(startBtn, ready ? 0xFF2E9E4F : 0xFF444C5C);
    }

    private void startCapture() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps first", Toast.LENGTH_LONG).show();
            return;
        }
        if (TapService.instance == null) {
            Toast.makeText(this, "Turn on the tap service first", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
        if (BotService.active) stopService(new Intent(this, BotService.class));
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent i = Build.VERSION.SDK_INT >= 34
                ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
        startActivityForResult(i, REQ_CAPTURE);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        if (res != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture is needed to see the game", Toast.LENGTH_LONG).show();
            return;
        }
        Intent svc = new Intent(this, BotService.class).putExtra("code", res).putExtra("data", data);
        startForegroundService(svc);
        Toast.makeText(this, "Panel started - open the Fishing Contest", Toast.LENGTH_LONG).show();
        moveTaskToBack(true);
    }
}
