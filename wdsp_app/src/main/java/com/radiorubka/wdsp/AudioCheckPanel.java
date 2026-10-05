package com.radiorubka.wdsp;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

/**
 * The Audio Check tab's controls (the author's 1.0), bound to {@link AudioCheck}, which owns the
 * sound. After every tap the buttons are set from what the engine actually does - a refused start
 * (a call, the focus refused) or a stop from outside (a speaker test that ended, another player)
 * shows at once instead of leaving a button lit over silence.
 */
final class AudioCheckPanel implements AudioCheck.Listener {

    /** The author's keys and store: test-signal settings, global, not per preset. */
    private static final String PREF_SWEEP_START = "audiocheck_sweep_start";
    private static final String PREF_SWEEP_END = "audiocheck_sweep_end";
    private static final String PREF_SWEEP_DURATION = "audiocheck_sweep_duration";
    private static final String PREF_SWEEP_NORMALIZE = "audiocheck_sweep_normalize";

    private final MainActivity activity;
    private final AudioCheck engine;
    private final SharedPreferences prefs;

    private final MaterialButton bass, vocal, drums, melody, pink, sine, sweep, normalize;
    private final MaterialButton fl, fr, rl, rr, onlySub;
    private final MaterialButton[] steps;
    private final Slider sineFreq;
    private final TextView sineFreqVal;
    private final EditText sweepStart, sweepEnd, sweepDuration;
    private boolean updating;

    AudioCheckPanel(MainActivity activity) {
        this.activity = activity;
        this.engine = AudioCheck.get(activity);
        this.prefs = activity.getSharedPreferences(PresetsDatabaseValidator.PREFS_NAME, Context.MODE_PRIVATE);

        bass = activity.findViewById(R.id.switch_audiocheck_bass);
        vocal = activity.findViewById(R.id.switch_audiocheck_vocal);
        drums = activity.findViewById(R.id.switch_audiocheck_drums);
        melody = activity.findViewById(R.id.switch_audiocheck_melody);
        pink = activity.findViewById(R.id.switch_audiocheck_pink_noise);
        sine = activity.findViewById(R.id.switch_audiocheck_sine);
        sweep = activity.findViewById(R.id.btn_audiocheck_sweep_toggle);
        normalize = activity.findViewById(R.id.switch_audiocheck_sweep_normalize);
        fl = activity.findViewById(R.id.btn_audiocheck_fl);
        fr = activity.findViewById(R.id.btn_audiocheck_fr);
        rl = activity.findViewById(R.id.btn_audiocheck_rl);
        rr = activity.findViewById(R.id.btn_audiocheck_rr);
        onlySub = activity.findViewById(R.id.switch_audiocheck_only_sub);
        steps = new MaterialButton[]{
                activity.findViewById(R.id.btn_audiocheck_sine_minus10),
                activity.findViewById(R.id.btn_audiocheck_sine_minus1),
                activity.findViewById(R.id.btn_audiocheck_sine_plus1),
                activity.findViewById(R.id.btn_audiocheck_sine_plus10)};
        sineFreq = activity.findViewById(R.id.seek_audiocheck_sine_freq);
        sineFreqVal = activity.findViewById(R.id.tv_audiocheck_sine_freq_val);
        sweepStart = activity.findViewById(R.id.edit_audiocheck_sweep_start);
        sweepEnd = activity.findViewById(R.id.edit_audiocheck_sweep_end);
        sweepDuration = activity.findViewById(R.id.edit_audiocheck_sweep_duration);

        bind(bass, () -> engine.setStem(AudioCheck.Stem.BASS, bass.isChecked()));
        bind(vocal, () -> engine.setStem(AudioCheck.Stem.VOCAL, vocal.isChecked()));
        bind(drums, () -> engine.setStem(AudioCheck.Stem.DRUMS, drums.isChecked()));
        bind(melody, () -> engine.setStem(AudioCheck.Stem.MELODY, melody.isChecked()));
        bind(pink, () -> engine.setPinkNoise(pink.isChecked()));
        bind(sine, () -> engine.setSine(sine.isChecked()));
        bind(sweep, () -> {
            if (sweep.isChecked()) {
                engine.startSweep(field(sweepStart, 100f), field(sweepEnd, 10000f),
                        field(sweepDuration, 10f), normalize.isChecked());
            } else {
                engine.stopSweep();
            }
        });
        bind(fl, () -> engine.toggleSpeaker(AudioCheck.Speaker.FL));
        bind(fr, () -> engine.toggleSpeaker(AudioCheck.Speaker.FR));
        bind(rl, () -> engine.toggleSpeaker(AudioCheck.Speaker.RL));
        bind(rr, () -> engine.toggleSpeaker(AudioCheck.Speaker.RR));
        bind(onlySub, () -> engine.setOnlySub(onlySub.isChecked()));

        normalize.setChecked(prefs.getBoolean(PREF_SWEEP_NORMALIZE, false));
        normalize.addOnCheckedChangeListener((bv, checked) -> {
            activity.updateToggleStyle(bv);
            if (updating) return;
            prefs.edit().putBoolean(PREF_SWEEP_NORMALIZE, checked).apply();
            // As the author: the gain curve cancels only the threshold's shape - it still needs
            // playing quietly enough to be near that threshold.
            if (checked) Toaster.show(activity, activity.getString(R.string.toast_sweep_hearing_threshold));
        });

        int[] deltas = {-10, -1, 1, 10};
        for (int i = 0; i < steps.length; i++) {
            final int delta = deltas[i];
            if (steps[i] == null) continue;
            steps[i].setOnClickListener(v -> sineFreq.setValue(freqToPos(engine.sineHz() + delta)));
        }
        sineFreq.setValue(freqToPos(engine.sineHz()));
        sineFreqVal.setText(activity.getString(R.string.unit_hz, String.valueOf(engine.sineHz())));
        sineFreq.addOnChangeListener((slider, value, fromUser) -> {
            int hz = Math.round(posToFreq(value));
            sineFreqVal.setText(activity.getString(R.string.unit_hz, String.valueOf(hz)));
            engine.setSineHz(hz);
        });

        persist(sweepStart, PREF_SWEEP_START);
        persist(sweepEnd, PREF_SWEEP_END);
        persist(sweepDuration, PREF_SWEEP_DURATION);

        engine.setListener(this);
        refresh();
    }

