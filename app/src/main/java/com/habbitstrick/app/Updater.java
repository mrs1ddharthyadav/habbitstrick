package com.habbitstrick.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Checks the GitHub release for a newer build and downloads it. */
public class Updater {

    static final String BASE = "https://github.com/mrs1ddharthyadav/habbitstrick/releases/download/latest/";
    static final String VERSION_URL = BASE + "version.json";
    static final String APK_URL = BASE + "Habbit-Strick.apk";

    interface Progress {
        void on(int pct);
    }

    static PackageInfo info(Context c) throws Exception {
        return c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
    }

    static long installedCode(Context c) {
        try {
            PackageInfo pi = info(c);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            return info(c).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(12000);
        con.setReadTimeout(20000);
        con.setInstanceFollowRedirects(true);
        con.setRequestProperty("User-Agent", "HabbitStrick");
        con.setRequestProperty("Cache-Control", "no-cache");
        return con;
    }

    /** Returns a JSON string for the web page. Never throws. */
    static String check(Context c, boolean manual) {
        JSONObject out = new JSONObject();
        try {
            out.put("manual", manual);
            out.put("cur", installedName(c) + " (" + installedCode(c) + ")");
            HttpURLConnection con = open(VERSION_URL);
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = con.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 100000) throw new IOException("too big");
                }
            }
            JSONObject v = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            long latest = v.optLong("versionCode", 0);
            out.put("has", latest > installedCode(c));
            out.put("code", latest);
            out.put("name", "v" + v.optString("versionName", ""));
            out.put("notes", v.optString("notes", ""));
        } catch (Exception e) {
            try {
                out.put("has", false);
                out.put("err", manual ? "Could not check for updates. Check your internet and try again." : "");
            } catch (Exception ignored) {
            }
        }
        return out.toString();
    }

    /** Downloads the APK into the cache folder and checks it belongs to this app. */
    static File download(Context c, Progress cb) throws Exception {
        File dir = new File(c.getCacheDir(), "updates");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "Habbit-Strick.apk");
        if (f.exists()) f.delete();
        HttpURLConnection con = open(APK_URL);
        con.setReadTimeout(30000);
        int code = con.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code);
        long total = con.getContentLengthLong();
        long got = 0;
        int last = -1;
        try (InputStream is = con.getInputStream(); FileOutputStream os = new FileOutputStream(f)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                if (total > 0) {
                    int pct = (int) (got * 100 / total);
                    if (pct != last) {
                        last = pct;
                        cb.on(pct);
                    }
                }
            }
        }
        if (got < 500000 || (total > 0 && got != total)) {
            f.delete();
            throw new IOException("incomplete download");
        }
        PackageInfo pi = c.getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(), 0);
        if (pi == null || !c.getPackageName().equals(pi.packageName)) {
            f.delete();
            throw new IOException("not a Habbit Strick update");
        }
        long newCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        if (newCode <= installedCode(c)) {
            f.delete();
            throw new IOException("not newer");
        }
        return f;
    }
}
