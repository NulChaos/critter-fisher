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
    private TextView overlayBtn, tapBtn, startBtn, updateTv, updateBtn;
    private String pendingApk;
    private boolean installing = false;

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
        root.addView(text("Android will ask to share your screen - allow it. Keep the panel near the top of the screen. Then open an event and the panel switches to it automatically.",
                13, 0xFF8FA0BD));
        com.nul.critterfisher.Settings.load(this);
        TextView singleBtn = button("");
        Runnable paintSingle = () -> {
            boolean on = com.nul.critterfisher.Settings.shareSingleApp;
            singleBtn.setText(on ? "Experimental: share only the game (ON)"
                    : "Experimental: share only the game (off)");
            paint(singleBtn, on ? 0xFF8A5A1A : 0xFF3A4660);
        };
        paintSingle.run();
        singleBtn.setOnClickListener(v -> {
            com.nul.critterfisher.Settings.shareSingleApp = !com.nul.critterfisher.Settings.shareSingleApp;
            com.nul.critterfisher.Settings.save(this);
            paintSingle.run();
        });
        root.addView(singleBtn);
        root.addView(text("Leave this OFF if your phone ever froze after sharing. It lets Android 14+ offer \"A single app\", which keeps the panel out of what the bot sees, but it caused a full freeze on at least one Samsung phone.",
                12, 0xFFB08A5A));

        updateTv = text("Build " + Updater.currentBuild(this), 13, 0xFF8FA0BD);
        updateTv.setPadding(0, dp(24), 0, 0);
        root.addView(updateTv);
        updateBtn = button("Check for updates");
        paint(updateBtn, 0xFF3A4660);
        updateBtn.setOnClickListener(v -> {
            if (pendingApk != null) installUpdate(); else checkUpdate();
        });
        root.addView(updateBtn);

        if (getIntent().getBooleanExtra("updated", false)) {
            Toast.makeText(this, "Updated to build " + Updater.currentBuild(this)
                    + " - tap Start again", Toast.LENGTH_LONG).show();
        }

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
        if (pendingApk != null && !installing && getPackageManager().canRequestPackageInstalls()) {
            installUpdate();          // came back from granting the permission
        } else if (pendingApk == null) {
            checkUpdate();
        }
    }

    private void checkUpdate() {
        int cur = Updater.currentBuild(this);
        updateTv.setText("Build " + cur + " · checking for updates...");
        Updater.check((latest, url, err) -> {
            if (err != null) { updateTv.setText("Build " + cur + " · update check failed: " + err); return; }
            if (latest <= cur) { updateTv.setText("Build " + cur + " · up to date"); return; }
            pendingApk = url;
            updateTv.setText("Build " + cur + " · build " + latest + " available");
            updateBtn.setText("Update to build " + latest);
            paint(updateBtn, 0xFF5B4BB0);
            installUpdate();          // automatic
        });
    }

    private void installUpdate() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            updateTv.setText("Allow Critter Fisher to install updates (one time), then come back");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        installing = true;
        Updater.install(this, pendingApk, s -> updateTv.setText(s));
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
        // Default: whole screen (what builds 1-10 used). Single-app sharing is opt-in because
        // it froze a phone right after sharing.
        boolean single = com.nul.critterfisher.Settings.shareSingleApp;
        Intent i = (Build.VERSION.SDK_INT >= 34 && !single)
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
