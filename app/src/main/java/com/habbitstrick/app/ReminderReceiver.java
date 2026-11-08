package com.habbitstrick.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        String id = intent.getStringExtra("id");
        if (Reminders.ACTION_SNOOZE.equals(action)) {
            Reminders.snooze(context, id, intent.getIntExtra("nid", 0));
        } else if (Reminders.ACTION_ONCE.equals(action)) {
            Reminders.maybeNotify(context, id, false);
        } else if (Reminders.ACTION_REMIND.equals(action)) {
            Reminders.maybeNotify(context, id, true);
        }
    }
}
