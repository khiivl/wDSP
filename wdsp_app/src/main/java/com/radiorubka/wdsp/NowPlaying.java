package com.radiorubka.wdsp;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.qf.musicplayer.bean.MusicInfoData;

import java.util.List;

/**
 * What is playing right now: who, what, how far through, and a picture.
 *
 * <h2>Three sources, because no single one covers the car</h2>
 *
 * <ol>
 *   <li><b>The platform broadcast.</b> The built-in player sends
 *       {@code com.qf.musicplayer.action.UPDATE_ACTION} with a parcel of title, artist, album,
 *       position and duration - the same one the launcher's own widget reads. Free, instant, and
 *       only ever about that one player.</li>
 *   <li><b>Media sessions.</b> Everything else - Spotify, YouTube Music, Bluetooth audio - reports
 *       through {@code MediaSessionManager}, which the launcher can read because it is a system
 *       app and we cannot unless the owner grants notification access. So it is asked for only
 *       when somebody switches the track display on, and everything still works without it.</li>
 *   <li><b>The active package.</b> {@code sys.qf.last_audio_src} always names the app that owns
 *       the sound, so its icon and name are available whatever else fails. Not a track, but it
 *       beats an empty strip.</li>
 * </ol>
 *
 * <p>The three are layered, not merged: a session that is actually playing wins over a stale
 * broadcast, and the package is the floor under both.
 */
public final class NowPlaying {

    private static final String TAG = "wDSP_NowPlaying";

    private static final String ACTION_QF_MUSIC = "com.qf.musicplayer.action.UPDATE_ACTION";
    private static final String EXTRA_QF_MUSIC = "com.qf.musicplayer.action.UPDATE_ACTION_musicinfo";
    private static final String PROP_AUDIO_SRC = "sys.qf.last_audio_src";
    private static final String PROP_RADIO_STATUS = "sys.qf.radio.status";
    private static final String PROP_SOUND_CHANNEL = "sys.qf.sound.channel";
    /** The MCU channel that means the tuner is connected to the amplifier. */
    private static final String CHANNEL_RADIO = "2";

    /**
     * Radio apps, by package. Prefix match, because the family shares a stem and the factory one
     * ships under two names on different builds.
     */
    private static final String[] RADIO_PACKAGES = {
            "com.android.fmradio",
            "com.kostyamat.fmradio",
            "com.qf.radio",
            "com.qf.fmradio",
    };

    /** What the platform's parcel calls "playing". Anything else is treated as stopped. */
    private static final int QF_STATUS_PLAYING = 16;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String title;
    private String artist;
    private String album;
    private boolean playing;
    private long positionMs;
    private long durationMs;
    private long positionTakenAt;
    private Bitmap art;
    private String artKey;
    private String playerPackage = "";

    private BroadcastReceiver receiver;
    private MediaSessionManager sessions;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsListener;
    private MediaController controller;
    private MediaController.Callback controllerCallback;

    /**
     * Told the moment playback starts, so the bars do not wait for the next poll.
     */
    private Runnable onStarted;

    /**
     * Told the moment it stops.
     *
     * <p>Whether that is worth acting on is not decided here. Most stops have to be sat on for a
     * few seconds first, because a gap between tracks looks identical from this side - see the
     * screensaver's PAUSE_HOLD_MS. But when the caller already knows why the music stopped, it
     * should not then have to wait for a poll to hear that it did.
     */
    private Runnable onStopped;

    public interface MetadataListener {
        void onMetadataChanged();
    }
    private MetadataListener metadataListener;

    public void setMetadataListener(MetadataListener listener) {
        this.metadataListener = listener;
    }

    public void notifyMetadataChanged() {
        MetadataListener l = this.metadataListener;
        if (l != null) {
            main.post(l::onMetadataChanged);
        }
    }

    public void setOnStarted(Runnable listener) {
        this.onStarted = listener;
    }

    public void setOnStopped(Runnable listener) {
        this.onStopped = listener;
    }

    private static NowPlaying instance;

    public static synchronized NowPlaying getInstance(Context context) {
        if (instance == null) instance = new NowPlaying(context.getApplicationContext());
        return instance;
    }

    private NowPlaying(Context context) {
        this.context = context;
    }

    // -------------------------------------------------------------------------------------------
    // what the screensaver asks for
    // -------------------------------------------------------------------------------------------

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized String title() {
        return title;
    }

    public synchronized String artist() {
        return artist;
    }

    public synchronized String album() {
        return album;
    }

    public synchronized Bitmap art() {
        return art;
    }