    /** Every button of the panel, for the activity's theme pass. */
    View[] buttons() {
        return new View[]{bass, vocal, drums, melody, pink, sine, sweep, normalize,
                fl, fr, rl, rr, onlySub, steps[0], steps[1], steps[2], steps[3]};
    }

    /** The sweep fields in the theme's colours - text and hint - for the activity's theme pass. */
    void tintFields(int textColor, int hintColor) {
        for (EditText e : new EditText[]{sweepStart, sweepEnd, sweepDuration}) {
            if (e == null) continue;
            e.setTextColor(textColor);
            e.setHintTextColor(hintColor);
        }
    }

    @Override
    public void onAudioCheckChanged() {
        refresh();
    }

    /** Sets every button from what the engine does now, and restyles them. */
    void refresh() {
        updating = true;
        set(bass, engine.isStemOn(AudioCheck.Stem.BASS));
        set(vocal, engine.isStemOn(AudioCheck.Stem.VOCAL));
        set(drums, engine.isStemOn(AudioCheck.Stem.DRUMS));
        set(melody, engine.isStemOn(AudioCheck.Stem.MELODY));
        set(pink, engine.isPinkNoiseOn());
        set(sine, engine.isSineOn());
        set(sweep, engine.isSweepOn());
        set(fl, engine.activeSpeaker() == AudioCheck.Speaker.FL);
        set(fr, engine.activeSpeaker() == AudioCheck.Speaker.FR);
        set(rl, engine.activeSpeaker() == AudioCheck.Speaker.RL);
        set(rr, engine.activeSpeaker() == AudioCheck.Speaker.RR);
        set(onlySub, engine.isOnlySub());
        updating = false;
    }

    private void set(MaterialButton b, boolean checked) {
        if (b == null) return;
        b.setChecked(checked);
        activity.updateToggleStyle(b);
    }

    /** A tap acts on the engine, then the panel shows what the engine really did. */
    private void bind(MaterialButton b, Runnable action) {
        if (b == null) return;
        b.setOnClickListener(v -> {
            if (updating) return;
            action.run();
            refresh();
        });
    }

    /** An empty or garbled field falls back to a default instead of refusing - the author's rule. */
    private static float field(EditText e, float fallback) {
        try {
            return Float.parseFloat(e.getText().toString().trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void persist(EditText e, String key) {
        if (e == null) return;
        String saved = prefs.getString(key, null);
        if (saved != null) e.setText(saved);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                prefs.edit().putString(key, s.toString()).apply();
            }
        });
    }

    /** The author's log slider: 0 = 20 Hz, 1 = 20 kHz, equal steps are equal ratios. */
    private static float posToFreq(float pos) {
        return AudioCheck.SINE_MIN_HZ
                * (float) Math.pow(AudioCheck.SINE_MAX_HZ / AudioCheck.SINE_MIN_HZ, pos);
    }

    private static float freqToPos(float hz) {
        float clamped = Math.max(AudioCheck.SINE_MIN_HZ, Math.min(AudioCheck.SINE_MAX_HZ, hz));
        return (float) (Math.log(clamped / AudioCheck.SINE_MIN_HZ)
                / Math.log(AudioCheck.SINE_MAX_HZ / AudioCheck.SINE_MIN_HZ));
    }
}
