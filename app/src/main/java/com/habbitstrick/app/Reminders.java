package com.habbitstrick.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class Reminders {

    static final String PREFS = "hs";
    static final String ACTION_REMIND = "com.habbitstrick.app.REMIND";
    static final String ACTION_ONCE = "com.habbitstrick.app.REMIND_ONCE";
    static final String ACTION_SNOOZE = "com.habbitstrick.app.SNOOZE";
    static final String ACTION_DONE = "com.habbitstrick.app.DONE";
    static final String CHANNEL = "reminders";

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String ymd(Calendar cal) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /** Called from the web page after every change. */
    static void applySync(Context c, String json) throws Exception {
        JSONObject o = new JSONObject(json);
        JSONArray rem = o.optJSONArray("reminders");
        if (rem == null) rem = new JSONArray();
        JSONArray done = o.optJSONArray("doneIds");
        if (done == null) done = new JSONArray();
        String remStr = rem.toString();
        SharedPreferences p = prefs(c);
        boolean changed = !remStr.equals(p.getString("rem", ""));
        p.edit()
                .putString("rem", remStr)
                .putString("doneDate", o.optString("date", ""))
                .putString("doneIds", done.toString())
                .putString("wItems", o.optJSONArray("items") != null ? o.optJSONArray("items").toString() : "[]")
                .putString("wDate", o.optString("date", ""))
                .putInt("wDone", o.optInt("done", 0))
                .putInt("wTotal", o.optInt("total", 0))
                .putInt("wStreak", o.optInt("streak", 0))
                .apply();
        if (changed) schedule(c);
        HabitWidget.refresh(c);
    }

    static Intent baseIntent(Context c) {
        Intent i = new Intent(c, ReminderReceiver.class);
        i.setAction(ACTION_REMIND);
        return i;
    }

    /** True when this reminder time still exists, so a fired alarm should re-arm. */
    static boolean stillScheduled(Context c, String id, String time) {
        try {
            JSONArray arr = new JSONArray(prefs(c).getString("rem", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                if (!id.equals(t.optString("id"))) continue;
                JSONArray times = t.optJSONArray("times");
                if (times == null) return false;
                for (int j = 0; j < times.length(); j++) {
                    if (time.equals(times.optString(j))) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static boolean canExact(Context c) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    /** Sets the next single alarm for one reminder time. It re-arms itself after it fires. */
    static void scheduleOne(Context c, AlarmManager am, String id, String time) {
        try {
            if (am == null) am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            String[] hm = time.split(":");
            if (hm.length != 2) return;
            int h = Integer.parseInt(hm[0]);
            int m = Integer.parseInt(hm[1]);
            int code = (id + "|" + time).hashCode();
            Intent in = baseIntent(c);
            in.putExtra("id", id);
            in.putExtra("time", time);
            PendingIntent pi = PendingIntent.getBroadcast(c, code, in,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, h);
            cal.set(Calendar.MINUTE, m);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis() + 2000) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }
            if (canExact(c)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi);
            }
        } catch (Exception ignored) {
        }
    }

    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        SharedPreferences p = prefs(c);

        Set<String> old = new HashSet<>(p.getStringSet("codes", new HashSet<String>()));
        for (String s : old) {
            try {
                int code = Integer.parseInt(s);
                PendingIntent pi = PendingIntent.getBroadcast(c, code, baseIntent(c),
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                if (pi != null) {
                    am.cancel(pi);
                    pi.cancel();
                }
            } catch (Exception ignored) {
            }
        }

        Set<String> codes = new HashSet<>();
        try {
            JSONArray arr = new JSONArray(p.getString("rem", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                String id = t.getString("id");
                JSONArray times = t.optJSONArray("times");
                if (times == null) continue;
                for (int j = 0; j < times.length(); j++) {
                    String time = times.getString(j);
                    String[] hm = time.split(":");
                    if (hm.length != 2) continue;
                    int h = Integer.parseInt(hm[0]);
                    int m = Integer.parseInt(hm[1]);
                    int code = (id + "|" + time).hashCode();
                    scheduleOne(c, am, id, time);
                    codes.add(String.valueOf(code));
                }
            }
        } catch (Exception ignored) {
        }
        p.edit().putStringSet("codes", codes).apply();
    }

    /** Decides whether a reminder should show right now, then shows it. */
    static void maybeNotify(Context c, String id, boolean checkSchedule) {
        if (id == null) return;
        try {
            SharedPreferences p = prefs(c);
            JSONArray arr = new JSONArray(p.getString("rem", "[]"));
            JSONObject t = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject x = arr.getJSONObject(i);
                if (id.equals(x.optString("id"))) {
                    t = x;
                    break;
                }
            }
            if (t == null) return;

            Calendar now = Calendar.getInstance();
            String today = ymd(now);

            if (today.equals(p.getString("doneDate", ""))) {
                JSONArray done = new JSONArray(p.getString("doneIds", "[]"));
                for (int i = 0; i < done.length(); i++) {
                    if (id.equals(done.optString(i))) return;
                }
            }

            if (checkSchedule) {
                String from = t.optString("from", "");
                if (!from.isEmpty() && today.compareTo(from) < 0) return;
                String date = t.optString("date", "");
                if (!date.isEmpty() && !date.equals(today)) return;
                JSONArray vac = t.optJSONArray("vac");
                if (vac != null) {
                    for (int i = 0; i < vac.length(); i++) {
                        JSONObject r = vac.optJSONObject(i);
                        if (r != null && today.compareTo(r.optString("a")) >= 0 && today.compareTo(r.optString("b")) <= 0) return;
                    }
                }
                JSONArray days = t.optJSONArray("days");
                if (days != null) {
                    int d = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7;
                    boolean ok = false;
                    for (int i = 0; i < days.length(); i++) {
                        if (days.optInt(i, -1) == d) ok = true;
                    }
                    if (!ok) return;
                }
            }
            show(c, id, t.optString("name", "your task"), t.optBoolean("q", false));
        } catch (Exception ignored) {
        }
    }

    static void createChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Task reminders from Habbit Strick");
        nm.createNotificationChannel(ch);
    }

    static void show(Context c, String id, String name, boolean quit) {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        createChannel(c);
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(c, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int nid = id.hashCode();
        Intent sn = new Intent(c, ReminderReceiver.class);
        sn.setAction(ACTION_SNOOZE);
        sn.putExtra("id", id);
        sn.putExtra("nid", nid);
        PendingIntent pSnooze = PendingIntent.getBroadcast(c, ("s" + id).hashCode(), sn,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Habbit Strick")
                .setContentText((quit ? "Keep the rule: " : "Time for: ") + name)
                .setContentIntent(pOpen)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (!"test".equals(id)) {
            Intent dn = new Intent(c, ReminderReceiver.class);
            dn.setAction(ACTION_DONE);
            dn.putExtra("id", id);
            dn.putExtra("nid", nid);
            PendingIntent pDone = PendingIntent.getBroadcast(c, ("d" + id).hashCode(), dn,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nb.addAction(0, quit ? "Followed" : "Done", pDone);
        }
        nb.addAction(0, "Snooze 10 min", pSnooze);
        Notification n = nb.build();
        NotificationManagerCompat.from(c).notify(nid, n);
    }

    /** Marks a task done from the notification. The web page applies it next time it opens. */
    static void markDone(Context c, String id, int nid) {
        NotificationManagerCompat.from(c).cancel(nid);
        if (id == null) return;
        try {
            SharedPreferences p = prefs(c);
            String today = ymd(Calendar.getInstance());
            JSONArray q = new JSONArray(p.getString("wq", "[]"));
            JSONObject e = new JSONObject();
            e.put("d", today);
            e.put("id", id);
            e.put("v", 1);
            q.put(e);
            JSONArray done = new JSONArray(p.getString("doneIds", "[]"));
            boolean had = false;
            for (int i = 0; i < done.length(); i++) {
                if (id.equals(done.optString(i))) had = true;
            }
            if (!had) done.put(id);
            JSONArray items = new JSONArray(p.getString("wItems", "[]"));
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                if (id.equals(o.optString("id"))) o.put("done", true);
            }
            int wd = p.getInt("wDone", 0) + (had ? 0 : 1);
            p.edit()
                    .putString("wq", q.toString())
                    .putString("doneIds", done.toString())
                    .putString("doneDate", today)
                    .putString("wItems", items.toString())
                    .putInt("wDone", wd)
                    .apply();
        } catch (Exception ignored) {
        }
        HabitWidget.refresh(c);
    }

    static void snooze(Context c, String id, int nid) {
        NotificationManagerCompat.from(c).cancel(nid);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null || id == null) return;
        Intent in = new Intent(c, ReminderReceiver.class);
        in.setAction(ACTION_ONCE);
        in.putExtra("id", id);
        PendingIntent pi = PendingIntent.getBroadcast(c, ("once" + id).hashCode(), in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10 * 60 * 1000L, pi);
    }
}
