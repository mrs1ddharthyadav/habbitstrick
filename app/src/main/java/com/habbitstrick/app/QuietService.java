package com.habbitstrick.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Counts screen-on time, locks the screen with a countdown after the daily limit,
 * and keeps chosen apps closed while the Phone quiet habit is on.
 */
public class QuietService extends Service {

    static final String CH = "quiet";
    static final int NID = 4242;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager pm;
    private WindowManager wm;
    private SharedPreferences sp;

    private long lastReal = 0;
    private long tickN = 0;
    private long lastNotif = 0;
    private long lastFgCheck = 0;
    private long lastKick = 0;
    private String fgPkg = null;
    private long fgQueryFrom = 0;
    private long usedSec = 0;
    private long usedMs = 0;

    private View overlay;
    private TextView clock;
    private TextView sub;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                onTick();
            } catch (Exception ignored) {
            }
            h.postDelayed(this, pm != null && pm.isInteractive() ? 1000 : 10000);
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            lastReal = SystemClock.elapsedRealtime();
            h.removeCallbacks(tick);
            h.post(tick);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        sp = Quiet.p(this);
        Quiet.rollDay(this);
        usedSec = sp.getLong("q_used", 0);
        usedMs = usedSec * 1000L;
        lastReal = SystemClock.elapsedRealtime();
        startFg();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screen, f);
        h.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        usedSec = sp.getLong("q_used", usedSec);
        usedMs = usedSec * 1000L;
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        try {
            unregisterReceiver(screen);
        } catch (Exception ignored) {
        }
        hideOverlay();
        super.onDestroy();
    }

    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CH, "Phone quiet", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shows your screen time while Phone quiet is on");
            nm.createNotificationChannel(ch);
        }
        Notification n = buildNotif();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NID, n);
            }
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotif() {
        JSONObject cfg = Quiet.cfg(this);
        int lim = cfg.optInt("limit", 60);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent po = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Phone quiet is on")
                .setContentText("Screen time today: " + (usedSec / 60) + " of " + lim + " min")
                .setContentIntent(po)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void onTick() {
        Quiet.rollDay(this);
        JSONObject cfg = Quiet.cfg(this);
        if (!cfg.optBoolean("on", false)) {
            hideOverlay();
            stopSelf();
            return;
        }
        long now = System.currentTimeMillis();
        long real = SystemClock.elapsedRealtime();
        long delta = Math.min(5000, Math.max(0, real - lastReal));
        lastReal = real;
        boolean interactive = pm.isInteractive();
        long lockEnd = sp.getLong("q_lockEnd", 0);
        long hideUntil = sp.getLong("q_hideUntil", 0);
        boolean locked = lockEnd > now;

        if (locked) {
            if (hideUntil <= now && interactive) showOverlay(lockEnd - now);
            else hideOverlay();
        } else {
            hideOverlay();
            if (lockEnd != 0) sp.edit().putLong("q_lockEnd", 0).apply();
        }

        if (interactive && !locked) {
            usedMs += delta;
            usedSec = usedMs / 1000L;
            tickN++;
            int level = sp.getInt("q_level", 0);
            long limitSec = Math.max(1, cfg.optInt("limit", 60)) * 60L;
            if (usedSec >= limitSec && !sp.getBoolean("q_warned", false)) {
                sp.edit().putBoolean("q_warned", true).apply();
                warn(cfg);
            }
            if (usedSec >= Quiet.threshold(cfg, level)) {
                int mins = Quiet.lockMinutes(cfg, level);
                long end = now + mins * 60000L;
                sp.edit()
                        .putLong("q_lockEnd", end)
                        .putInt("q_level", level + 1)
                        .putInt("q_locks", sp.getInt("q_locks", 0) + 1)
                        .putLong("q_hideUntil", 0)
                        .putLong("q_used", usedSec)
                        .apply();
                showOverlay(end - now);
            } else if (tickN % 5 == 0) {
                sp.edit().putLong("q_used", usedSec).apply();
            }
            checkBlocked(cfg, now);
        }

        if (now - lastNotif > 30000) {
            lastNotif = now;
            try {
                NotificationManagerCompat.from(this).notify(NID, buildNotif());
            } catch (SecurityException ignored) {
            }
        }
    }

    private void warn(JSONObject cfg) {
        int[] gaps = Quiet.ints(cfg, "gaps", new int[]{30, 20, 5, 5});
        int mins = Quiet.lockMinutes(cfg, 0);
        toast("Screen limit reached. " + gaps[0] + " more minutes and the phone locks for " + mins + " min.");
    }

    private void toast(String m) {
        try {
            Toast.makeText(getApplicationContext(), m, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {
        }
    }

    /* ---------- blocked apps ---------- */

    private void checkBlocked(JSONObject cfg, long now) {
        if (!cfg.optBoolean("block", true) || !Quiet.usageOk(this)) return;
        JSONArray apps = cfg.optJSONArray("apps");
        if (apps == null || apps.length() == 0) return;
        if (now - lastFgCheck < 1500) return;
        lastFgCheck = now;
        try {
            UsageStatsManager um = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long from = fgQueryFrom == 0 ? now - 20000 : fgQueryFrom;
            UsageEvents ev = um.queryEvents(from, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                int t = e.getEventType();
                if (t == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    fgPkg = e.getPackageName();
                } else if (t == UsageEvents.Event.MOVE_TO_BACKGROUND && e.getPackageName().equals(fgPkg)) {
                    fgPkg = null;
                }
            }
            fgQueryFrom = now - 1000;
            if (fgPkg == null) return;
            Set<String> set = new HashSet<>();
            for (int i = 0; i < apps.length(); i++) set.add(apps.optString(i));
            if (set.contains(fgPkg) && now - lastKick > 2500) {
                lastKick = now;
                String name = fgPkg;
                try {
                    name = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(fgPkg, 0)).toString();
                } catch (Exception ignored) {
                }
                fgPkg = null;
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(home);
                toast(name + " is blocked while Phone quiet is on.");
            }
        } catch (Exception ignored) {
        }
    }

    /* ---------- lock overlay ---------- */

    private void showOverlay(long remainMs) {
        if (!Quiet.overlayOk(this)) return;
        long sec = Math.max(0, (remainMs + 999) / 1000);
        String t = String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60);
        if (overlay != null) {
            if (clock != null) clock.setText(t);
            return;
        }
        FrameLayout root = new FrameLayout(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                return true; // swallow Back, volume and every other key that reaches this window
            }
        };
        root.setBackgroundColor(Color.BLACK);
        root.setClickable(true);
        root.setFocusableInTouchMode(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(box, bp);

        TextView title = new TextView(this);
        title.setText("Phone quiet");
        title.setTextColor(Color.parseColor("#ff8fa3"));
        title.setTextSize(16);
        title.setLetterSpacing(0.12f);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        clock = new TextView(this);
        clock.setText(t);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(72);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setGravity(Gravity.CENTER);
        box.addView(clock);

        sub = new TextView(this);
        sub.setText("You went over your screen limit.\nPut the phone down and take a break.");
        sub.setTextColor(Color.parseColor("#9a9aa2"));
        sub.setTextSize(16);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(60, 20, 60, 60);
        box.addView(sub);

        Button em = new Button(this);
        em.setText("Emergency call");
        em.setAllCaps(false);
        em.setTextColor(Color.parseColor("#9a9aa2"));
        em.setBackgroundColor(Color.parseColor("#18181a"));
        em.setOnClickListener(v -> {
            sp.edit().putLong("q_hideUntil", System.currentTimeMillis() + 120000L).apply();
            hideOverlay();
            try {
                Intent d = new Intent(Intent.ACTION_DIAL);
                d.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(d);
            } catch (Exception ignored) {
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 40;
        box.addView(em, lp);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams wp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.OPAQUE);
        wp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            wp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        try {
            wm.addView(root, wp);
            overlay = root;
            root.requestFocus();
        } catch (Exception e) {
            overlay = null;
        }
    }

    private void hideOverlay() {
        if (overlay != null) {
            try {
                wm.removeView(overlay);
            } catch (Exception ignored) {
            }
            overlay = null;
            clock = null;
            sub = null;
        }
    }
}
