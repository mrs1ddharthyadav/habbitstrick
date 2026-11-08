package com.habbitstrick.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Reminders.schedule(context);
            HabitWidget.refresh(context);
        } catch (Exception ignored) {
        }
    }
}
