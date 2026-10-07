package com.radiorubka.wdsp;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * Diagnostic-only helper: answers the single question the spectrum engine cannot answer by
 * itself - which audio session, if any, this app is actually allowed to tap on this head unit.
 *
 * Background: a Visualizer created on session 0 is an "output mix" effect, and
 * AudioPolicyManager::getOutputForEffect() hard-prefers the PRIMARY output. On stock QF
 * policies media does not go to the primary output (it lands on the "fast" mixPort), so the
 * session-0 effect processes an idle thread and yields silence. A session-targeted effect is
 * created on whichever thread the track lives on, which is the way out - but only if the
 * platform lets a non-privileged app attach to a session owned by another app.
 *
 * Nothing here runs unless explicitly triggered by a debug broadcast; the probe always runs on
 * its own thread so it can never stall McuService's audio worker.
 *
 * <pre>
 *   adb shell am broadcast -a com.radiorubka.wdsp.PROBE_SESSION --ei sid 25
 *   adb shell am broadcast -a com.radiorubka.wdsp.PROBE_SESSION --ei sid -1
 * </pre>
 */
public final class SessionProbe {
    private static final String TAG = "wDSP_SessionProbe";

    /** Waveform samples are unsigned 8-bit centred on 128; anything below this is silence. */
    private static final double SILENCE_RMS = 1.5;

    private SessionProbe() {
    }

    /** Probes a single session id and logs the result. Returns immediately. */
    public static void probeAsync(final int sessionId, final int durationMs) {
        new Thread(() -> {
            Result r = probe(sessionId, durationMs);
            Log.i(TAG, "PROBE " + r);
        }, "wDSP_SessionProbe").start();
    }

    /**
     * Walks candidate session ids downwards from the platform's current counter and logs every
     * one that carries audio. Session 0 is probed first so we can also tell whether the global
     * mix works on this particular head unit (it does when custom audio policies are installed).
     */
    public static void scanAsync(final Context ctx, final int maxCandidates, final int perProbeMs) {
        new Thread(() -> {
            int upper = 0;
            try {
                AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) upper = am.generateAudioSessionId();
            } catch (Throwable t) {
                Log.w(TAG, "generateAudioSessionId failed: " + t);
            }
            Log.i(TAG, "SCAN start, session counter=" + upper + ", maxCandidates=" + maxCandidates
                    + ", perProbeMs=" + perProbeMs);

            Log.i(TAG, "SCAN " + probe(0, perProbeMs));

            int probed = 0;
            for (int sid = upper - 1; sid > 0 && probed < maxCandidates; sid--, probed++) {
                Result r = probe(sid, perProbeMs);
                if (r.attached && r.rms > SILENCE_RMS) {
                    Log.i(TAG, "SCAN HIT " + r);
                } else if (!r.attached) {
                    Log.d(TAG, "SCAN " + r);
                }
            }
            Log.i(TAG, "SCAN done, probed " + probed + " candidates");
        }, "wDSP_SessionScan").start();
    }

    /** Attaches a Visualizer to one session, measures it, releases it. Never throws. */
    public static Result probe(int sessionId, int durationMs) {
        Result result = new Result(sessionId);
        Visualizer v = null;
        boolean weEnabledIt = false;
        try {
            v = new Visualizer(sessionId);
            result.attached = true;

            // The effect on this session may already be enabled - by our own capture, or by
            // another DSP app. Capture size can only be set while it is disabled, so in that case
            // take whatever size is already configured rather than throwing.
            if (!v.getEnabled()) {
                int[] range = Visualizer.getCaptureSizeRange();
                v.setCaptureSize(range[0]);
                v.setEnabled(true);
                weEnabledIt = true;
            }
            result.enabled = v.getEnabled();
            result.hasControl = hasControl(v);

            byte[] waveform = new byte[v.getCaptureSize()];
            long deadline = System.currentTimeMillis() + Math.max(50, durationMs);
            double sumSquares = 0;
            long samples = 0;

            while (System.currentTimeMillis() < deadline) {
                if (v.getWaveForm(waveform) == Visualizer.SUCCESS) {
                    for (byte b : waveform) {
                        int centred = (b & 0xFF) - 128;
                        sumSquares += (double) centred * centred;
                        samples++;
                        int magnitude = Math.abs(centred);
                        if (magnitude > result.peak) result.peak = magnitude;
                    }
                    result.captures++;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (samples > 0) result.rms = Math.sqrt(sumSquares / samples);
        } catch (Throwable t) {
            result.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (v != null) {
                // Leave the session exactly as we found it. Disabling an effect we did not enable
                // would silence another DSP app sitting on the same session; leaving one enabled
                // that we did enable breaks the next probe, which cannot set its capture size.
                if (weEnabledIt) {
                    try {
                        v.setEnabled(false);
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    v.release();
                } catch (Throwable ignored) {
                }
            }
        }
        return result;
    }

    /** Another DSP app may already own the effect on this session; then we get data but no control. */
    private static boolean hasControl(Visualizer v) {
        try {
            java.lang.reflect.Method m = v.getClass().getMethod("hasControl");
            Object res = m.invoke(v);
            if (res instanceof Boolean) return (Boolean) res;
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** Outcome of a single probe, formatted for logcat. */
    public static class Result {
        public final int sessionId;
        public boolean attached;
        public boolean enabled;
        public boolean hasControl = true;
        public int captures;
        public int peak;
        public double rms;
        public String error;

        Result(int sessionId) {
            this.sessionId = sessionId;
        }

        public boolean hasSignal() {
            return attached && rms > SILENCE_RMS;
        }

        @Override
        public String toString() {
            if (error != null) {
                return "session=" + sessionId + " attached=" + attached + " FAILED " + error;
            }
            return String.format(java.util.Locale.US,
                    "session=%d attached=%b enabled=%b control=%b captures=%d peak=%d rms=%.2f %s",
                    sessionId, attached, enabled, hasControl, captures, peak, rms,
                    hasSignal() ? "<<< SIGNAL" : "silent");
        }
    }
}