    /**
     * The part of {@link #line()} without the artist: album and title, in reading order.
     *
     * <p>Split out because only this half is worth scrolling. The artist changes rarely, is short,
     * and reads as a label on the strip rather than as part of the sentence - sliding it past
     * makes the eye chase the one word it already knew. Null when there is nothing but an artist.
     */
    public synchronized String titleLine() {
        StringBuilder sb = new StringBuilder();
        if (notEmpty(album) && !album.equalsIgnoreCase(title)) sb.append(album);
        if (notEmpty(title)) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append(title);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** One line for the marquee, in the order a person reads it. Null when there is nothing. */
    public synchronized String line() {
        StringBuilder sb = new StringBuilder();
        if (notEmpty(artist)) sb.append(artist);
        // Singles come back with the album set to the track name - YT Music does it, and reading
        // "Heart On The Floor - Heart On The Floor" twice in one line looks like a bug in us.
        if (notEmpty(album) && !album.equalsIgnoreCase(title)) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(album);
        }
        if (notEmpty(title)) {
            if (sb.length() > 0) sb.append(" — ");
            sb.append(title);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * How far through, 0..1, or -1 when it cannot be known.
     *
     * <p>Extrapolated from the last reported position rather than asked for again: a session
     * reports where it was at a moment, and a progress line that only moves when the player
     * happens to send an update stutters visibly.
     */
    public synchronized float progress() {
        if (durationMs <= 0) return -1f;
        long now = positionMs;
        if (playing && positionTakenAt > 0) {
            now += System.currentTimeMillis() - positionTakenAt;
        }
        if (now < 0) now = 0;
        if (now > durationMs) now = durationMs;
        return now / (float) durationMs;
    }

    /** The icon of whichever app owns the sound, or null. */
    public Drawable playerIcon() {
        String pkg = currentPackage();
        if (pkg.isEmpty()) return null;
        try {
            return context.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public String playerLabel() {
        String pkg = currentPackage();
        if (pkg.isEmpty()) return null;
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable ignored) {
            return pkg;
        }
    }

    /**
     * Whether there is anything worth putting on the strip at all.
     *
     * <p>Separate from {@link #isPlaying()} on purpose: a paused player still has a track, and the
     * screensaver wants to keep showing it under the clock rather than blanking the line the
     * moment the music stops.
     */
    public synchronized boolean hasTrack() {
        return notEmpty(title) || notEmpty(artist) || notEmpty(album) || art != null;
    }

    /**
     * The application the sound belongs to, for anyone who has to name it to the platform.
     *
     * <p>The MCU service needs it to label the preset, and playback capture is filtered by uid, so
     * recording one player and nothing else starts with knowing which package that is.
     */
    public synchronized String playerPackage() {
        return currentPackage();
    }

    private synchronized String currentPackage() {
        if (notEmpty(playerPackage)) {
            return playerPackage;
        }
        String prop = HardwareProfile.systemProperty(PROP_AUDIO_SRC);
        return prop == null ? "" : prop;
    }

    public static boolean isRadioPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String r : RADIO_PACKAGES) {
            if (pkg.startsWith(r)) return true;
        }
        return false;
    }

    /**
     * Whether the sound is coming from the analogue tuner rather than from a file or a stream.
     *
     * <h2>What turns on it</h2>
     *
     * The bars. Radio does not pass through AudioFlinger on this platform - it is a wire from the
     * tuner to the amplifier - so the spectrum engine is measuring silence, and bars drawn from
     * that would be a convincing lie. On radio the screensaver shows the clock and whatever the
     * radio app publishes about the station instead.
     *
     * <h2>Three lines, and why it used to be twenty</h2>
     *
     * <ol>
     *   <li><b>Heard before asked.</b> If the spectrum engine is hearing anything, whatever is
     *       playing cannot be the tuner - radio never reaches the mixer. This has to come first
     *       and cannot be dropped: the radio app raises its radio flag for an internet stream too,
     *       and a stream does go through the mixer. Reported from a car: the strip showed a full
     *       spectrum while the screensaver put up a clock.</li>
     *   <li><b>The MCU channel.</b> Evidence <em>for</em> the tuner and never against it - 2
     *       decides, anything else says nothing. It reads reliably only where the BitPerfect
     *       policies are installed and wanders on a factory unit, but the asymmetry costs nothing:
     *       a stray 2 while music plays never gets here, because there is signal.</li>
     *   <li><b>The radio flag</b>, which the radio app now sets properly.</li>
     * </ol>
     *
     * <p>📻 Verified on the wire 25.08.2026 with the tuner playing: {@code sys.qf.radio.status} was
     * true, {@code sys.qf.sound.channel} was 2, and the volume type and owning package agreed.
     *
     * <p>What used to follow - the volume type, the media session's package, and two flavours of
     * {@code last_audio_src} matched against a list of radio package names - is gone. The list was
     * the rung that lied: a radio app playing a stream is the same package doing something else
     * entirely, and it is what put a clock over a working spectrum. With the properties honest,
     * naming the app adds nothing except a way to be wrong.
     */
    public boolean isRadioSource() {
        if (AudioSpectrumEngine.getInstance().hasSignalNow()) return false;

        if (CHANNEL_RADIO.equals(HardwareProfile.systemProperty(PROP_SOUND_CHANNEL))) return true;
        return isTrue(HardwareProfile.systemProperty(PROP_RADIO_STATUS));
    }

    private static boolean isTrue(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    // -------------------------------------------------------------------------------------------
    // starting and stopping
    // -------------------------------------------------------------------------------------------

    public void start() {
        main.post(() -> {
            listenToBroadcast();
            listenToSessions();
        });
    }

    private void listenToBroadcast() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                try {
                    MusicInfoData info = intent.getParcelableExtra(EXTRA_QF_MUSIC);
                    if (info != null) acceptBroadcast(info);
                } catch (Throwable t) {
                    Log.w(TAG, "could not read the platform's now-playing parcel", t);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_QF_MUSIC);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not listen for the platform's player", t);
            receiver = null;
        }
    }

