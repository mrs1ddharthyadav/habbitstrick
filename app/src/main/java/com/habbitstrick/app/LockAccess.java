package com.habbitstrick.app;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Lets Phone quiet draw above everything and swallow keys while a lock is running. */
public class LockAccess extends AccessibilityService {
    static LockAccess inst;
    private long lastHome = 0;

    static boolean locked(android.content.Context c) {
        android.content.SharedPreferences sp = Quiet.p(c);
        long now = System.currentTimeMillis();
        return Quiet.on(c) && sp.getLong("q_lockEnd", 0) > now && sp.getLong("q_hideUntil", 0) <= now;
    }

    @Override
    protected void onServiceConnected() {
        inst = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent i) {
        inst = null;
        return super.onUnbind(i);
    }

    @Override
    public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        return locked(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!locked(this)) return;
        CharSequence pk = e.getPackageName();
        if (pk != null && pk.toString().equals(getPackageName())) return;
        long now = System.currentTimeMillis();
        if (now - lastHome > 300) {
            lastHome = now;
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
