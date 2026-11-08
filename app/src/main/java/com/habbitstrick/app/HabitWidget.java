package com.habbitstrick.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

public class HabitWidget extends AppWidgetProvider {

    static final String ACTION_TOGGLE = "com.habbitstrick.app.WIDGET_TOGGLE";
    static final int[] ROWS = {R.id.w_i0, R.id.w_i1, R.id.w_i2, R.id.w_i3};

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            manager.updateAppWidget(id, build(context));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            toggle(context, intent.getStringExtra("id"));
            return;
        }
        super.onReceive(context, intent);
    }

    static void toggle(Context c, String id) {
        if (id == null) return;
        SharedPreferences p = Reminders.prefs(c);
        String today = Reminders.ymd(Calendar.getInstance());
        if (!today.equals(p.getString("wDate", ""))) return;
        try {
            JSONArray items = new JSONArray(p.getString("wItems", "[]"));
            boolean found = false;
            boolean nowDone = false;
            for (int k = 0; k < items.length(); k++) {
                JSONObject o = items.getJSONObject(k);
                if (id.equals(o.optString("id"))) {
                    nowDone = !o.optBoolean("done", false);
                    o.put("done", nowDone);
                    found = true;
                }
            }
            if (!found) return;
            JSONArray q = new JSONArray(p.getString("wq", "[]"));
            JSONObject e = new JSONObject();
            e.put("d", today);
            e.put("id", id);
            e.put("v", nowDone ? 1 : 0);
            q.put(e);
            JSONArray done = new JSONArray(p.getString("doneIds", "[]"));
            JSONArray nd = new JSONArray();
            for (int k = 0; k < done.length(); k++) {
                if (!id.equals(done.optString(k))) nd.put(done.optString(k));
            }
            if (nowDone) nd.put(id);
            int wd = Math.max(0, p.getInt("wDone", 0) + (nowDone ? 1 : -1));
            p.edit()
                    .putString("wItems", items.toString())
                    .putString("wq", q.toString())
                    .putString("doneIds", nd.toString())
                    .putInt("wDone", wd)
                    .apply();
        } catch (Exception ignored) {
        }
        refresh(c);
    }

    static RemoteViews build(Context c) {
        SharedPreferences p = Reminders.prefs(c);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        String today = Reminders.ymd(Calendar.getInstance());
        boolean fresh = today.equals(p.getString("wDate", ""));
        if (fresh) {
            v.setTextViewText(R.id.w_count, p.getInt("wDone", 0) + "/" + p.getInt("wTotal", 0));
            v.setTextViewText(R.id.w_label, "done today");
        } else {
            v.setTextViewText(R.id.w_count, "--");
            v.setTextViewText(R.id.w_label, "Open to update");
        }
        v.setTextViewText(R.id.w_streak, "Streak " + p.getInt("wStreak", 0));

        JSONArray items = new JSONArray();
        if (fresh) {
            try {
                items = new JSONArray(p.getString("wItems", "[]"));
            } catch (Exception ignored) {
            }
        }
        for (int k = 0; k < ROWS.length; k++) {
            if (k < items.length()) {
                JSONObject o = items.optJSONObject(k);
                if (o == null) {
                    v.setViewVisibility(ROWS[k], View.GONE);
                    continue;
                }
                boolean done = o.optBoolean("done", false);
                v.setTextViewText(ROWS[k], (done ? "●  " : "○  ") + o.optString("name", ""));
                v.setTextColor(ROWS[k], done ? Color.parseColor("#6B6B72") : Color.WHITE);
                v.setViewVisibility(ROWS[k], View.VISIBLE);
                Intent in = new Intent(c, HabitWidget.class);
                in.setAction(ACTION_TOGGLE);
                in.putExtra("id", o.optString("id"));
                PendingIntent pt = PendingIntent.getBroadcast(c, 100 + k, in,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                v.setOnClickPendingIntent(ROWS[k], pt);
            } else {
                v.setViewVisibility(ROWS[k], View.GONE);
            }
        }

        Intent open = new Intent(c, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(c, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.w_root, pi);
        return v;
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, HabitWidget.class));
        for (int id : ids) {
            m.updateAppWidget(id, build(c));
        }
    }
}
