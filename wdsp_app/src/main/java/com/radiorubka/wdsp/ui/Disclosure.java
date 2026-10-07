package com.radiorubka.wdsp.ui;

import android.content.SharedPreferences;
import android.view.View;
import android.widget.ImageView;

/**
 * A title that folds the controls under it - the author's 1.0 pattern (the loudness groups, GALA's
 * "Advanced"), one implementation for every such title instead of a click listener per section.
 *
 * <p>{@code panel} is whatever holds the controls - a ConstraintLayout {@code Group} works, so the
 * rows keep the card's shared label barrier. With a {@code prefKey} the open state outlives the app
 * (the author's keys, kept in the UI prefs, never in the presets file the export walks); without one
 * the section starts closed every time, as his GALA one does.
 *
 * <p>Whether the section is shown at all belongs to the caller ({@link #setShown}): the title goes
 * with it, the open state is kept for when it comes back.
 */
public final class Disclosure {
    private final View header;
    private final View panel;
    private final ImageView chevron;
    private final SharedPreferences prefs;
    private final String prefKey;
    private boolean open;
    private boolean shown = true;

    public Disclosure(View header, View panel, ImageView chevron, SharedPreferences prefs, String prefKey) {
        this.header = header;
        this.panel = panel;
        this.chevron = chevron;
        this.prefs = prefKey != null ? prefs : null;
        this.prefKey = prefKey;
        this.open = this.prefs != null && this.prefs.getBoolean(prefKey, false);
        header.setOnClickListener(v -> {
            open = !open;
            if (this.prefs != null) this.prefs.edit().putBoolean(this.prefKey, open).apply();
            apply();
        });
        apply();
    }

    public void setShown(boolean shown) {
        this.shown = shown;
        apply();
    }

    private void apply() {
        header.setVisibility(shown ? View.VISIBLE : View.GONE);
        panel.setVisibility(shown && open ? View.VISIBLE : View.GONE);
        // ic_chevron is "^": pointing up while open, down while there is something to unfold.
        if (chevron != null) chevron.setRotation(open ? 0f : 180f);
    }
}
