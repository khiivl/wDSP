package com.radiorubka.wdsp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;

/**
 * Starts the player that was playing again, after a restart or after the car slept.
 *
 * <p>🔴 Owner, 14.09.2026: "я хотів би впровадити цю загублену китайцями функцію, раз wDSP стартує
 * першим" - two separate switches, after a restart and after sleep (Settings, Permissions and
 * system). What the Haiwai firmware does instead (Gemini's reading of {@code QFSleepWakeup}, checked
 * on the owner's unit): at {@code ACC_OFF} it remembers the source only if it is one of the five
 * factory apps in {@code /system/config/RestoreAppsWhenWakeup.ini}, force-stops everything not on a
 * sleep whitelist, and on waking starts only a factory app. A third-party player is simply gone.
 *
 * <p>The rules, from the notes of that night:
 * <ul>
 *   <li>Radio is left alone - it restores itself, and the contract with QFRadio says a request from
 *   wDSP does not wake a stopped radio.</li>
 *   <li>A pause the person made is kept. "Was playing" is the state when the ignition went off, with
 *   {@link #PAUSE_GRACE_MS} of grace for a pause that arrives just before it - the platform's own.</li>
 *   <li>Nothing is started over something already playing: the channel is not taken "just in case".</li>
 *   <li>The factory apps are the platform's to restore, not ours.</li>
 * </ul>
 *
 * <p>Only a process that lives through sleep can do this after sleep: an app the platform force-stops
 * at {@code ACC_OFF} receives nothing until someone opens it.
 */
public final class PlayerResume {

    private static final String TAG = "wDSP_PlayerResume";

    /** The two switches (owner, 14.09.2026). Settings, so they travel with a backup. */
    public static final String PREF_AFTER_REBOOT = "pref_resume_player_after_reboot";
    public static final String PREF_AFTER_SLEEP = "pref_resume_player_after_sleep";

    /** What was playing describes this unit, not the owner's settings: kept out of backups. */
    private static final String STATE_PREFS = "wdsp_device_state";
    /** The last player seen playing. */
    private static final String KEY_PLAYER = "resume_player";
    /** Whether it was still playing at its last change. */
    private static final String KEY_PLAYING = "resume_playing";
    /** Wall clock of its last stop or pause. */
    private static final String KEY_STOPPED_AT = "resume_stopped_at";
    /** ACC_OFF seen and no ACC_ON since - survives a restart during sleep. */
    private static final String KEY_ASLEEP = "resume_asleep";
    /** The player to start at the next wake or boot, taken at ACC_OFF; empty = nothing. */
    private static final String KEY_SNAPSHOT = "resume_snapshot";

    /** A pause this close before ACC_OFF is taken as the platform's, not the person's. */
    private static final long PAUSE_GRACE_MS = 5000;
    /**
     * A pause this long before the machine started is the shutdown's, not the person's.
     *
     * <p>🔴 Without this the boot path found nothing to resume, every time (owner, 21.09.2026:
     * "you are reading the wrong thing, you are not recording it"). The last thing that ever
     * happens before the power goes is the platform pausing the player, so "was it still playing?"
     * is answered "no" by the time anybody asks. The pause is compared against the moment THIS
     * machine started instead: a player that stopped within two minutes of it stopped because the
     * unit was going down, and one that stopped an hour earlier was stopped by a person.
     */
    private static final long PAUSE_BEFORE_BOOT_MS = 120_000;
    /** A service that is ready this soon after the kernel started is a boot, not a wake. */
    private static final long BOOT_WINDOW_MS = 180_000;
    /** After our own ACC_ON work and the platform restoring its own apps. */
    private static final long AFTER_WAKE_DELAY_MS = 4000;
    private static final long AFTER_BOOT_DELAY_MS = 5000;
    private static final long BROWSER_HOLD_MS = 10_000;
    /** How long each rung of the ladder is given before the next one is tried. */
    private static final long STEP_SETTLE_MS = 3000;
    /** An app that has just been launched needs longer: it has a screen to build first. */
    private static final long LAUNCH_SETTLE_MS = 5000;

    private static final String PLATFORM_RESTORE_LIST = "/system/config/RestoreAppsWhenWakeup.ini";

    private static PlayerResume instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean readyHandled;
    private boolean bootHandled;

