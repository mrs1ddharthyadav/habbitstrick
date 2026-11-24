package com.habbitstrick.app;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;

/** Shared settings and maths for the Phone quiet habit. */
class Quiet {

    static SharedPreferences p(Context c) {
        return Reminders.prefs(c);
    }

    static JSONObject cfg(Context c) {
        try {
            return new JSONObject(p(c).getString("q_cfg", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static boolean on(Context c) {
        return cfg(c).optBoolean("on", false);
    }

    static String today() {
        return Reminders.ymd(Calendar.getInstance());
    }

    static boolean overlayOk(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static boolean accOk(Context c) {
        try {
            String v = android.provider.Settings.Secure.getString(c.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return v != null && v.contains(c.getPackageName() + "/" + LockAccess.class.getName());
        } catch (Exception e) {
            return false;
        }
    }

    static boolean usageOk(Context c) {
        try {
            AppOpsManager a = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            int m;
            if (Build.VERSION.SDK_INT >= 29) {
                m = a.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            } else {
                m = a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
            }
            return m == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    static void startService(Context c) {
        try {
            Intent i = new Intent(c, QuietService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
        }
    }

    static void stopService(Context c) {
        try {
            c.stopService(new Intent(c, QuietService.class));
        } catch (Exception ignored) {
        }
    }

    /** Starts a fresh day: saves yesterday's numbers and clears today's counters. */
    static void rollDay(Context c) {
        SharedPreferences sp = p(c);
        String t = today();
        String d = sp.getString("q_day", "");
        if (d.equals(t)) return;
        SharedPreferences.Editor ed = sp.edit();
        if (!d.isEmpty()) {
            try {
                JSONObject h = new JSONObject(sp.getString("q_hist", "{}"));
                JSONObject e = new JSONObject();
                e.put("m", (int) (sp.getLong("q_used", 0) / 60));
                e.put("l", sp.getInt("q_locks", 0));
                h.put(d, e);
                // keep about two months
                if (h.length() > 62) {
                    String oldest = null;
                    Iterator<String> it = h.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (oldest == null || k.compareTo(oldest) < 0) oldest = k;
                    }
                    if (oldest != null) h.remove(oldest);
                }
                ed.putString("q_hist", h.toString());
            } catch (Exception ignored) {
            }
        }
        ed.putString("q_day", t)
                .putLong("q_used", 0)
                .putInt("q_locks", 0)
                .putInt("q_level", 0)
                .putLong("q_lockEnd", 0)
                .putLong("q_hideUntil", 0)
                .putBoolean("q_warned", false)
                .apply();
    }

    static int[] ints(JSONObject cfg, String key, int[] def) {
        JSONArray a = cfg.optJSONArray(key);
        if (a == null || a.length() == 0) return def;
        int[] r = new int[a.length()];
        for (int i = 0; i < r.length; i++) r[i] = Math.max(1, a.optInt(i, 5));
        return r;
    }

    /** Screen seconds at which lock number (level + 1) starts: limit, then each gap added up. */
    static long threshold(JSONObject cfg, int level) {
        int[] gaps = ints(cfg, "gaps", new int[]{30, 20, 5, 5});
        long t = Math.max(1, cfg.optInt("limit", 60)) * 60L;
        for (int i = 0; i <= level; i++) {
            t += gaps[Math.min(i, gaps.length - 1)] * 60L;
        }
        return t;
    }

    static int lockMinutes(JSONObject cfg, int level) {
        int[] locks = ints(cfg, "locks", new int[]{5, 5, 10, 20, 40, 60});
        return Math.min(60, locks[Math.min(level, locks.length - 1)]);
    }

    static String history(Context c) {
        SharedPreferences sp = p(c);
        rollDay(c);
        try {
            JSONObject h = new JSONObject(sp.getString("q_hist", "{}"));
            JSONObject e = new JSONObject();
            e.put("m", (int) (sp.getLong("q_used", 0) / 60));
            e.put("l", sp.getInt("q_locks", 0));
            h.put(today(), e);
            return h.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    static String status(Context c) {
        SharedPreferences sp = p(c);
        rollDay(c);
        JSONObject cfg = cfg(c);
        JSONObject o = new JSONObject();
        try {
            int level = sp.getInt("q_level", 0);
            o.put("on", cfg.optBoolean("on", false));
            o.put("used", sp.getLong("q_used", 0));
            o.put("limit", cfg.optInt("limit", 60));
            o.put("locks", sp.getInt("q_locks", 0));
            o.put("lockEnd", sp.getLong("q_lockEnd", 0));
            o.put("next", threshold(cfg, level));
            o.put("nextLock", lockMinutes(cfg, level));
            o.put("overlayOk", overlayOk(c));
            o.put("usageOk", usageOk(c));
            o.put("accOk", accOk(c));
            o.put("notif", Reminders.notifStatus(c));
        } catch (Exception ignored) {
        }
        return o.toString();
    }
}