    private synchronized void acceptBroadcast(MusicInfoData info) {
        // Only while no media session is speaking for a real player: the built-in player keeps
        // sending its last state after it has stopped, and a stale title outranking a live one is
        // exactly the sort of thing nobody would think to look for.
        if (controller != null) return;
        title = info.getName();
        artist = info.getArtist();
        album = info.getAlbum();
        playing = info.getCurPlayStatus() == QF_STATUS_PLAYING;
        positionMs = info.getCurrTime();
        durationMs = info.getTotalTime();
        positionTakenAt = System.currentTimeMillis();
        playerPackage = "";
        loadArtFromFile(info.getPath());
    }

    /**
     * Cover art for the built-in player, which sends a path rather than a picture.
     *
     * <p>Keyed by that path so the file is opened once per track and not once per frame.
     */
    private void loadArtFromFile(String path) {
        if (path == null || path.isEmpty()) {
            art = null;
            artKey = null;
            return;
        }
        if (path.equals(artKey)) return;
        artKey = path;
        art = null;
        new Thread(() -> {
            Bitmap bitmap = null;
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(path);
                byte[] bytes = retriever.getEmbeddedPicture();
                if (bytes != null) {
                    bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
            synchronized (NowPlaying.this) {
                if (path.equals(artKey)) art = bitmap;
            }
            notifyMetadataChanged();
        }, "wDSP_AlbumArt").start();
    }

    /**
     * Loads cover art from a content://, file://, or http(s):// URI asynchronously.
     */
    private void loadArtFromUri(String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return;
        if (uriStr.equals(artKey)) return;
        artKey = uriStr;
        art = null;
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                if (uriStr.startsWith("content://") || uriStr.startsWith("android.resource://")) {
                    android.net.Uri uri = android.net.Uri.parse(uriStr);
                    try (java.io.InputStream is = context.getContentResolver().openInputStream(uri)) {
                        if (is != null) bitmap = android.graphics.BitmapFactory.decodeStream(is);
                    }
                } else if (uriStr.startsWith("file://") || uriStr.startsWith("/")) {
                    String path = uriStr.startsWith("file://") ? uriStr.substring(7) : uriStr;
                    bitmap = android.graphics.BitmapFactory.decodeFile(path);
                } else if (uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                    java.net.URL url = new java.net.URL(uriStr);
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    try (java.io.InputStream is = conn.getInputStream()) {
                        if (is != null) bitmap = android.graphics.BitmapFactory.decodeStream(is);
                    }
                }
            } catch (Throwable ignored) {
            }
            synchronized (NowPlaying.this) {
                if (uriStr.equals(artKey)) art = bitmap;
            }
            notifyMetadataChanged();
        }, "wDSP_UriArt").start();
    }

    // -------------------------------------------------------------------------------------------
    // media sessions - everything that is not the built-in player
    // -------------------------------------------------------------------------------------------

    /** Whether the owner has given us notification access, which is what sessions need. */
    public boolean canReadSessions() {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        return enabled != null && enabled.contains(context.getPackageName());
    }

    public void refresh() {
        main.post(() -> {
            if (sessions == null) {
                listenToSessions();
            } else if (canReadSessions()) {
                try {
                    ComponentName listener = new ComponentName(context, NotificationAccess.class);
                    adoptSession(sessions.getActiveSessions(listener));
                } catch (Throwable t) {
                    Log.w(TAG, "could not refresh active sessions", t);
                }
            }
        });
    }

    private void listenToSessions() {
        if (sessions != null || !canReadSessions()) return;
        try {
            sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (sessions == null) return;
            ComponentName listener = new ComponentName(context, NotificationAccess.class);
            sessionsListener = controllers -> main.post(() -> adoptSession(controllers));
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listener);
            adoptSession(sessions.getActiveSessions(listener));
        } catch (Throwable t) {
            Log.w(TAG, "media sessions are not available to us", t);
            sessions = null;
        }
    }

    /** Picks the session that is actually making sound, preferring one that is playing. */
    private void adoptSession(List<MediaController> controllers) {
        MediaController best = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (c == null) continue;
                PlaybackState state = c.getPlaybackState();
                boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
                if (isPlaying) {
                    best = c;
                    break;
                }
                if (best == null) {
                    String pkg = c.getPackageName();
                    boolean isRadio = isRadioPackage(pkg);
                    if (!isRadio || isPlaying) {
                        best = c;
                    }
                }
            }
        }
        if (controller != null && controllerCallback != null) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (Throwable ignored) {
            }
        }
        controller = best;
        if (controller == null) {
            synchronized (this) {
                playerPackage = "";
                title = null;
                artist = null;
                album = null;
                durationMs = 0;
                art = null;
                artKey = null;
            }
            return;
        }
        controllerCallback = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                acceptMetadata(metadata);
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                acceptState(state);
            }
        };
        controller.registerCallback(controllerCallback);
        synchronized (this) {
            playerPackage = controller.getPackageName() == null ? "" : controller.getPackageName();
        }
        Log.i(TAG, "adoptSession adopted: " + playerPackage);
        acceptMetadata(controller.getMetadata());
        acceptState(controller.getPlaybackState());
    }

    private synchronized void acceptMetadata(MediaMetadata metadata) {
        if (metadata == null) {
            title = null;
            artist = null;
            album = null;
            durationMs = 0;
            art = null;
            artKey = null;
            return;
        }
        title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (!notEmpty(title)) title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (!notEmpty(title) && metadata.getDescription() != null && metadata.getDescription().getTitle() != null) {
            title = metadata.getDescription().getTitle().toString();
        }
        artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (!notEmpty(artist)) artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (!notEmpty(artist)) artist = metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR);
        if (!notEmpty(artist)) artist = metadata.getString(MediaMetadata.METADATA_KEY_COMPOSER);
        if (!notEmpty(artist)) artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        if (!notEmpty(artist) && metadata.getDescription() != null && metadata.getDescription().getSubtitle() != null) {
            artist = metadata.getDescription().getSubtitle().toString();
        }
        album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        if (!notEmpty(album)) album = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
        if (!notEmpty(album) && metadata.getDescription() != null && metadata.getDescription().getDescription() != null) {
            album = metadata.getDescription().getDescription().toString();
        }
        durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        Bitmap bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bitmap == null) bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bitmap == null && metadata.getDescription() != null) {
            bitmap = metadata.getDescription().getIconBitmap();
        }
        art = bitmap;
        if (art == null) {
            String uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (uriStr == null) uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (uriStr == null && metadata.getDescription() != null && metadata.getDescription().getIconUri() != null) {
                uriStr = metadata.getDescription().getIconUri().toString();
            }
            if (uriStr != null && !uriStr.isEmpty()) {
                loadArtFromUri(uriStr);
            } else {
                artKey = null;
            }
        } else {
            artKey = null;
        }
        Log.i(TAG, "acceptMetadata title=" + title + ", artist=" + artist + ", album=" + album);
        notifyMetadataChanged();
    }

    private void acceptState(PlaybackState state) {
        if (state == null) return;
        boolean started;
        boolean stopped;
        synchronized (this) {
            boolean was = playing;
            playing = state.getState() == PlaybackState.STATE_PLAYING;
            positionMs = state.getPosition();
            positionTakenAt = System.currentTimeMillis();
            if (isRadioPackage(playerPackage) && !playing) {
                title = null;
                artist = null;
                album = null;
                art = null;
                artKey = null;
                playerPackage = "";
            }
            started = playing && !was;
            stopped = !playing && was;
        }
        notifyMetadataChanged();
        if (started && onStarted != null) main.post(onStarted);
        if (stopped && onStopped != null) main.post(onStopped);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
