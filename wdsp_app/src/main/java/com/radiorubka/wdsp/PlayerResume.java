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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.radiorubka.wdsp.ui.theme.ThemeManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.List;
import java.util.Locale;

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
    /** How long a freshly opened player is given to build a screen and claim a session. */
    private static final long LAUNCH_WAIT_MS = 15_000;
    /** How often it is asked again while that window runs. */
    private static final long PLAY_RETRY_MS = 1500;
    /** How many PLAYs one rung sends before giving up on it. */
    private static final int MAX_PLAY_TRIES = 5;
    /** How long a player that streams is given to get a network before it is asked to play. */
    private static final long NETWORK_WAIT_MS = 20_000;
    private static final long NETWORK_POLL_MS = 2000;
    /** The system is still starting while nothing real is in front - see waitForSystem. */
    private static final long SYSTEM_READY_WAIT_MS = 60_000;
    private static final long SYSTEM_POLL_MS = 1000;

    private static final String PLATFORM_RESTORE_LIST = "/system/config/RestoreAppsWhenWakeup.ini";

    private static PlayerResume instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean readyHandled;
    private boolean bootHandled;
    /** What was on screen when a resume started, so the screen can be put back afterwards. */
    private String foregroundBefore = "";

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
        // 🔴 The radio is never the thing to resume, and this is the ONE place that decides it -
        // proven the hard way at 04:41 on 22.09.2026, on a unit that had been rebooted for a
        // different test: the boot path found the radio recorded as "the last player", opened its
        // app and put it on air in the middle of the night. It restores itself, and the contract
        // with QFRadio says a request from wDSP does not wake a stopped radio. Switching to the
        // radio therefore clears what was remembered: the person chose the radio, so there is
        // nothing of theirs left to bring back.
        if (NowPlaying.isRadioPackage(pkg)) {
            if (!s.getString(KEY_PLAYER, "").isEmpty()) {
                Log.i(TAG, "the radio took over - nothing to resume any more; it restores itself");
                s.edit().putString(KEY_PLAYER, "").putBoolean(KEY_PLAYING, false)
                        .putLong(KEY_STOPPED_AT, System.currentTimeMillis()).apply();
            }
            return;
        }
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
        // Whatever was on screen before we start opening things, so it can be put back.
        foregroundBefore = foregroundPackage();
        waitForSystem(pkg, after, SystemClock.elapsedRealtime() + SYSTEM_READY_WAIT_MS);
    }

    /**
     * Waits for the machine to finish starting before asking anybody to play.
     *
     * <p>🔴 Taken from DefaultAppsChanger, which does this properly and does it with root (owner,
     * 22.09.2026: "it does it perfectly, but for rooted units, and wDSP counts on units without
     * root, so you can peek there"). Its {@code waitSystemReady} polls the foreground app until it
     * is something real; the root-free equivalent is the platform's own
     * {@code sys.qf.current.activity}, which the framework updates on every window focus change
     * (Gemini's research, board #746). Until something real is in front, a launch lands in the
     * boot animation and a PLAY lands nowhere.
     */
    private void waitForSystem(String pkg, String after, long deadline) {
        final String fg = foregroundPackage();
        final boolean ready = !fg.isEmpty()
                && !fg.equals("android")
                && !fg.equals("com.android.settings")   // FallbackHome, the boot placeholder
                && !fg.equals(context.getPackageName());
        if (ready || SystemClock.elapsedRealtime() > deadline) {
            if (!ready) Log.i(TAG, "after " + after + ": the screen never settled - going ahead anyway");
            else if (!fg.isEmpty()) foregroundBefore = fg;
            waitForNetwork(pkg, after, SystemClock.elapsedRealtime() + NETWORK_WAIT_MS, false);
            return;
        }
        main.postDelayed(() -> waitForSystem(pkg, after, deadline), SYSTEM_POLL_MS);
    }

    /**
     * Gives a player that streams its network before asking it to play.
     *
     * <p>Also from DefaultAppsChanger, which pings and waits. A streaming player asked to play
     * with no network does not queue the request - it fails, and the failure looks exactly like
     * "the resume does not work". Only the players that need it wait; everything else goes
     * straight on, and the wait ends the moment the network is usable.
     */
    private void waitForNetwork(String pkg, String after, long deadline, boolean announced) {
        if (!needsNetwork(pkg) || hasUsableNetwork()) {
            if (announced) Log.i(TAG, "after " + after + ": the network is up - asking " + pkg + " now");
            climb(pkg, after, 0);
            return;
        }
        if (SystemClock.elapsedRealtime() > deadline) {
            Log.w(TAG, "after " + after + ": no network after " + (NETWORK_WAIT_MS / 1000)
                    + " s - asking " + pkg + " anyway, it may refuse");
            climb(pkg, after, 0);
            return;
        }
        if (!announced) Log.i(TAG, "after " + after + ": " + pkg + " plays from the network - waiting for one");
        main.postDelayed(() -> waitForNetwork(pkg, after, deadline, true), NETWORK_POLL_MS);
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
                // Opening it is not the end of this rung: the app has a screen to build and a
                // session to claim, and only then is there anybody to ask. DefaultAppsChanger
                // watches for exactly that - the app in front, then an active session - and asks
                // again every second and a half until the music is actually on. The same here,
                // minus the root: the foreground comes from sys.qf.current.activity and the
                // session from the one we are allowed to read.
                Log.i(TAG, "after " + after + ": opened " + pkg + " - waiting for it to take a session");
                pressPlayUntilItPlays(pkg, after, SystemClock.elapsedRealtime() + LAUNCH_WAIT_MS, 0);
                return;
            case 3:
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
        }, STEP_SETTLE_MS);
    }

    /**
     * Asks a just-opened player to play until it does, then puts the screen back as it was.
     *
     * <p>One ask was never enough: an app that has just started shows a splash, restores its own
     * state and only then registers a session, and a PLAY sent before that is dropped without a
     * word. So this asks again every {@link #PLAY_RETRY_MS} until the music is on, the tries run
     * out or the window closes - which is what DefaultAppsChanger does with root, and what the log
     * from boot 0034 was missing.
     */
    private void pressPlayUntilItPlays(String pkg, String after, long deadline, int tries) {
        if (isPlaying(pkg)) {
            Log.i(TAG, "after " + after + ": " + pkg + " is playing (opened and asked "
                    + tries + (tries == 1 ? " time)" : " times)"));
            restoreForeground(pkg);
            return;
        }
        final boolean asked = NowPlaying.getInstance(context).playPackage(pkg);
        final int nextTries = asked ? tries + 1 : tries;
        if (nextTries >= MAX_PLAY_TRIES || SystemClock.elapsedRealtime() > deadline) {
            Log.i(TAG, "after " + after + ": " + pkg + " was opened and asked " + nextTries
                    + " times without playing - trying the media button");
            restoreForeground(pkg);
            climb(pkg, after, 3);
            return;
        }
        main.postDelayed(() -> pressPlayUntilItPlays(pkg, after, deadline, nextTries), PLAY_RETRY_MS);
    }

    /**
     * Puts back whatever was on screen before the player was opened.
     *
     * <p>Resuming the music is not a reason to change what the person is looking at - after a boot
     * that is usually the launcher, and after a wake it is whatever they left. DefaultAppsChanger
     * restores the previous app with root and falls back to the home screen; without root the home
     * screen is the honest version of the same thing, and the previous app is re-launched only
     * when there was a real one.
     */
    private void restoreForeground(String pkg) {
        final String back = foregroundBefore;
        foregroundBefore = "";
        try {
            if (!back.isEmpty() && !back.equals(pkg) && !back.equals(context.getPackageName())) {
                Intent previous = context.getPackageManager().getLaunchIntentForPackage(back);
                if (previous != null) {
                    previous.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    context.startActivity(previous);
                    Log.i(TAG, "screen put back to " + back);
                    return;
                }
            }
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
            Log.i(TAG, "screen put back to the launcher");
        } catch (Throwable t) {
            Log.w(TAG, "could not put the screen back: " + t);
        }
    }

    /** What is in front, without root: the property the framework updates on every focus change. */
    private String foregroundPackage() {
        final String activity = HardwareProfile.systemProperty(PROP_CURRENT_ACTIVITY);
        if (activity == null || activity.isEmpty()) return "";
        final int slash = activity.indexOf('/');
        return slash > 0 ? activity.substring(0, slash) : activity;
    }

    private static final String PROP_CURRENT_ACTIVITY = "sys.qf.current.activity";

    /**
     * Players that have nothing to play until there is a network.
     *
     * <p>A list rather than a guess: nearly every app holds the INTERNET permission, so asking the
     * package manager would delay a local player for nothing. WARNING: Spotify is in it and is
     * still its own story - the owner, 22.09.2026: "the particulars of starting Spotify are a
     * separate song". This gets it as far as a network and an open app; what it wants after that
     * is a daytime question.
     */
    private static final String[] NEEDS_NETWORK = {
            "com.spotify", "youtube.music", "com.google.android.apps.youtube",
            "deezer", "tidal", "soundcloud", "yandex.music", "vk.music", "apple.music",
            "com.aspiro", "podcast",
    };

    private boolean needsNetwork(String pkg) {
        final String p = pkg.toLowerCase(Locale.US);
        for (String mark : NEEDS_NETWORK) {
            if (p.contains(mark)) return true;
        }
        return false;
    }

    private boolean hasUsableNetwork() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;   // nothing we can see to wait for
            Network active = cm.getActiveNetwork();
            if (active == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;
        }
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
