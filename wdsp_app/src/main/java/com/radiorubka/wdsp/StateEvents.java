package com.radiorubka.wdsp;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * "Something the sound state is made of may have changed" - one bell for the whole app, so the
 * service's check, the analyser's watchdog and the screensaver look when it rings instead of each
 * on its own clock (owner, 12.10.2026: «переводь візуалізатор і заставку на події теж»).
 *
 * <p>Rung by: a watched property changing value (McuService, {@link PropertyWatch}), a media
 * session playing or stopping ({@link NowPlaying#setOnChange}), playback starting or stopping in
 * AudioFlinger and the captured signal appearing or running out (AudioSpectrumEngine).
 *
 * <p>A listener is called on the ringer's thread, often a capture or binder thread, so it must
 * only post to its own and return. It may be rung for changes that are not its business.
 */
final class StateEvents {

    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private StateEvents() {
    }

    static void subscribe(Runnable listener) {
        listeners.addIfAbsent(listener);
    }

    static void unsubscribe(Runnable listener) {
        listeners.remove(listener);
    }

    static void fire() {
        for (Runnable l : listeners) l.run();
    }
}