    public static synchronized PlayerResume getInstance(Context context) {
        if (instance == null) instance = new PlayerResume(context.getApplicationContext());
        return instance;
    }

    private PlayerResume(Context context) {
        this.context = context;
    }

    private SharedPreferences state() {
        return context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE);
    }

    // -------------------------------------------------------------------------------------------
    // what is playing - told by NowPlaying on every change of the session it follows
    // -------------------------------------------------------------------------------------------

    /** @param pkg the player's package, empty when there is no session at all */
    public void onPlaybackState(String pkg, boolean playing) {
        SharedPreferences s = state();
        // Asleep, whatever changes is the platform stopping and killing things, not the person.
        if (s.getBoolean(KEY_ASLEEP, false)) return;
        String known = s.getString(KEY_PLAYER, "");
        if (playing && pkg != null && !pkg.isEmpty()) {
            if (!pkg.equals(known) || !s.getBoolean(KEY_PLAYING, false)) {
                s.edit().putString(KEY_PLAYER, pkg).putBoolean(KEY_PLAYING, true).apply();
            }
        } else if (s.getBoolean(KEY_PLAYING, false) && (pkg == null || pkg.isEmpty() || pkg.equals(known))) {
            s.edit().putBoolean(KEY_PLAYING, false).putLong(KEY_STOPPED_AT, System.currentTimeMillis()).apply();
        }
    }

    // -------------------------------------------------------------------------------------------
    // the car - told by McuService
    // -------------------------------------------------------------------------------------------

    /** Ignition off: decide now what "was playing" means, before the platform pauses and kills. */
    public void onAccOff() {
        SharedPreferences s = state();
        if (s.getBoolean(KEY_ASLEEP, false)) return;
        String pkg = s.getString(KEY_PLAYER, "");
        boolean playing = s.getBoolean(KEY_PLAYING, false)
                || System.currentTimeMillis() - s.getLong(KEY_STOPPED_AT, 0) <= PAUSE_GRACE_MS;
        String snapshot;
        String why;
        if (NowPlaying.getInstance(context).isRadioSource() || NowPlaying.isRadioPackage(pkg)) {
            snapshot = "";
            why = "radio - it restores itself";
        } else if (!playing || pkg.isEmpty()) {
            snapshot = "";
            why = pkg.isEmpty() ? "no player seen" : pkg + " was paused before the ignition went off";
        } else {
            snapshot = pkg;
            why = pkg + " was playing";
        }
        s.edit().putString(KEY_SNAPSHOT, snapshot).putBoolean(KEY_ASLEEP, true).apply();
        Log.i(TAG, "ACC_OFF: " + why + (snapshot.isEmpty() ? " - nothing to resume" : " - remembered"));
    }

    /**
     * The service has loaded and applied the presets. Once per process; within
     * {@link #BOOT_WINDOW_MS} of the kernel starting it is a boot. Call before any ACC_ON that waited
     * for the same moment, so that the boot's own ACC_ON is recognised as the boot's.
     */
    public void onServiceReady() {
        if (readyHandled) return;
        readyHandled = true;
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return;
        bootHandled = true;
        SharedPreferences s = state();
        boolean slept = s.getBoolean(KEY_ASLEEP, false);
        // Slept and then lost power: what was playing when the ignition went off. Power lost or
        // rebooted while driving: whatever was playing last - including a player the platform
        // paused on its way down, which is what a shutdown looks like from here. See
        // PAUSE_BEFORE_BOOT_MS; before it, this branch demanded that the player still be playing
        // and so found nothing every single time.
        String target;
        if (slept) {
            target = s.getString(KEY_SNAPSHOT, "");
        } else {
            final String last = s.getString(KEY_PLAYER, "");
            final long stoppedAt = s.getLong(KEY_STOPPED_AT, 0L);
            final long bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
            final boolean stoppedGoingDown = stoppedAt > 0
                    && stoppedAt >= bootedAt - PAUSE_BEFORE_BOOT_MS;
            if (s.getBoolean(KEY_PLAYING, false) || stoppedGoingDown) {
                target = last;
            } else {
                target = "";
                if (!last.isEmpty()) {
                    Log.i(TAG, "boot: " + last + " was paused "
                            + ((bootedAt - stoppedAt) / 1000) + " s before this machine started - "
                            + "a person's pause, not the shutdown's");
                }
            }
        }
        s.edit().putBoolean(KEY_ASLEEP, false).apply();
        if (!ThemeManager.prefs(context).getBoolean(PREF_AFTER_REBOOT, false)) {
            Log.i(TAG, "boot: resuming after a restart is off" + (target.isEmpty() ? "" : " (" + target + " was playing)"));
            return;
        }
        schedule(target, "restart", AFTER_BOOT_DELAY_MS);
    }

    /** Ignition on. The boot's own ACC_ON is left to {@link #onServiceReady}. */
    public void onAccOn() {
        if (bootHandled && SystemClock.elapsedRealtime() <= BOOT_WINDOW_MS) return;
        SharedPreferences s = state();
        if (!s.getBoolean(KEY_ASLEEP, false)) return;
        String target = s.getString(KEY_SNAPSHOT, "");
        s.edit().putBoolean(KEY_ASLEEP, false).apply();
        if (!ThemeManager.prefs(context).getBoolean(PREF_AFTER_SLEEP, false)) {
            Log.i(TAG, "wake: resuming after sleep is off" + (target.isEmpty() ? "" : " (" + target + " was playing)"));
            return;
        }
        schedule(target, "sleep", AFTER_WAKE_DELAY_MS);
    }

    // -------------------------------------------------------------------------------------------
    // starting it
    // -------------------------------------------------------------------------------------------

    private void schedule(String pkg, String after, long delayMs) {
        if (pkg == null || pkg.isEmpty()) {
            Log.i(TAG, "after " + after + ": nothing was playing");
            return;
        }
        main.postDelayed(() -> resume(pkg, after), delayMs);
    }

    private void resume(String pkg, String after) {
        NowPlaying now = NowPlaying.getInstance(context);
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (now.isRadioSource() || now.isPlaying() || (am != null && am.isMusicActive())) {
            Log.i(TAG, "after " + after + ": something is already playing ("
                    + (now.isRadioSource() ? "radio" : now.playerPackage()) + ") - " + pkg + " is not started over it");
            return;
        }
        if (isRestoredByPlatform(pkg)) {
            Log.i(TAG, "after " + after + ": " + pkg + " is one of the platform's own - it restores it");
            return;
        }
        if (!isInstalled(pkg)) {
            Log.i(TAG, "after " + after + ": " + pkg + " is no longer installed");
            return;
        }
        climb(pkg, after, 0);
    }

    /**
     * Asks a player to play, and keeps asking differently until it does or the ways run out.
     *
     * <p>🔴 Rewritten 21.09.2026, and the owner named the fault before the log confirmed it: "you
     * are launching it wrong - without context. Spotify will not start from a targeted intent
     * either, like any other player, because if nobody has taken the media session yet, a plain
     * input key flies into the void." The unit's own log from boot 0034 says exactly that:
     * <pre>
     *   asked app.morphe.android.apps.youtube.music to play through its media button receiver
     *   app.morphe.android.apps.youtube.music is NOT playing 6000 ms later
     * </pre>
     * The old code took "a media button receiver exists" for success and stopped there, so the
     * browser service - the one rung that can actually start a dead process - was never reached.
     *
     * <p>So the rungs go from the ones that give the player a context to the ones that need it to
     * have one already, and every rung is CHECKED rather than assumed:
     * <ol>
     *   <li>its live session, if it still has one - nothing to start;</li>
     *   <li>its media browser service: binds it, which starts the process without a screen, and
     *       plays through the session it hands back;</li>
     *   <li>launching the app itself: for a player with no browser service, that is the only way
     *       its session is ever created;</li>
     *   <li>the media button, last, because by now something is listening for it.</li>
     * </ol>
     */
    private void climb(String pkg, String after, int step) {
        final NowPlaying now = NowPlaying.getInstance(context);
        if (isPlaying(pkg)) {
            Log.i(TAG, "after " + after + ": " + pkg + " is playing");
            return;
        }
        String how;
        long settle = STEP_SETTLE_MS;
        switch (step) {
            case 0:
                if (!now.playPackage(pkg)) { climb(pkg, after, 1); return; }
                how = "its media session";
                break;
            case 1:
                if (!playThroughBrowser(pkg)) { climb(pkg, after, 2); return; }
                how = "its media browser service";
                break;
            case 2:
                if (!launchApp(pkg)) { climb(pkg, after, 3); return; }
                how = "opening the app itself";
                settle = LAUNCH_SETTLE_MS;
                break;
            case 3:
                // A second go at the session: the app is open now, so it probably has one.
                if (now.playPackage(pkg)) { how = "its media session, once it was open"; break; }
                if (!sendPlayKey(pkg)) { climb(pkg, after, 4); return; }
                how = "its media button receiver";
                break;
            default:
                Log.w(TAG, "after " + after + ": " + pkg + " would not start - session, browser "
                        + "service, its own screen and the media button were all tried");
                return;
        }
        final int next = step + 1;
        Log.i(TAG, "after " + after + ": asked " + pkg + " to play through " + how);
        main.postDelayed(() -> {
            if (isPlaying(pkg)) {
                Log.i(TAG, "after " + after + ": " + pkg + " is playing (" + how + ")");
                return;
            }
            Log.i(TAG, "after " + after + ": " + how + " did not start " + pkg + " - trying further");
            climb(pkg, after, next);
        }, settle);
    }

    /** Playing, as anything outside this class can see it: our own session view or the mixer. */
    private boolean isPlaying(String pkg) {
        final NowPlaying now = NowPlaying.getInstance(context);
        if (now.isPlaying() && pkg.equals(now.playerPackage())) return true;
        final AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        // Without notification access there is no session to read, and the mixer is all we have.
        return am != null && am.isMusicActive() && now.playerPackage().isEmpty();
    }

    /**
     * Opens the player, which is how a player with no browser service gets a session at all.
     *
     * <p>Background activity starts are blocked on Android 10 for an app with nothing on screen -
     * except one holding SYSTEM_ALERT_WINDOW, which this app does for the status bar visualizer.
     * If that permission is ever lost this rung stops working and the log says so.
     */
    private boolean launchApp(String pkg) {
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(launch);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "could not open " + pkg + ": " + t);
            return false;
        }
    }

    /** An explicit PLAY key to the player's own receiver - starts a player whose process is gone. */
    private boolean sendPlayKey(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            List<ResolveInfo> receivers = pm.queryBroadcastReceivers(
                    new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0);
            if (receivers == null || receivers.isEmpty()) return false;
            ComponentName target = new ComponentName(pkg, receivers.get(0).activityInfo.name);
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                Intent key = new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(target)
                        .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY));
                context.sendBroadcast(key);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "media button to " + pkg + " failed: " + t);
            return false;
        }
    }

    /** Binds the player's browser service, which starts it without its screen, and asks it to play. */
    private boolean playThroughBrowser(String pkg) {
        try {
            List<ResolveInfo> services = context.getPackageManager().queryIntentServices(
                    new Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0);
            if (services == null || services.isEmpty()) return false;
            ComponentName service = new ComponentName(pkg, services.get(0).serviceInfo.name);
            final MediaBrowser[] holder = new MediaBrowser[1];
            holder[0] = new MediaBrowser(context, service, new MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    try {
                        new MediaController(context, holder[0].getSessionToken()).getTransportControls().play();
                    } catch (Throwable t) {
                        Log.w(TAG, "browser of " + pkg + " connected but would not play: " + t);
                    }
                    main.postDelayed(() -> {
                        try { holder[0].disconnect(); } catch (Throwable ignored) {}
                    }, BROWSER_HOLD_MS);
                }

                @Override
                public void onConnectionFailed() {
                    Log.w(TAG, "browser of " + pkg + " refused the connection");
                }
            }, null);
            holder[0].connect();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "browser of " + pkg + " failed: " + t);
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** The factory list the platform restores by itself; unreadable counts as not listed. */
    private static boolean isRestoredByPlatform(String pkg) {
        try (BufferedReader r = new BufferedReader(new FileReader(PLATFORM_RESTORE_LIST))) {
            String line;
            while ((line = r.readLine()) != null) {
                // Whole package names only: com.android.fmradio must not match com.android.fmradio.ext.
                for (String token : line.split("[^A-Za-z0-9_.]+")) {
                    if (token.equals(pkg)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
