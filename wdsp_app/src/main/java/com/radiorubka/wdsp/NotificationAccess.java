package com.radiorubka.wdsp;

import android.service.notification.NotificationListenerService;

/**
 * Declared so that media sessions can be read, and for nothing else.
 *
 * <h2>Why an app about sound needs this</h2>
 *
 * {@code MediaSessionManager.getActiveSessions} refuses to answer unless the caller names an
 * enabled notification listener. That is the platform's way of gating "what is the whole device
 * playing" behind a permission the owner grants by hand - the launcher's own widget can read it
 * without asking only because it is a system app.
 *
 * <p>So this service exists to be listed and enabled. It overrides nothing: no notification is
 * read, posted, dismissed or acted upon, and there is no code here that could. The screensaver
 * asks {@link NowPlaying} what is playing; the permission is the price of that question, and the
 * app works without it - just showing the player's name instead of the track.
 */
public class NotificationAccess extends NotificationListenerService {
}
