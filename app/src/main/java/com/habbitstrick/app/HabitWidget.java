package com.habbitstrick.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.util.Calendar;

public class HabitWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            manager.updateAppWidget(id, build(context));
        }
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
