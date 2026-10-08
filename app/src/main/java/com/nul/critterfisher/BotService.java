package com.nul.critterfisher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.nio.ByteBuffer;

/** Foreground service: screen capture + floating control panel + bot thread. */
public class BotService extends Service implements Bot.Host {
    public static final String ACTION_STOP = "stop";
    public static volatile boolean active = false;

    private WindowManager wm;
    private MediaProjection projection;
    private VirtualDisplay vd;
    private ImageReader reader;
    private HandlerThread capThread;
    private Bot bot;
    private Thread botThread;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int realW, realH;
    private final Detect.Frame frame = new Detect.Frame();
    private boolean haveFrame = false;

    // overlay views
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView statusTv, playBtn;
    private BarView miniBar, strip;
    private LinearLayout settingsBox;
    private final Bot.Status shown = new Bot.Status();

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (projection != null || intent == null) return START_NOT_STICKY;

        startInForeground();
        Settings.load(this);

        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) { stopSelf(); return START_NOT_STICKY; }

        capThread = new HandlerThread("capture");
        capThread.start();
        Handler capHandler = new Handler(capThread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { main.post(BotService.this::stopSelf); }
        }, capHandler);

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        realW = dm.widthPixels;
        realH = dm.heightPixels;
        int capW = realW / 2, capH = realH / 2;     // half resolution is plenty and faster
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 3);
        vd = projection.createVirtualDisplay("critterfisher", capW, capH, dm.densityDpi / 2,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, capHandler);

        buildOverlay();
        active = true;
        bot = new Bot(this);
        botThread = new Thread(bot, "bot");
        botThread.setPriority(Thread.MAX_PRIORITY);
        botThread.start();
        shown.message = "Open the Fishing Contest, then press ▶";
        refreshUi();
        return START_NOT_STICKY;
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("cf", "Critter Fisher",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BotService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "cf")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Critter Fisher is running")
                .setContentText("Use the floating panel to start/pause")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, n);
        }
    }

    @Override public void onDestroy() {
        active = false;
        if (bot != null) { bot.alive = false; bot.running = false; }
        try { if (panel != null) wm.removeView(panel); } catch (Exception ignored) { }
        try { if (strip != null) wm.removeView(strip); } catch (Exception ignored) { }
        if (vd != null) vd.release();
        if (projection != null) projection.stop();
        if (reader != null) reader.close();
        if (capThread != null) capThread.quitSafely();
        Settings.save(this);
        super.onDestroy();
    }

    // ------------------------------------------------------------ Bot.Host
    @Override public synchronized Detect.Frame next(long timeoutMs) {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        do {
            Image img = null;
            try { img = reader.acquireLatestImage(); } catch (Exception ignored) { }
            if (img != null) {
                try {
                    Image.Plane p = img.getPlanes()[0];
                    ByteBuffer buf = p.getBuffer();
                    int need = p.getRowStride() * img.getHeight();
                    if (frame.data == null || frame.data.length != need) frame.data = new byte[need];
                    buf.rewind();
                    buf.get(frame.data, 0, Math.min(buf.remaining(), need));
                    frame.w = img.getWidth();
                    frame.h = img.getHeight();
                    frame.stride = p.getRowStride();
                    frame.timeNs = img.getTimestamp();
                    haveFrame = true;
                } finally {
                    img.close();
                }
                return frame;
            }
            try { Thread.sleep(2); } catch (InterruptedException e) { break; }
        } while (System.nanoTime() < end);
        return haveFrame ? frame : null;
    }

    @Override public boolean tap(float[] p) {
        return TapService.tap(p[0] / Detect.REF_W * realW, p[1] / Detect.REF_H * realH);
    }

    @Override public void onStatus(Bot.Status s) {
        final Bot.Status c = new Bot.Status();
        c.state = s.state; c.running = s.running; c.message = s.message; c.bait = s.bait;
        c.catches = s.catches; c.fps = s.fps; c.zoneL = s.zoneL; c.zoneR = s.zoneR;
        c.innerL = s.innerL; c.innerR = s.innerR; c.marker = s.marker; c.tapX = s.tapX;
        c.tapAtMs = s.tapAtMs;
        main.post(() -> {
            shown.state = c.state; shown.running = c.running; shown.message = c.message;
            shown.bait = c.bait;
            shown.catches = c.catches; shown.fps = c.fps; shown.zoneL = c.zoneL;
            shown.zoneR = c.zoneR; shown.innerL = c.innerL; shown.innerR = c.innerR;
            shown.marker = c.marker; shown.tapX = c.tapX; shown.tapAtMs = c.tapAtMs;
            refreshUi();
        });
    }

    // ------------------------------------------------------------- overlay
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private TextView button(String s, int bg) {
        TextView b = text(s, 15, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(8));
        b.setBackground(d);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private int overlayType() {
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    private void buildOverlay() {
        // ---- control panel
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6182030);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x6688AAFF);
        panel.setBackground(bg);
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("≡ Critter Fisher", 14, 0xFFBFD4FF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(4), dp(4), dp(4));
        playBtn = button("▶", 0xFF2E9E4F);
        TextView gear = button("⚙", 0xFF3A4660);
        TextView close = button("✕", 0xFF8A2D3A);
        header.addView(title);
        header.addView(playBtn);
        header.addView(gear);
        header.addView(close);
        panel.addView(header);

        statusTv = text("", 12, Color.WHITE);
        statusTv.setTypeface(Typeface.MONOSPACE);
        statusTv.setPadding(0, dp(4), 0, dp(4));
        panel.addView(statusTv);

        miniBar = new BarView(this, true);
        panel.addView(miniBar, new LinearLayout.LayoutParams(dp(250), dp(26)));

        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        settingsBox.setVisibility(View.GONE);
        addSlider("Tap timing (latency)", "ms", 0, 300, Settings.tapLatencyMs,
                v -> Settings.tapLatencyMs = v);
        addSlider("Aim: stay off zone edges", "%", 0, 45, Settings.aimInsetPct,
                v -> Settings.aimInsetPct = v);
        addSlider("Reel-in tap interval", "ms", 40, 400, Settings.reelTapMs,
                v -> Settings.reelTapMs = v);
        addCheck("Use bait (until luck limit)", Settings.useBait, v -> Settings.useBait = v);
        addSlider("Bait recheck after limit", "s", 30, 600, Settings.baitRecheckSec,
                v -> Settings.baitRecheckSec = v);
        addCheck("Show markers on the game", Settings.showMarkers, v -> {
            Settings.showMarkers = v;
            strip.invalidate();
        });
        TextView baitNow = button("Try bait now", 0xFF5B4BB0);
        baitNow.setOnClickListener(v -> {
            bot.forceBait = true;
            shown.message = "Will try bait on the next idle screen";
            refreshUi();
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(8);
        settingsBox.addView(baitNow, bl);
        panel.addView(settingsBox);

        playBtn.setOnClickListener(v -> {
            if (TapService.instance == null) {
                shown.message = "Tap service is OFF - enable it in Accessibility";
                refreshUi();
                return;
            }
            bot.running = !bot.running;
            shown.running = bot.running;
            shown.message = bot.running ? "Running" : "Paused";
            refreshUi();
        });
        gear.setOnClickListener(v -> {
            boolean open = settingsBox.getVisibility() != View.VISIBLE;
            settingsBox.setVisibility(open ? View.VISIBLE : View.GONE);
            if (!open) Settings.save(this);
        });
        close.setOnClickListener(v -> stopSelf());

        panelLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = dp(8);
        panelLp.y = (int) (realH * 0.11f);    // top area: nothing the bot reads is there
        title.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = panelLp.x; oy = panelLp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelLp.x = ox + (int) (e.getRawX() - sx);
                        panelLp.y = oy + (int) (e.getRawY() - sy);
                        wm.updateViewLayout(panel, panelLp);
                        return true;
                }
                return false;
            }
        });
        wm.addView(panel, panelLp);

        // ---- thin marker strip just under the game's timing bar (not touchable)
        strip = new BarView(this, false);
        WindowManager.LayoutParams sl = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(10), realH / 160),
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        sl.gravity = Gravity.TOP | Gravity.START;
        sl.y = (int) (Detect.STRIP_Y / Detect.REF_H * realH);
        sl.alpha = 0.8f;   // keeps Android from blocking touches that pass through
        if (Build.VERSION.SDK_INT >= 28) {
            sl.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) sl.setFitInsetsTypes(0);
        wm.addView(strip, sl);
    }

    interface IntSetter { void set(int v); }
    interface BoolSetter { void set(boolean v); }

    private void addSlider(String label, String unit, int min, int max, int value, IntSetter s) {
        TextView tv = text(label + ": " + value + unit, 12, 0xFFDDE6FF);
        tv.setPadding(0, dp(6), 0, 0);
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                s.set(p + min);
                tv.setText(label + ": " + (p + min) + unit);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { Settings.save(BotService.this); }
        });
        settingsBox.addView(tv);
        settingsBox.addView(sb, new LinearLayout.LayoutParams(dp(250), dp(32)));
    }

    private void addCheck(String label, boolean value, BoolSetter s) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(0xFFDDE6FF);
        cb.setTextSize(12);
        cb.setChecked(value);
        cb.setOnCheckedChangeListener((b, v) -> { s.set(v); Settings.save(this); });
        settingsBox.addView(cb);
    }

    private void refreshUi() {
        if (statusTv == null) return;
        playBtn.setText(shown.running ? "❚❚" : "▶");
        String tapState = TapService.instance == null ? "  [taps OFF]" : "";
        statusTv.setText(String.format("%s · %dfps · catches %d%s\n%s\n%s",
                shown.running ? shown.state.name().toLowerCase() : "paused",
                shown.fps, shown.catches, tapState, shown.bait, shown.message));
        miniBar.invalidate();
        strip.invalidate();
    }

    /** Draws the bar: yellow zone, white aim window, cyan marker, magenta last tap. */
    class BarView extends View {
        final boolean inPanel;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        BarView(Context c, boolean inPanel) { super(c); this.inPanel = inPanel; }

        float map(float refX) {
            if (inPanel) {
                return (refX - Detect.BAR_X0) / (Detect.BAR_X1 - Detect.BAR_X0) * getWidth();
            }
            return refX / Detect.REF_W * getWidth();
        }

        @Override protected void onDraw(Canvas c) {
            int h = getHeight();
            boolean recentTap = System.currentTimeMillis() - shown.tapAtMs < 700 && shown.tapX >= 0;
            if (inPanel) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF263248);
                c.drawRoundRect(0, h * 0.2f, getWidth(), h * 0.8f, h * 0.3f, h * 0.3f, p);
            } else if (!Settings.showMarkers || (shown.zoneL < 0 && !recentTap)) {
                return;
            }
            if (shown.zoneL >= 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(inPanel ? 0xFFE8C431 : 0x5539FF6A);
                c.drawRect(map(shown.zoneL), h * 0.2f, map(shown.zoneR), h * 0.8f, p);
                p.setColor(inPanel ? 0xFFFFFFFF : 0xCCFFFFFF);
                c.drawRect(map(shown.innerL), h * 0.35f, map(shown.innerR), h * 0.65f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setColor(0xFF39FF6A);
                c.drawRect(map(shown.zoneL), h * 0.15f, map(shown.zoneR), h * 0.85f, p);
            }
            if (shown.marker >= 0) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFF2BD9FF);
                float x = map(shown.marker);
                c.drawRect(x - dp(2), 0, x + dp(2), h, p);
            }
            if (recentTap) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFF3BD5);
                float x = map(shown.tapX);
                c.drawCircle(x, h / 2f, h * 0.45f, p);
            }
        }
    }
}
