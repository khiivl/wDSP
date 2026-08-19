package com.radiorubka.wdsp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Decides which audio session {@link AudioSpectrumEngine} should attach its Visualizer to.
 *
 * Why this exists: a Visualizer created on session 0 is an "output mix" effect, and
 * AudioPolicyManager::getOutputForEffect() hard-prefers the PRIMARY output. Stock QF policies
 * expose exactly two outputs - "primary output" and "fast" - and route all media to the fast one,
 * leaving primary in standby. The session-0 effect therefore processes an idle thread and yields
 * silence. Custom audio policies (users running a Magisk module) move media back onto primary, and
 * there session 0 works - so session 0 stays a legitimate answer, it just cannot be assumed.
 *
 * A session-targeted effect is created on whichever thread the track actually lives on, which is
 * the way out. Measured on a K706: attaching to another app's session is permitted for a plain
 * app, gives full control, and costs no underruns.
 *
 * Finding the number is the whole problem, so we try three sources, cheapest first:
 *
 * <ol>
 *   <li>a session announced by the player itself through ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION;</li>
 *   <li>the session last seen carrying audio for this player package;</li>
 *   <li>a sweep from {@link AudioManager#generateAudioSessionId()} downwards, session 0 first.</li>
 * </ol>
 *
 * The sweep sounds brutal but is not: sessions that do not exist are rejected by the effect engine
 * in about a millisecond ("Cannot initialize Visualizer engine, error: -3"), so only the handful of
 * real sessions cost a measurement window. A full 48-candidate sweep measured 0.9 s.
 */
public final class SessionResolver {
    private static final String TAG = "wDSP_SessionResolver";

    /** How long to listen to a candidate before deciding it is silent. */
    private static final int PROBE_MS = 120;
    /** Longer window for the candidate we were told about - worth being sure. */
    private static final int VERIFY_MS = 250;
    /**
     * How far down the session counter to walk. Generous on purpose: the counter keeps climbing
     * for as long as the unit is up - every app start and every call to generateAudioSessionId()
     * pushes it - so a player that has been running since boot can sit hundreds of ids below it.
     * A narrow window silently missed it. This costs almost nothing: an id with nothing behind it
     * is rejected by the effect engine in about a millisecond.
     */
    private static final int MAX_CANDIDATES = 512;
    /** A player announcing its session is only trusted for this long. */
    private static final long HINT_TTL_MS = 5 * 60 * 1000L;

    public interface Callback {
        /** Called off the main thread with a session known to carry audio, or -1 if none found. */
        void onSessionResolved(int sessionId);
    }

    private static SessionResolver instance;

    private final Context context;
    private final Map<String, Integer> cacheByPackage = new HashMap<>();

    private volatile int hintedSession = -1;
    private volatile long hintedAt = 0;
    private volatile boolean resolving = false;
    private BroadcastReceiver effectSessionReceiver;

    public static synchronized SessionResolver getInstance(Context ctx) {
        if (instance == null) {
            instance = new SessionResolver(ctx.getApplicationContext());
        }
        return instance;
    }

    private SessionResolver(Context context) {
        this.context = context;
    }

    /**
     * Starts listening for players that announce their session. Well-behaved players (Poweramp,
     * VLC, anything built on MediaPlayer) send this; YouTube Music does not, which is exactly why
     * the sweep below still has to exist.
     */
    public synchronized void start() {
        if (effectSessionReceiver != null) return;
        effectSessionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent.getAction();
                int sid = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
                if (sid <= 0) return;
                if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
                    hintedSession = sid;
                    hintedAt = System.currentTimeMillis();
                    Log.i(TAG, "Player announced session " + sid + " ("
                            + intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) + ")");
                } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(action)) {
                    if (hintedSession == sid) hintedSession = -1;
                    forget(sid);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        try {
            context.registerReceiver(effectSessionReceiver, filter);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot register effect-session receiver: " + t);
            effectSessionReceiver = null;
        }
    }

    /** Drops a session from every cache - call when it stopped delivering audio. */
    public synchronized void forget(int sessionId) {
        if (hintedSession == sessionId) hintedSession = -1;
        cacheByPackage.values().removeAll(java.util.Collections.singleton(sessionId));
    }

    public boolean isResolving() {
        return resolving;
    }

    /**
     * Resolves asynchronously and calls back on a worker thread. Only one resolution runs at a
     * time; overlapping requests are dropped rather than queued.
     *
     * @param playerPackage active player from sys.qf.last_audio_src, may be null
     */
    public boolean resolveAsync(final String playerPackage, final Callback callback) {
        synchronized (this) {
            if (resolving) return false;
            resolving = true;
        }
        new Thread(() -> {
            int found = -1;
            try {
                found = resolve(playerPackage);
            } catch (Throwable t) {
                Log.w(TAG, "Resolution failed: " + t);
            } finally {
                resolving = false;
            }
            if (callback != null) callback.onSessionResolved(found);
        }, "wDSP_SessionResolver").start();
        return true;
    }

    private int resolve(String playerPackage) {
        long started = System.currentTimeMillis();

        int hint = hintedSession;
        if (hint > 0 && System.currentTimeMillis() - hintedAt < HINT_TTL_MS) {
            if (SessionProbe.probe(hint, VERIFY_MS).hasSignal()) {
                Log.i(TAG, "Resolved from player announcement: session " + hint);
                remember(playerPackage, hint);
                return hint;
            }
            Log.d(TAG, "Announced session " + hint + " is silent, falling through");
        }

        Integer cached;
        synchronized (this) {
            cached = playerPackage != null ? cacheByPackage.get(playerPackage) : null;
        }
        if (cached != null) {
            if (SessionProbe.probe(cached, PROBE_MS).hasSignal()) {
                Log.i(TAG, "Resolved from cache: " + playerPackage + " -> session " + cached);
                return cached;
            }
            Log.d(TAG, "Cached session " + cached + " for " + playerPackage + " went silent");
            forget(cached);
        }

        int swept = sweep();
        if (swept >= 0) {
            Log.i(TAG, "Resolved by sweep: session " + swept + " in "
                    + (System.currentTimeMillis() - started) + " ms");
            remember(playerPackage, swept);
        } else {
            Log.w(TAG, "Sweep found no session carrying audio ("
                    + (System.currentTimeMillis() - started) + " ms)");
        }
        return swept;
    }

    /**
     * Session 0 goes first on purpose: on head units with custom audio policies it is the working
     * answer, and probing it costs one window. Everything below it is walked newest-first, because
     * the session that just started playing is the highest-numbered one.
     */
    private int sweep() {
        if (SessionProbe.probe(0, PROBE_MS).hasSignal()) return 0;

        int counter = 0;
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) counter = am.generateAudioSessionId();
        } catch (Throwable t) {
            Log.w(TAG, "generateAudioSessionId failed: " + t);
        }
        if (counter <= 0) return -1;

        int probed = 0;
        for (int sid = counter - 1; sid > 0 && probed < MAX_CANDIDATES; sid--, probed++) {
            if (SessionProbe.probe(sid, PROBE_MS).hasSignal()) return sid;
        }
        return -1;
    }

    private synchronized void remember(String playerPackage, int sessionId) {
        if (playerPackage != null && !playerPackage.isEmpty() && sessionId > 0) {
            cacheByPackage.put(playerPackage, sessionId);
        }
    }
}
