package com.radiorubka.wdsp;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.session.MediaController;
import android.media.browse.MediaBrowser;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
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
    /** + package: whether that player's session showed a stream (see noteSource). */
    private static final String KEY_STREAMS = "resume_streams_";
    /** ACTION_SHUTDOWN took the note and no boot has read it yet - see onShutdown. */
    private static final String KEY_SHUTDOWN = "resume_shutdown";
    /** + package: that player's media button receiver, "package/class" (see noteSession). */
    private static final String KEY_RECEIVER = "resume_receiver_";
    /** How long a bound session service is held after asking it to play. */
    private static final long BROWSER_HOLD_MS = 10_000;
    /** How long a player that streams is given to get a network before it is started anyway. */
    private static final long NETWORK_WAIT_MS = 20_000;
    private static final long NETWORK_POLL_MS = 2000;

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
    /** How long a freshly opened player is given to come on screen (DAC: 15 s, every 0.5 s). */
    private static final long APPEAR_WAIT_MS = 15_000;
    private static final long APPEAR_POLL_MS = 500;
    /** On screen, its UI is given this long before the PLAY - DAC's "UI stabilization". */
    private static final long UI_SETTLE_MS = 1500;
    /** How long after a PLAY the music is looked for. */
    private static final long VERIFY_MS = 3000;
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
                        .putLong(KEY_STOPPED_AT, System.currentTimeMillis()).commit();
            }
            return;
        }
        String known = s.getString(KEY_PLAYER, "");
        if (playing && pkg != null && !pkg.isEmpty()) {
            noteSession(pkg);
            if (!pkg.equals(known) || !s.getBoolean(KEY_PLAYING, false)) {
                s.edit().putString(KEY_PLAYER, pkg).putBoolean(KEY_PLAYING, true).commit();
            }
        } else if (s.getBoolean(KEY_PLAYING, false) && (pkg == null || pkg.isEmpty() || pkg.equals(known))) {
            s.edit().putBoolean(KEY_PLAYING, false).putLong(KEY_STOPPED_AT, System.currentTimeMillis()).commit();
        }
    }

    /**
     * Whether the player's track comes from the network, read off its session (owner, 15.10.2026:
     * «якщо сесія грала стрім, а не локально, то і змісту запускати до мережі немає»; «ми можемо
     * якось це прочитати і зафіксувати десь?»): BUFFERING is a stream's state - a file never buffers -
     * and the track's URI or media id says http(s)/a service scheme or content/file. Kept per player
     * only when the session said something; with nothing said, {@link #NEEDS_NETWORK} decides.
     */
    public void noteSource(String pkg, boolean buffering, String mediaRef) {
        if (pkg == null || pkg.isEmpty() || NowPlaying.isRadioPackage(pkg)) return;
        Boolean streams = buffering ? Boolean.TRUE : streamsByRef(mediaRef);
        if (streams == null) return;
        final String key = KEY_STREAMS + pkg;
        SharedPreferences s = state();
        if (!s.contains(key) || s.getBoolean(key, false) != streams) {
            s.edit().putBoolean(key, streams).commit();
            Log.i(TAG, pkg + (streams ? " streams" : " plays local files")
                    + (buffering ? " (it buffered)" : " (" + mediaRef + ")"));
        }
    }

    /** true = a stream, false = a local file, null = the reference says neither. */
    static Boolean streamsByRef(String ref) {
        if (ref == null || ref.trim().isEmpty()) return null;
        final String r = ref.trim().toLowerCase(Locale.US);
        if (r.startsWith("content:") || r.startsWith("file:") || r.startsWith("/")
                || r.startsWith("android.resource:")) return Boolean.FALSE;
        if (r.startsWith("http:") || r.startsWith("https:") || r.startsWith("rtsp:")
                || r.startsWith("rtmp:") || r.startsWith("spotify:")) return Boolean.TRUE;
        return null;
    }

    /**
     * The player's audio session, not the system around it (owner, 15.10.2026: «ти аудіосесію плеєра
     * маєш зберігати а не всю систему»; «хто в машині слухає щось з відкритим інтерфейсом, там лаунчер
     * або навігатор»): the component that receives its media button, as the system itself records it
     * while that player plays ({@code Settings.Secure media_button_receiver}, "package/class,user" - kept
     * across a reboot, it is how Android starts the last player on a key). Kept per player while it plays.
     */
    private void noteSession(String pkg) {
        final String component = systemMediaButtonReceiver();
        if (component == null || !component.startsWith(pkg + "/")) return;
        final String key = KEY_RECEIVER + pkg;
        final SharedPreferences s = state();
        if (!component.equals(s.getString(key, ""))) {
            s.edit().putString(key, component).commit();
            Log.i(TAG, "player session remembered: media button receiver " + component);
        }
    }

    /** The system's last media button receiver, "package/class", or null. */
    private String systemMediaButtonReceiver() {
        try {
            String v = android.provider.Settings.Secure.getString(context.getContentResolver(),
                    "media_button_receiver");
            if (v == null || v.isEmpty()) return null;
            final int comma = v.indexOf(',');
            if (comma > 0) v = v.substring(0, comma);
            final ComponentName c = ComponentName.unflattenFromString(v);
            return c != null ? c.flattenToString() : null;
        } catch (Throwable t) {
            return null;
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
        s.edit().putString(KEY_SNAPSHOT, snapshot).putBoolean(KEY_ASLEEP, true).commit();
        Log.i(TAG, "ACC_OFF: " + why + (snapshot.isEmpty() ? " - nothing to resume" : " - remembered"));
    }

    /**
     * An orderly reboot or power-off ({@code ACTION_SHUTDOWN}): the same note as {@link #onAccOff},
     * taken while the player is still known. A power cut announces nothing - for that every change is
     * written as it happens, synchronously ({@code commit}), so the last record is the state before
     * it (owner, 15.10.2026: «не можемо записати коли перевантажуємося … що будемо з цим робити?»).
     */
    public void onShutdown() {
        SharedPreferences s = state();
        if (s.getBoolean(KEY_ASLEEP, false)) return;   // ACC_OFF already took it
        String pkg = s.getString(KEY_PLAYER, "");
        boolean playing = s.getBoolean(KEY_PLAYING, false)
                || System.currentTimeMillis() - s.getLong(KEY_STOPPED_AT, 0) <= PAUSE_GRACE_MS;
        boolean take = playing && !pkg.isEmpty() && !NowPlaying.isRadioPackage(pkg)
                && !NowPlaying.getInstance(context).isRadioSource();
        s.edit().putString(KEY_SNAPSHOT, take ? pkg : "").putBoolean(KEY_SHUTDOWN, true).commit();
        Log.i(TAG, "shutdown: " + (take ? pkg + " was playing - remembered" : "nothing to resume"));
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
        // An orderly reboot took the same note as ACC_OFF (onShutdown); a power cut left only the
        // running record, read below.
        boolean shutDown = s.getBoolean(KEY_SHUTDOWN, false);
        // Slept and then lost power: what was playing when the ignition went off. Power lost or
        // rebooted while driving: whatever was playing last - including a player the platform
        // paused on its way down, which is what a shutdown looks like from here. See
        // PAUSE_BEFORE_BOOT_MS; before it, this branch demanded that the player still be playing
        // and so found nothing every single time.
        String target;
        if (slept || shutDown) {
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
        s.edit().putBoolean(KEY_ASLEEP, false).putBoolean(KEY_SHUTDOWN, false).commit();
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
        s.edit().putBoolean(KEY_ASLEEP, false).commit();
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
     * A player that streams is not started before there is a network: it has nothing to play and
     * fails the request (owner, 15.10.2026). A local player goes straight on, and the wait ends the
     * moment the network is usable.
     */
    private void waitForNetwork(String pkg, String after, long deadline, boolean announced) {
        if (!needsNetwork(pkg) || hasUsableNetwork()) {
            if (announced) Log.i(TAG, "after " + after + ": the network is up - starting " + pkg);
            start(pkg, after);
            return;
        }
        if (SystemClock.elapsedRealtime() > deadline) {
            Log.w(TAG, "after " + after + ": no network after " + (NETWORK_WAIT_MS / 1000)
                    + " s - starting " + pkg + " anyway, it may refuse");
            start(pkg, after);
            return;
        }
        if (!announced) Log.i(TAG, "after " + after + ": " + pkg + " streams - waiting for the network");
        main.postDelayed(() -> waitForNetwork(pkg, after, deadline, true), NETWORK_POLL_MS);
    }

    /** What the session said about this player; the package list when it never said. */
    private boolean needsNetwork(String pkg) {
        final String key = KEY_STREAMS + pkg;
        final SharedPreferences s = state();
        if (s.contains(key)) return s.getBoolean(key, false);
        final String p = pkg.toLowerCase(Locale.US);
        for (String mark : NEEDS_NETWORK) {
            if (p.contains(mark)) return true;
        }
        return false;
    }

    /** Streaming players by name, for one whose session never said where its track comes from. */
    private static final String[] NEEDS_NETWORK = {
            "com.spotify", "youtube.music", "com.google.android.apps.youtube",
            "deezer", "tidal", "soundcloud", "yandex.music", "vk.music", "apple.music",
            "com.aspiro", "podcast",
    };

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

    /**
     * Brings the player's audio session back without its screen - in a car the screen is the launcher
     * or the navigator, and nobody listens with a player open (owner, 15.10.2026). Each rung is checked
     * {@link #VERIFY_MS} later before the next one:
     * <ol>
     *   <li>its live session - it still has its context;</li>
     *   <li>its media button receiver, by component: the system's own record of it, else the one in its
     *       manifest - an explicit broadcast starts the process with no screen, and the player opens its
     *       session to answer it;</li>
     *   <li>its session service ({@code MediaBrowserService}): bound, and asked through the session it
     *       hands back;</li>
     *   <li>only then its screen through the launcher's entry, the way DefaultAppsChanger {@code c0bca87}
     *       did it - wait for it on screen, a second and a half, PLAY, and the launcher put back.</li>
     * </ol>
     */
    private void start(String pkg, String after) {
        rung(pkg, after, 0);
    }

    private void rung(String pkg, String after, int step) {
        if (isPlaying(pkg)) {
            Log.i(TAG, "after " + after + ": " + pkg + " is playing");
            return;
        }
        final String how;
        switch (step) {
            case 0:
                if (!NowPlaying.getInstance(context).playPackage(pkg)) { rung(pkg, after, 1); return; }
                how = "its live session";
                break;
            case 1:
                if (!playThroughReceiver(pkg)) { rung(pkg, after, 2); return; }
                how = "its media button receiver";
                break;
            case 2:
                if (!playThroughBrowser(pkg)) { rung(pkg, after, 3); return; }
                how = "its session service";
                break;
            default:
                openAndPlay(pkg, after);
                return;
        }
        Log.i(TAG, "after " + after + ": asked " + pkg + " to play through " + how);
        main.postDelayed(() -> {
            if (isPlaying(pkg)) {
                Log.i(TAG, "after " + after + ": " + pkg + " is playing (" + how + ")");
            } else {
                Log.i(TAG, "after " + after + ": " + how + " did not start " + pkg + " - next");
                rung(pkg, after, step + 1);
            }
        }, VERIFY_MS);
    }

    private void openAndPlay(String pkg, String after) {
        if (!launchApp(pkg)) {
            Log.w(TAG, "after " + after + ": " + pkg + " would not start - its session, receiver and "
                    + "service were tried, and it has no screen to open");
            return;
        }
        Log.i(TAG, "after " + after + ": its session would not come back - opened " + pkg + " as the last resort");
        waitOnScreen(pkg, after, SystemClock.elapsedRealtime() + APPEAR_WAIT_MS);
    }

    /** A PLAY to the player's media button receiver by component - starts a player whose process is gone. */
    private boolean playThroughReceiver(String pkg) {
        String component = state().getString(KEY_RECEIVER + pkg, "");
        final String system = systemMediaButtonReceiver();
        if (component.isEmpty() && system != null && system.startsWith(pkg + "/")) component = system;
        ComponentName target = component.isEmpty() ? null : ComponentName.unflattenFromString(component);
        if (target == null) {
            List<ResolveInfo> receivers = context.getPackageManager().queryBroadcastReceivers(
                    new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0);
            if (receivers == null || receivers.isEmpty()) return false;
            target = new ComponentName(pkg, receivers.get(0).activityInfo.name);
        }
        try {
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(target)
                        .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)));
            }
            Log.i(TAG, "PLAY to " + target.flattenToShortString());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "PLAY to " + target.flattenToShortString() + " failed: " + t);
            return false;
        }
    }

    /** Binds the player's session service, which starts it without its screen, and plays through it. */
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
                        Log.w(TAG, "session service of " + pkg + " connected but would not play: " + t);
                    }
                    main.postDelayed(() -> {
                        try { holder[0].disconnect(); } catch (Throwable ignored) {}
                    }, BROWSER_HOLD_MS);
                }

                @Override
                public void onConnectionFailed() {
                    Log.w(TAG, "session service of " + pkg + " refused the connection");
                }
            }, null);
            holder[0].connect();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "session service of " + pkg + " failed: " + t);
            return false;
        }
    }

    /** DAC's {@code waitForAppToAppear}: the foreground every half second, fifteen seconds at most. */
    private void waitOnScreen(String pkg, String after, long deadline) {
        final String fg = foregroundPackage();
        if (pkg.equals(fg) || fg.isEmpty() || SystemClock.elapsedRealtime() > deadline) {
            if (!pkg.equals(fg)) {
                Log.i(TAG, "after " + after + ": " + pkg + (fg.isEmpty()
                        ? " - the foreground cannot be read, going on" : " never came on screen - going on"));
            }
            main.postDelayed(() -> {
                sendPlay(pkg);
                restoreForeground(pkg);
                verify(pkg, after, true);
            }, UI_SETTLE_MS);
            return;
        }
        main.postDelayed(() -> waitOnScreen(pkg, after, deadline), APPEAR_POLL_MS);
    }

    /** Whether the PLAY took; one more key if it did not, and the outcome in the log. */
    private void verify(String pkg, String after, boolean again) {
        main.postDelayed(() -> {
            if (isPlaying(pkg)) {
                Log.i(TAG, "after " + after + ": " + pkg + " is playing");
            } else if (again) {
                Log.i(TAG, "after " + after + ": " + pkg + " not playing yet - one more PLAY");
                sendPlay(pkg);
                verify(pkg, after, false);
            } else {
                Log.w(TAG, "after " + after + ": " + pkg + " would not play - opened and asked twice");
            }
        }, VERIFY_MS);
    }

    /**
     * DAC's PLAY: down and up, ordered, addressed to the package rather than to one component, so a
     * receiver the running player registered for itself hears it as well as the manifest's.
     */
    private void sendPlay(String pkg) {
        // An opened player has usually made its session by now, and that is what it listens to:
        // 📻 15.10.2026 20:08, the bench after a reboot - YouTube Music on screen, its own session
        // PAUSED at 2:56 with the queue restored, no media button session in the system, and two
        // keys to its package did nothing. DAC's later version waited for exactly that session.
        if (NowPlaying.getInstance(context).playPackage(pkg)) {
            Log.i(TAG, "PLAY through the session of " + pkg);
            return;
        }
        try {
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                Intent key = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                        .putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY));
                context.sendOrderedBroadcast(key, null);
            }
            Log.i(TAG, "PLAY sent to " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "PLAY to " + pkg + " failed: " + t);
        }
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
            final android.content.pm.PackageManager pm = context.getPackageManager();
            if (!back.isEmpty() && !back.equals(pkg) && !back.equals(context.getPackageName())) {
                // A launcher goes back through its HOME activity. 🔴 15.10.2026 20:08: its launcher
                // entry was taken instead and opened the launcher chooser on the owner's unit (fifteen
                // launchers installed, none set as the default).
                Intent itsHome = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(back);
                android.content.pm.ResolveInfo home = pm.resolveActivity(itsHome, 0);
                Intent previous = home != null && home.activityInfo != null
                        ? itsHome.setComponent(new ComponentName(back, home.activityInfo.name))
                        : pm.getLaunchIntentForPackage(back);
                if (previous != null) {
                    previous.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    context.startActivity(previous);
                    Log.i(TAG, "screen put back to " + back + (home != null ? " (its home)" : ""));
                    return;
                }
            }
            // The generic HOME only where a default launcher is set - otherwise it is the chooser.
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            android.content.pm.ResolveInfo def = pm.resolveActivity(home,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if (def == null || def.activityInfo == null || "android".equals(def.activityInfo.packageName)) {
                Log.i(TAG, "screen left as it is - no default launcher to go back to");
                return;
            }
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
