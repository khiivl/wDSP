package com.radiorubka.wdsp;

import android.media.audiofx.Visualizer;

/**
 * Answers the question SessionResolver needs: which audio session, if any, this app is actually
 * allowed to tap on this head unit.
 *
 * Background: a Visualizer created on session 0 is an "output mix" effect, and
 * AudioPolicyManager::getOutputForEffect() hard-prefers the PRIMARY output. On stock QF
 * policies media does not go to the primary output (it lands on the "fast" mixPort), so the
 * session-0 effect processes an idle thread and yields silence. A session-targeted effect is
 * created on whichever thread the track lives on, which is the way out - but only if the
 * platform lets a non-privileged app attach to a session owned by another app.
 *
 * Each probe runs on its own thread so it can never stall the UI/Choreographer callback.
 */
public final class SessionProbe {
    /** Waveform samples are unsigned 8-bit centred on 128; anything below this is silence. */
    private static final double SILENCE_RMS = 1.5;

    private SessionProbe() {
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
                    }
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

    /** Outcome of a single probe. */
    public static class Result {
        public final int sessionId;
        public boolean attached;
        public boolean enabled;
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
                    "session=%d attached=%b enabled=%b rms=%.2f %s",
                    sessionId, attached, enabled, rms, hasSignal() ? "<<< SIGNAL" : "silent");
        }
    }
}
