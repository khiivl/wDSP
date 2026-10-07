package com.radiorubka.wdsp;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Audio Check - the author's 1.0 test-signal tab: the four stems of one song (bass, vocal, drums,
 * melody) looped in sync and freely combined, pink noise, a sine generator, a logarithmic sweep that
 * can be normalised to the threshold of hearing, a spoken test per speaker, and "only the
 * subwoofer". His sound engine, moved out of the activity into one class (the activity keeps only the
 * controls), and joined to what a real QF needs:
 *
 * <ul>
 *   <li><b>the sound is taken</b> while anything plays ({@link PlaybackFocus}, the cabin
 *       measurement's own request): on QF a test played under the radio is not heard at all;</li>
 *   <li><b>everything stops</b> when the focus is lost (a call, a navigation prompt, another player)
 *       and when the activity leaves the screen, as in his version;</li>
 *   <li>the overrides of the fader and of "only sub" are <b>runtime only</b>, as in his version -
 *       broadcasts to {@link McuService}, never written to a preset - and are cleared on every stop.</li>
 * </ul>
 *
 * <p>All of it on one audio session (his design), so one Visualizer sees every signal; the spectrum
 * is told the session the standard way, {@link AudioEffect#ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION},
 * which {@link SessionResolver} already listens for. Generated signals are written at 48 kHz - the
 * rate a QF unit outputs - so no resampler sits between the generator and the speaker.
 *
 * <p>Main thread only.
 */
final class AudioCheck {
    private static final String TAG = "wDSP_AudioCheck";

    /** The runtime overrides McuService listens for - the author's action names. */
    static final String ACTION_FADER = "com.radiorubka.wdsp.AUDIOCHECK_FADER";
    static final String ACTION_ONLY_SUB = "com.radiorubka.wdsp.AUDIOCHECK_ONLY_SUB";

    /** The four stems of the test song. */
    enum Stem {
        BASS(R.raw.audiocheck_bass), VOCAL(R.raw.audiocheck_vocal),
        DRUMS(R.raw.audiocheck_drums), MELODY(R.raw.audiocheck_melody);
        final int rawRes;
        Stem(int rawRes) { this.rawRes = rawRes; }
    }

    /**
     * The speaker tests and the fader each one needs: 0..24 with 12 the centre, low = rear / left,
     * high = front / right - the author's convention, which he checked on real hardware.
     */
    enum Speaker {
        FL(R.raw.audiocheck_fl, 24, 0), FR(R.raw.audiocheck_fr, 24, 24),
        RL(R.raw.audiocheck_rl, 0, 0), RR(R.raw.audiocheck_rr, 0, 24);
        final int rawRes, faderFr, faderLr;
        Speaker(int rawRes, int faderFr, int faderLr) {
            this.rawRes = rawRes; this.faderFr = faderFr; this.faderLr = faderLr;
        }
    }

    /** Told when the state changed by itself - a speaker test ended, the focus went, a call came. */
    interface Listener {
        void onAudioCheckChanged();
    }

    static final float SINE_MIN_HZ = 20f, SINE_MAX_HZ = 20000f;
    private static final int GEN_RATE = 48000;
    /** The author's AC_STEM_SYNC_CHECK_MS / AC_STEM_SYNC_TOLERANCE_MS. */
    private static final long STEM_SYNC_CHECK_MS = 3000;
    private static final float STEM_SYNC_TOLERANCE_MS = 40f;
    /** The author's SINE_REBUILD_DEBOUNCE_MS. */
    private static final long SINE_REBUILD_DEBOUNCE_MS = 80;

    private static AudioCheck instance;

    static synchronized AudioCheck get(Context context) {
        if (instance == null) instance = new AudioCheck(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PlaybackFocus focus = new PlaybackFocus(TAG);
    private Listener listener;
    private int sessionId = -1;
    private boolean sessionAnnounced;

    private final Map<Stem, LoopTrack> stems = new EnumMap<>(Stem.class);
    private long stemEpochNanos = -1;
    private LoopTrack pinkNoise, sine, sweep;
    private int sineHz = 1000;
    private Runnable pendingSineRebuild;
    private MediaPlayer speakerPlayer;
    private Speaker activeSpeaker;
    private boolean onlySub;

    private final Runnable stemSyncWatchdog = this::checkStemSync;

    private AudioCheck(Context app) {
        this.app = app;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    // ---- state ----

    boolean isStemOn(Stem stem) { return isPlaying(stems.get(stem)); }
    boolean isPinkNoiseOn() { return isPlaying(pinkNoise); }
    boolean isSineOn() { return sine != null; }
    boolean isSweepOn() { return sweep != null; }
    boolean isOnlySub() { return onlySub; }
    Speaker activeSpeaker() { return activeSpeaker; }
    int sineHz() { return sineHz; }

    /** A narrow-band tone is playing - the spectrum can stop smoothing it (the author's reason). */
    boolean isTonePlaying() { return isSineOn() || isSweepOn(); }

    private boolean anythingPlays() {
        for (LoopTrack t : stems.values()) if (isPlaying(t)) return true;
        return isPlaying(pinkNoise) || sine != null || sweep != null || speakerPlayer != null || onlySub;
    }

    // ---- stems ----

    /**
     * Starts or stops one stem, joined in phase with the stems already playing: a stem switched on
     * late starts where the others are in the shared loop, not at its beginning (the author's sync).
     */
    void setStem(Stem stem, boolean on) {
        LoopTrack lt = stems.get(stem);
        if (on) {
            if (!take()) return;
            if (lt == null) {
                lt = loadLoop(stem.rawRes);
                if (lt == null) return;
                stems.put(stem, lt);
            }
            if (stemEpochNanos < 0) {
                stemEpochNanos = System.nanoTime();
                lt.track.setPlaybackHeadPosition(0);
                main.postDelayed(stemSyncWatchdog, STEM_SYNC_CHECK_MS);
            } else {
                double elapsedSec = (System.nanoTime() - stemEpochNanos) / 1e9;
                lt.track.pause();
                lt.track.setPlaybackHeadPosition((int) ((elapsedSec * lt.sampleRate) % lt.frames));
            }
            lt.track.play();
        } else if (lt != null) {
            lt.track.pause();
            if (!anyStemPlaying()) stemEpochNanos = -1;
            giveBackIfIdle();
        }
    }

    private boolean anyStemPlaying() {
        for (LoopTrack t : stems.values()) if (isPlaying(t)) return true;
        return false;
    }

    /**
     * The author's watchdog: while two or more stems play, snaps any that drifted further than
     * {@value #STEM_SYNC_TOLERANCE_MS} ms from the shared clock back into line.
     */
    private void checkStemSync() {
        int playing = 0;
        for (LoopTrack t : stems.values()) if (isPlaying(t)) playing++;
        if (stemEpochNanos >= 0 && playing >= 2) {
            double elapsedSec = (System.nanoTime() - stemEpochNanos) / 1e9;
            for (LoopTrack lt : stems.values()) {
                if (!isPlaying(lt)) continue;
                int expected = (int) ((elapsedSec * lt.sampleRate) % lt.frames);
                int actual = lt.track.getPlaybackHeadPosition() % lt.frames;
                int diff = Math.abs(expected - actual);
                diff = Math.min(diff, lt.frames - diff);
                if (diff > (int) (STEM_SYNC_TOLERANCE_MS / 1000f * lt.sampleRate)) {
                    lt.track.pause();
                    lt.track.setPlaybackHeadPosition(expected);
                    lt.track.play();
                }
            }
        }
        if (anyStemPlaying()) main.postDelayed(stemSyncWatchdog, STEM_SYNC_CHECK_MS);
    }

    // ---- generators ----

    void setPinkNoise(boolean on) {
        if (on) {
            if (!take()) return;
            if (pinkNoise == null) pinkNoise = buildPinkNoise();
            pinkNoise.track.play();
        } else if (pinkNoise != null) {
            pinkNoise.track.pause();
            giveBackIfIdle();
        }
    }

    void setSine(boolean on) {
        cancelSineRebuild();
        releaseLoop(sine);
        sine = null;
        if (on) {
            if (!take()) return;
            sine = buildSine(sineHz);
            sine.track.play();
        } else {
            giveBackIfIdle();
        }
        updateCurveWidth();
    }

    /** A new frequency, clamped to 20 Hz .. 20 kHz; a playing tone follows it after the debounce. */
    void setSineHz(int hz) {
        sineHz = Math.max((int) SINE_MIN_HZ, Math.min((int) SINE_MAX_HZ, hz));
        if (sine == null) return;
        cancelSineRebuild();
        final int target = sineHz;
        pendingSineRebuild = () -> { pendingSineRebuild = null; crossfadeSine(target); };
        main.postDelayed(pendingSineRebuild, SINE_REBUILD_DEBOUNCE_MS);
    }

    private void cancelSineRebuild() {
        if (pendingSineRebuild != null) main.removeCallbacks(pendingSineRebuild);
        pendingSineRebuild = null;
    }

    /**
     * The author's crossfade: the old buffer stops at an arbitrary point of its waveform and the new
     * one starts at zero, so a straight swap clicks every time; a ramp of a few milliseconds hides it.
     */
    private void crossfadeSine(int hz) {
        if (sine == null) return;
        final LoopTrack old = sine;
        final LoopTrack fresh = buildSine(hz);
        fresh.track.setVolume(0f);
        fresh.track.play();
        sine = fresh;
        final int steps = 4, stepMs = 6;
        for (int i = 1; i <= steps; i++) {
            final float t = i / (float) steps;
            main.postDelayed(() -> {
                fresh.track.setVolume(t);
                old.track.setVolume(1f - t);
            }, (long) i * stepMs);
        }
        main.postDelayed(() -> releaseLoop(old), (long) (steps + 1) * stepMs);
    }

    /** Starts the up-and-down logarithmic sweep with the author's limits; one cycle is {@code durationSec}. */
    void startSweep(float startHz, float endHz, float durationSec, boolean normalize) {
        stopSweep();
        if (!take()) return;
        float start = clampHz(startHz), end = clampHz(endHz);
        float duration = Math.max(0.5f, Math.min(300f, durationSec));
        sweep = buildSweep(start, end, duration, normalize);
        sweep.track.play();
        updateCurveWidth();
    }

    void stopSweep() {
        releaseLoop(sweep);
        sweep = null;
        giveBackIfIdle();
        updateCurveWidth();
    }

    private static float clampHz(float hz) {
        return Math.max(SINE_MIN_HZ, Math.min(SINE_MAX_HZ, hz));
    }

    // ---- speakers and the subwoofer ----

    /**
     * Plays one speaker's spoken test with the fader turned to it; a second tap on the same speaker
     * stops it, a tap on another switches. When the file ends the fader goes back by itself.
     */
    void toggleSpeaker(Speaker speaker) {
        boolean wasActive = activeSpeaker == speaker;
        stopSpeaker();
        if (wasActive) {
            giveBackIfIdle();
            return;
        }
        if (!take()) return;
        MediaPlayer mp = new MediaPlayer();
        try (AssetFileDescriptor afd = app.getResources().openRawResourceFd(speaker.rawRes)) {
            mp.setAudioAttributes(mediaAttributes());
            mp.setAudioSessionId(session());
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            mp.prepare();
        } catch (Throwable t) {
            Log.w(TAG, "speaker test " + speaker + " could not be prepared: " + t);
            mp.release();
            giveBackIfIdle();
            return;
        }
        mp.setOnCompletionListener(done -> {
            if (activeSpeaker == speaker) {
                stopSpeaker();
                giveBackIfIdle();
                notifyChanged();
            }
        });
        speakerPlayer = mp;
        activeSpeaker = speaker;
        sendFader(speaker.faderFr, speaker.faderLr);
        mp.start();
    }

    private void stopSpeaker() {
        if (speakerPlayer != null) {
            try { speakerPlayer.stop(); } catch (Throwable ignored) {}
            speakerPlayer.release();
            speakerPlayer = null;
        }
        if (activeSpeaker != null) {
            activeSpeaker = null;
            sendFader(-1, -1);
        }
    }

    /** EQ at -12 on every band, the doors' filters at 250 Hz, their boost at 0 - only the sub plays. */
    void setOnlySub(boolean on) {
        if (on == onlySub) return;
        onlySub = on;
        Intent intent = new Intent(ACTION_ONLY_SUB).setPackage(app.getPackageName());
        intent.putExtra("enabled", on);
        app.sendBroadcast(intent);
        if (!on) giveBackIfIdle();
    }

    private void sendFader(int fr, int lr) {
        Intent intent = new Intent(ACTION_FADER).setPackage(app.getPackageName());
        intent.putExtra("fr", fr);
        intent.putExtra("lr", lr);
        app.sendBroadcast(intent);
    }

    // ---- the whole ----

    /**
     * Stops every signal and clears both overrides. Not on a tab switch - a test keeps playing while
     * the person works on other tabs, as in the author's version; on leaving the screen, on a call,
     * on losing the sound to another player.
     */
    void stopAll() {
        for (Stem stem : Stem.values()) {
            LoopTrack lt = stems.get(stem);
            if (lt != null) lt.track.pause();
        }
        stemEpochNanos = -1;
        main.removeCallbacks(stemSyncWatchdog);
        if (pinkNoise != null) pinkNoise.track.pause();
        cancelSineRebuild();
        releaseLoop(sine);
        sine = null;
        releaseLoop(sweep);
        sweep = null;
        stopSpeaker();
        setOnlySub(false);
        giveBackIfIdle();
        updateCurveWidth();
    }

    /** Frees the buffers as well - the stems hold a few megabytes each while paused. */
    void release() {
        stopAll();
        for (LoopTrack lt : stems.values()) releaseLoop(lt);
        stems.clear();
        releaseLoop(pinkNoise);
        pinkNoise = null;
    }

    // ---- the sound, taken and given back ----

    /** Takes the sound before anything plays; false when the platform refuses. */
    private boolean take() {
        if (CallState.isActive()) {
            Log.i(TAG, "a call is on - the test does not play");
            return false;
        }
        if (!focus.isRequested()) {
            String answer = focus.request(app, change -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS
                        || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                        || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    Log.i(TAG, "the sound went to another player - stopping the test");
                    stopAll();
                    notifyChanged();
                }
            });
            Log.i(TAG, "audio focus for the test: " + answer);
            if ("REFUSED".equals(answer)) {
                focus.abandon(app);
                return false;
            }
        }
        if (!sessionAnnounced) {
            announceSession(true);
            AudioSpectrumEngine.getInstance().resolveForOwnPlayback();
        }
        return true;
    }

    /** The spectrum reads narrow while a tone plays - see AudioSpectrumEngine.setCurveNarrow. */
    private void updateCurveWidth() {
        AudioSpectrumEngine.getInstance().setCurveNarrow(isTonePlaying());
    }

    private void giveBackIfIdle() {
        if (anythingPlays()) return;
        announceSession(false);
        focus.abandon(app);
    }

    private void notifyChanged() {
        if (listener != null) listener.onAudioCheckChanged();
    }

    /** One session for every signal, so one Visualizer sees them all (the author's design). */
    private int session() {
        if (sessionId <= 0) {
            AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
            sessionId = am != null ? am.generateAudioSessionId() : AudioManager.AUDIO_SESSION_ID_GENERATE;
        }
        return sessionId;
    }

    /** Tells the spectrum where the test plays - the broadcast any well-behaved player sends. */
    private void announceSession(boolean open) {
        if (open == sessionAnnounced) return;
        sessionAnnounced = open;
        Intent intent = new Intent(open ? AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION
                : AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).setPackage(app.getPackageName());
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session());
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, app.getPackageName());
        intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        app.sendBroadcast(intent);
    }

    // ---- buffers ----

    /** A static AudioTrack holding a whole loop, and the frame count its sync and loops need. */
    private static final class LoopTrack {
        final AudioTrack track;
        final int frames;
        final int sampleRate;

        LoopTrack(AudioTrack track, int frames, int sampleRate) {
            this.track = track;
            this.frames = frames;
            this.sampleRate = sampleRate;
        }
    }

    private static boolean isPlaying(LoopTrack lt) {
        return lt != null && lt.track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    private static void releaseLoop(LoopTrack lt) {
        if (lt == null) return;
        try { lt.track.pause(); } catch (Throwable ignored) {}
        lt.track.release();
    }

    private static AudioAttributes mediaAttributes() {
        return new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
    }

    /** MODE_STATIC with loop points over the whole buffer: the only gapless loop AudioTrack has. */
    private LoopTrack staticLoop(byte[] pcm, int sampleRate, int channels) {
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(mediaAttributes())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channels == 1 ? AudioFormat.CHANNEL_OUT_MONO
                                : AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(pcm.length)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setSessionId(session())
                .build();
        track.write(pcm, 0, pcm.length);
        int frames = pcm.length / (2 * channels);
        track.setLoopPoints(0, frames, -1);
        return new LoopTrack(track, frames, sampleRate);
    }

    /**
     * A stem from its WAV resource, walking the RIFF chunks rather than assuming a 44-byte header (a
     * DAW writes LIST and fact chunks too) - the author's parser. 16-bit PCM is what the stems are.
     */
    private LoopTrack loadLoop(int rawRes) {
        try (InputStream in = app.getResources().openRawResource(rawRes)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
            byte[] b = buf.toByteArray();
            int sampleRate = 44100, channels = 2;
            byte[] data = null;
            int pos = 12; // "RIFF", size, "WAVE"
            while (pos + 8 <= b.length) {
                String id = new String(b, pos, 4, StandardCharsets.US_ASCII);
                int size = (b[pos + 4] & 0xFF) | ((b[pos + 5] & 0xFF) << 8)
                        | ((b[pos + 6] & 0xFF) << 16) | ((b[pos + 7] & 0xFF) << 24);
                int start = pos + 8;
                if ("fmt ".equals(id)) {
                    channels = (b[start + 2] & 0xFF) | ((b[start + 3] & 0xFF) << 8);
                    sampleRate = (b[start + 4] & 0xFF) | ((b[start + 5] & 0xFF) << 8)
                            | ((b[start + 6] & 0xFF) << 16) | ((b[start + 7] & 0xFF) << 24);
                } else if ("data".equals(id)) {
                    data = new byte[Math.min(size, b.length - start)];
                    System.arraycopy(b, start, data, 0, data.length);
                    break;
                }
                pos = start + size + (size % 2);
            }
            if (data == null) throw new IOException("no data chunk");
            return staticLoop(data, sampleRate, channels);
        } catch (Throwable t) {
            Log.e(TAG, "could not load stem " + rawRes, t);
            return null;
        }
    }

    /** A whole number of cycles, as close to a second as that allows: value and slope meet at the seam. */
    private LoopTrack buildSine(int hz) {
        int cycles = Math.max(1, hz);
        int frames = Math.round(cycles * GEN_RATE / (float) hz);
        byte[] pcm = new byte[frames * 2];
        double amplitude = 0.3 * Short.MAX_VALUE;
        for (int i = 0; i < frames; i++) {
            putSample(pcm, i, amplitude * Math.sin(2 * Math.PI * hz * i / GEN_RATE));
        }
        return staticLoop(pcm, GEN_RATE, 1);
    }

    /**
     * The author's round-trip exponential chirp: up, then down, each leg's phase the integral of its
     * own exponentially changing frequency, the down leg continuing the up leg's phase - so the
     * waveform is continuous at the top and the loop joins low to low. With {@code normalize} every
     * instant is scaled by the threshold-of-hearing gain (attenuation only, never a boost).
     */
    private LoopTrack buildSweep(float startHz, float endHz, float durationSec, boolean normalize) {
        int half = Math.max(1, Math.round(durationSec * GEN_RATE / 2f));
        byte[] pcm = new byte[half * 2 * 2];
        double amplitude = 0.3 * Short.MAX_VALUE;
        float halfDuration = half / (float) GEN_RATE;
        boolean constant = Math.abs(endHz - startHz) < 0.01f;
        double kUp = constant ? 0 : Math.log(endHz / (double) startHz) / halfDuration;
        double kDown = constant ? 0 : Math.log(startHz / (double) endHz) / halfDuration;
        double tqMax = normalize ? HearingThreshold.maxDb(Math.min(startHz, endHz), Math.max(startHz, endHz)) : 0;
        double phaseAtTurn = 0;
        for (int i = 0; i < half; i++) {
            double t = i / (double) GEN_RATE;
            double f = constant ? startHz : startHz * Math.exp(kUp * t);
            double phase = constant ? 2 * Math.PI * startHz * t
                    : 2 * Math.PI * startHz / kUp * (Math.exp(kUp * t) - 1);
            phaseAtTurn = phase;
            double amp = normalize ? amplitude * HearingThreshold.gain(f, tqMax) : amplitude;
            putSample(pcm, i, amp * Math.sin(phase));
        }
        for (int i = 0; i < half; i++) {
            double t = i / (double) GEN_RATE;
            double f = constant ? endHz : endHz * Math.exp(kDown * t);
            double leg = constant ? 2 * Math.PI * endHz * t
                    : 2 * Math.PI * endHz / kDown * (Math.exp(kDown * t) - 1);
            double amp = normalize ? amplitude * HearingThreshold.gain(f, tqMax) : amplitude;
            putSample(pcm, half + i, amp * Math.sin(phaseAtTurn + leg));
        }
        return staticLoop(pcm, GEN_RATE, 1);
    }

    /** Paul Kellet's 3-pole pink filter on white noise, five seconds - the author's generator. */
    private LoopTrack buildPinkNoise() {
        int frames = GEN_RATE * 5;
        byte[] pcm = new byte[frames * 2];
        Random rnd = new Random();
        float b0 = 0, b1 = 0, b2 = 0;
        for (int i = 0; i < frames; i++) {
            float white = rnd.nextFloat() * 2f - 1f;
            b0 = 0.99765f * b0 + white * 0.0990460f;
            b1 = 0.96300f * b1 + white * 0.2965164f;
            b2 = 0.57000f * b2 + white * 1.0526913f;
            float pink = Math.max(-1f, Math.min(1f, (b0 + b1 + b2 + white * 0.1848f) * 0.1f));
            putSample(pcm, i, pink * Short.MAX_VALUE);
        }
        return staticLoop(pcm, GEN_RATE, 1);
    }

    private static void putSample(byte[] pcm, int frame, double value) {
        short s = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
        pcm[frame * 2] = (byte) (s & 0xFF);
        pcm[frame * 2 + 1] = (byte) ((s >> 8) & 0xFF);
    }

    /**
     * The ISO 226:2003 threshold of hearing (0 phon), the author's table and interpolation - the data
     * a perceptual sweep is built from. The standard stops at 12.5 kHz; 16 and 20 kHz are his bounded
     * extension.
     */
    static final class HearingThreshold {
        private static final float[] HZ = {
                20, 25, 31.5f, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000,
                1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000
        };
        private static final float[] DB = {
                74.3f, 65.0f, 56.3f, 48.4f, 41.7f, 35.5f, 29.8f, 25.1f, 20.7f, 16.8f, 13.8f, 11.2f,
                8.9f, 7.2f, 6.0f, 5.0f, 4.4f, 4.2f, 3.7f, 2.6f, 1.0f, -1.2f, -3.6f, -3.9f, -1.1f, 6.6f,
                15.3f, 16.4f, 11.6f, 20.0f, 40.0f
        };

        private HearingThreshold() {}

        /** dB SPL to just hear {@code freqHz}, interpolated on a log-frequency axis, clamped to the table. */
        static double db(double freqHz) {
            float f = (float) Math.max(HZ[0], Math.min(HZ[HZ.length - 1], freqHz));
            int i = 0;
            while (i < HZ.length - 2 && HZ[i + 1] < f) i++;
            double t = (Math.log(f) - Math.log(HZ[i])) / (Math.log(HZ[i + 1]) - Math.log(HZ[i]));
            return DB[i] + t * (DB[i + 1] - DB[i]);
        }

        /** The least sensitive point within a range, sampled at 200 log points - the dip is inside. */
        static double maxDb(float loHz, float hiHz) {
            double max = Double.NEGATIVE_INFINITY;
            double logLo = Math.log(loHz), logHi = Math.log(Math.max(hiHz, loHz * 1.0001));
            for (int i = 0; i <= 200; i++) max = Math.max(max, db(Math.exp(logLo + (logHi - logLo) * i / 200)));
            return max;
        }

        /** Linear gain (at most 1) that cancels the ear's threshold shape against the range's maximum. */
        static double gain(double freqHz, double tqMaxDb) {
            return Math.pow(10.0, (db(freqHz) - tqMaxDb) / 20.0);
        }
    }
}
