package com.nul.critterfisher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks GitHub Releases for a newer build and installs it. */
public class Updater {
    public static final String REPO = "NulChaos/critter-fisher";

    public interface Callback { void onResult(int latestBuild, String apkUrl, String error); }
    public interface Progress { void onStatus(String s); }

    public static int currentBuild(Context c) {
        try {
            return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (Exception e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "CritterFisher");
        return con;
    }

    /** Looks up the latest release on a background thread; callback on the main thread. */
    public static void check(Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            int build = -1;
            String apk = null, err = null;
            try {
                HttpURLConnection con = open("https://api.github.com/repos/" + REPO + "/releases/latest");
                con.setRequestProperty("Accept", "application/vnd.github+json");
                if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                try (InputStream in = con.getInputStream()) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                }
                JSONObject rel = new JSONObject(bo.toString("UTF-8"));
                String tag = rel.getString("tag_name");               // build-N
                build = Integer.parseInt(tag.replaceAll("[^0-9]", ""));
                JSONArray assets = rel.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url");
                }
                if (apk == null) err = "no APK in latest release";
            } catch (Exception e) {
                err = e.getMessage();
            }
            final int fb = build;
            final String fa = apk, fe = err;
            main.post(() -> cb.onResult(fb, fa, fe));
        }, "update-check").start();
    }

    /** Downloads the APK and hands it to the system installer. */
    public static void install(Context ctx, String apkUrl, Progress p) {
        Context c = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                main.post(() -> p.onStatus("Downloading update..."));
                HttpURLConnection con = open(apkUrl);
                if (con.getResponseCode() != 200) throw new Exception("download HTTP " + con.getResponseCode());
                PackageInstaller pi = c.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(c.getPackageName());
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                }
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    long len = con.getContentLengthLong();
                    try (InputStream in = con.getInputStream();
                         OutputStream out = s.openWrite("app.apk", 0, len > 0 ? len : -1)) {
                        byte[] b = new byte[65536];
                        int n;
                        while ((n = in.read(b)) > 0) out.write(b, 0, n);
                        s.fsync(out);
                    }
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent done = PendingIntent.getBroadcast(c, id,
                            new Intent(c, InstallReceiver.class), flags);
                    main.post(() -> p.onStatus("Installing update..."));
                    s.commit(done.getIntentSender());
                }
            } catch (Exception e) {
                main.post(() -> p.onStatus("Update failed: " + e.getMessage()));
            }
        }, "update-install").start();
    }
}
