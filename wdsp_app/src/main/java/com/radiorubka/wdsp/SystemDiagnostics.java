package com.radiorubka.wdsp;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Everything about this head unit's audio, in one block of text, plus a running log of the moments
 * it changes.
 *
 * <h2>Why this exists</h2>
 *
 * Three reports arrived from units we do not own and cannot borrow:
 *
 * <ul>
 *   <li>the cabin measurement button did nothing at all - no error, no dialog - until the owner
 *       happened to take a phone call over Bluetooth, after which it worked;</li>
 *   <li>radio volume falls back to a factory default after every Bluetooth call, on some units but
 *       not on others with the same firmware;</li>
 *   <li>with the BitPerfect audio module installed, navigation prompts are too quiet and the radio
 *       is too loud - and with the factory audio policy neither happens.</li>
 * </ul>
 *
 * None of these can be reproduced here, and asking an owner to read numbers off their screen is
 * how a fortnight gets spent on the wrong hypothesis. So the app collects the numbers itself.
 *
 * <h2>What it collects, and why those things</h2>
 *
 * The platform does not use Android's volume model. It keeps one {@code VolumeState} per source -
 * media, radio, Bluetooth call, AUX - and each one stores its value in a plain system property.
 * Two details in there explain the second complaint on their own, so the report reads them
 * directly:
 *
 * <ul>
 *   <li>the live values live in {@code sys.*.vol}, which are <b>not</b> persistent properties: an
 *       unset one silently reads back as the default from {@code persist.sys.*_volume}. A volume
 *       that "resets to the factory value" is a property that stopped existing;</li>
 *   <li>only the state whose type matches {@code sys.current.vol.type} is pushed to the MCU. If a
 *       call leaves that property saying {@code btcall_type}, later volume changes are written to
 *       a property and never reach the amplifier.</li>
 * </ul>
 *
 * Navigation has its own mixer - {@code persist.sys.navi_remix} and a ratio - and the platform
 * parks the ducked music volume in {@code sys.qf.lower.volume.by.navi} to restore it afterwards.
 * That is the third complaint's most likely home, so those are read too.
 *
 * For the first complaint the report opens the microphone on every audio source in turn and says
 * which ones work, because "the button does nothing" is what a failed {@code AudioRecord} looks
 * like from the passenger seat.
 *
 * <h2>The timeline</h2>
 *
 * A snapshot cannot show a regression that happens during a phone call. So the service arms a
 * small recorder that writes one line whenever the platform announces a call, a navigation prompt,
 * a Bluetooth state change or a volume change - each line carrying the volume model as it stood at
 * that instant. The tester makes one call, and the before and after are side by side in the file.
 *
 * <p>The recorder holds a few hundred lines in memory and nothing else; it does not poll, and it
 * writes no file of its own until a report is asked for.
 */
public final class SystemDiagnostics {

    private static final String TAG = "wDSP_Diagnostics";

    /** Hidden in AudioManager, so it is spelled out; the platform still broadcasts it. */
    private static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";

    /** Enough to hold a Bluetooth call and the minutes either side of it. */
    private static final int TIMELINE_LINES = 400;

    private SystemDiagnostics() {
    }

    // -------------------------------------------------------------------------------------------
    // the property tables
    //
    // Names taken from the decompiled platform framework (android.qf.os.VolumeState,
    // android.qf.os.VolumeManager, android.qf.os.QFAudioService), not from guesswork - the comment
    // on each line is what the framework does with it.
    // -------------------------------------------------------------------------------------------

    private static final String[][] VOLUME_LIVE = {
            {"sys.current.vol.type", "which source the knob currently drives; only this one reaches the MCU"},
            {"sys.media.vol", "media volume, live"},
            {"sys.radio.vol", "radio volume, live"},
            {"sys.call.vol", "Bluetooth call volume, live"},
            {"sys.aux.vol", "AUX volume, live"},
            {"sys.mute.state", "the single global mute flag"},
            {"sys.media.mute.state", "per-source mute"},
            {"sys.radio.mute.state", "per-source mute"},
            {"sys.call.mute.state", "per-source mute"},
            {"sys.aux.mute.state", "per-source mute"},
    };

    private static final String[][] VOLUME_DEFAULTS = {
            {"persist.sys.main_volume", "fallback when sys.media.vol is unset (framework default 12)"},
            {"persist.sys.radio_volume", "fallback when sys.radio.vol is unset (framework default 12)"},
            {"persist.sys.phone_volume", "fallback when sys.call.vol is unset (framework default 12)"},
            {"persist.sys.aux_volume", "fallback when sys.aux.vol is unset (framework default 12)"},
            {"persist.sys.navi_volume", "volume restored to after a wake-up"},
    };

    private static final String[][] NAVIGATION = {
            {"persist.sys.navi_remix", "mix navigation over music instead of pausing it (default true)"},
            {"persist.sys.navi_remix_ratio", "0..100, sent to the MCU as ratio*32/100 (default 60)"},
            {"sys.qf.lower.volume.by.navi", "where the ducked music volume is parked for restoring"},
            {"sys.qf.navi_state", "navigation is speaking"},
            {"persist.sys.navi_boot", "navigation starts with the unit"},
    };

    private static final String[][] AUDIO_PATH = {
            {"sys.qf.audio.status", "platform audio state"},
            {"sys.qf.codec.status", "codec state"},
            {"sys.qf.sound.channel", "which source the MCU has connected to the amplifier"},
            {"sys.qf.last_audio_src", "the app the platform thinks owns the sound"},
            {"persist.sys.qf.last_audio_src", "the same, remembered across reboots"},
            {"persist.sys.qf.dac_vol_restore_en", "re-push the DAC volume after a source change"},
            {"persist.sys.qf.dac_vol_restore_time", "how long it waits before doing so"},
            {"persist.sys.fm.max.volume", "ceiling the platform puts on radio volume"},
            {"sys.qf.eq.type", "the factory equaliser preset the platform thinks is active"},
            {"persist.sys.channel_gain", "per-channel gain trim"},
            {"persist.sys.power_amp_ctrl", "amplifier control mode"},
            {"persist.sys.audio_boot", "startup sound"},
            {"ro.qf.audio.solution", "audio hardware variant, if the build declares one"},
    };

    private static final String[][] BLUETOOTH_AND_CALL = {
            {"sys.qf.call_state", "a call is in progress"},
            {"sys.qf.ismute_before_call", "what the mute flag was before the call, for restoring"},
            {"sys.qf.bt.state.isopen", "Bluetooth is on"},
            {"sys.qf.interbt.a2dp.connect", "the internal Bluetooth module has A2DP up"},
            {"persist.sys.double_bt", "two Bluetooth stacks; when set, hardware volume is rescaled to Android's"},
            {"persist.sys.is.ext.bt", "external Bluetooth module"},
            {"persist.sys.btmodel.choose", "which Bluetooth module the build expects"},
    };

    /** Every audio source worth trying, worst-behaved first. */
    private static final int[] MIC_SOURCES = {
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.DEFAULT,
    };

    private static final int[] STREAMS = {
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_DTMF,
            AudioManager.STREAM_ACCESSIBILITY,
    };

    // -------------------------------------------------------------------------------------------
    // the report
    // -------------------------------------------------------------------------------------------

    /**
     * The whole diagnostic, ready to be written to a file.
     *
     * <p>Deliberately independent of the cabin measurement: the unit that most needs to be
     * diagnosed is the one where the measurement will not start.
     *
     * @param withMicrophoneProbe opening the microphone takes about a second per source and needs
     *                            the recording permission; skipped when there is no permission
     */
    public static String report(Context context, boolean withMicrophoneProbe) {
        StringBuilder sb = new StringBuilder();
        sb.append("wDSP system diagnostic\n");
        sb.append("taken ").append(timestamp()).append('\n');
        sb.append("app ").append(appVersion(context)).append('\n');
        sb.append(HardwareProfile.describeBoard()).append('\n');
        sb.append(HardwareProfile.describe()).append('\n');
        sb.append(HardwareProfile.screenDescription(context)).append('\n');
        sb.append('\n');

        appendAudioHub(sb);
        appendProps(sb, "PLATFORM VOLUME - live values", VOLUME_LIVE);
        sb.append("  note: sys.*.vol are NOT persistent properties. An empty value below means the\n");
        sb.append("        platform is reading the persist.* default in the next block instead.\n\n");
        appendProps(sb, "PLATFORM VOLUME - defaults it falls back to", VOLUME_DEFAULTS);
        appendProps(sb, "NAVIGATION MIXING", NAVIGATION);
        appendProps(sb, "AUDIO PATH", AUDIO_PATH);
        appendProps(sb, "BLUETOOTH AND CALLS", BLUETOOTH_AND_CALL);

        appendVendorVolume(sb);
        appendAndroidVolume(sb, context);
        appendDevices(sb, context);
        appendActiveAudio(sb, context);

        appendNowPlaying(sb, context);

        if (withMicrophoneProbe) {
            sb.append(microphoneProbe());
        } else {
            sb.append("MICROPHONE\n  not probed - the app has no recording permission\n\n");
        }

        sb.append(timeline());
        return sb.toString();
    }

    /**
     * The second DSP, if there is one, and the state that decides what it does.
     *
     * <p>First block of the report on purpose: it splits the fleet in two. Units with an AKM hub
     * have the radio and the second Bluetooth module on its analogue inputs and Android on its
     * digital one, and the MCU re-pushes the hub's master volume every time it changes source.
     * Units without one leave the level alone. Almost every volume complaint we have had comes
     * from the first group, and nothing else in this report says which group a unit is in.
     */
    private static void appendAudioHub(StringBuilder sb) {
        String hub = HardwareProfile.audioHub();
        sb.append("SECOND DSP (audio hub)\n");
        sb.append(String.format(Locale.US, "  MCU code       = %s%n", HardwareProfile.mcuCode()));
        sb.append(String.format(Locale.US, "  hub            = %s  (from the MCU code, the way the platform decides)%n", hub));
        sb.append(String.format(Locale.US, "  /sys/ak7738    = %s%n", sysfsState("/sys/ak7738/pm_suspend")));
        sb.append(String.format(Locale.US, "  /sys/ak7604    = %s%n", sysfsState("/sys/ak7604/pm_suspend")));
        if (!"none".equals(hub)) {
            sb.append("  on this unit the radio is an ANALOGUE input of the hub and Android is the\n");
            sb.append("  digital one, so source changes re-push the hub's master volume\n");
        }
        sb.append('\n');
    }

    /**
     * Reads the hub's suspend flag, which is a plain world-readable sysfs file on the units that
     * have one - and simply missing on the units that do not, which is the answer either way.
     */
    private static String sysfsState(String path) {
        java.io.File file = new java.io.File(path);
        if (!file.exists()) return "not present";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "present, empty" : "present, pm_suspend=" + line.trim();
        } catch (Throwable t) {
            return "present but unreadable (" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * What the app can see about the music, and through which of the three doors.
     *
     * <p>Worth a block of its own because the answer differs per unit and per player: the built-in
     * player broadcasts, everything else needs notification access, and without either there is
     * only the package name. When somebody reports that the screensaver shows no track, this says
     * which of those is the case without a single question being asked.
     */
    private static void appendNowPlaying(StringBuilder sb, Context context) {
        NowPlaying np = NowPlaying.getInstance(context);
        sb.append("NOW PLAYING\n");
        sb.append(String.format(Locale.US, "  notification access = %b  (needed for anything but the built-in player)%n",
                np.canReadSessions()));
        sb.append(String.format(Locale.US, "  player      = %s%n", orUnset(np.playerLabel())));
        sb.append(String.format(Locale.US, "  playing     = %b%n", np.isPlaying()));
        sb.append(String.format(Locale.US, "  line        = %s%n", orUnset(np.line())));
        sb.append(String.format(Locale.US, "  cover art   = %s%n", np.art() == null ? "none" : "yes"));
        float p = np.progress();
        sb.append(String.format(Locale.US, "  progress    = %s%n",
                p < 0 ? "unknown" : String.format(Locale.US, "%.0f%%", p * 100f)));
        sb.append('\n');
    }

    private static void appendProps(StringBuilder sb, String title, String[][] table) {
        sb.append(title).append('\n');
        for (String[] row : table) {
            String value = HardwareProfile.systemProperty(row[0]);
            sb.append(String.format(Locale.US, "  %-34s = %-14s  %s%n",
                    row[0], value == null ? "(unset)" : value, row[1]));
        }
        sb.append('\n');
    }

    /**
     * The platform's own volume object, asked directly rather than through its properties.
     *
     * <p>Worth having both: if this disagrees with {@code sys.current.vol.type} above, the volume
     * manager in this process was initialised before the type last changed, which is itself the
     * bug in some of these reports.
     */
    private static void appendVendorVolume(StringBuilder sb) {
        sb.append("PLATFORM VOLUME - as the volume manager answers right now\n");
        sb.append(String.format(Locale.US, "  active type   = %s%n", VolumeHelper.getActivePlayerType()));
        sb.append(String.format(Locale.US, "  active value  = %d%n", VolumeHelper.getVolume()));
        sb.append(String.format(Locale.US, "  hardware mute = %b%n", VolumeHelper.isHardwareMuted()));
        sb.append('\n');
    }

    private static void appendAndroidVolume(StringBuilder sb, Context context) {
        AudioManager am = audioManager(context);
        sb.append("ANDROID VOLUME AND MODE\n");
        if (am == null) {
            sb.append("  no AudioManager\n\n");
            return;
        }
        sb.append(String.format(Locale.US, "  mode=%s  ringer=%d  musicActive=%b  sco=%b  a2dp=%b  speakerphone=%b%n",
                modeName(am.getMode()), am.getRingerMode(), am.isMusicActive(),
                am.isBluetoothScoOn(), isBluetoothA2dpOn(am), am.isSpeakerphoneOn()));
        for (int stream : STREAMS) {
            int min = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) min = am.getStreamMinVolume(stream);
            sb.append(String.format(Locale.US, "  %-14s %2d / %2d (min %d)%s  routed to %s%n",
                    streamName(stream),
                    am.getStreamVolume(stream), am.getStreamMaxVolume(stream), min,
                    isStreamMute(am, stream) ? " MUTED" : "",
                    devicesForStream(stream)));
        }
        sb.append(String.format(Locale.US, "  output sample rate=%s frames per buffer=%s%n",
                orUnset(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)),
                orUnset(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER))));
        sb.append('\n');
    }

    /**
     * The devices the audio policy is offering.
     *
     * <p>This is the block that tells a modified audio policy from a factory one: BitPerfect adds
     * and removes outputs and changes the rates they accept, and a navigation prompt that is too
     * quiet is usually a prompt that has been routed somewhere the hardware volume does not reach.
     */
    private static void appendDevices(StringBuilder sb, Context context) {
        AudioManager am = audioManager(context);
        sb.append("AUDIO DEVICES\n");
        if (am == null) {
            sb.append("  no AudioManager\n\n");
            return;
        }
        for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_ALL)) {
            sb.append(String.format(Locale.US, "  %-6s id=%-3d type=%-3d %-22s rates=%s channels=%s%n",
                    device.isSink() ? "out" : "in",
                    device.getId(), device.getType(), device.getProductName(),
                    join(device.getSampleRates()), join(device.getChannelCounts())));
        }
        sb.append('\n');
    }

    /** Who is playing and who is recording, which is how an assistant holding the microphone shows. */
    private static void appendActiveAudio(StringBuilder sb, Context context) {
        AudioManager am = audioManager(context);
        sb.append("ACTIVE AUDIO\n");
        if (am == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            sb.append("  not available on this Android version\n\n");
            return;
        }
        try {
            List<AudioRecordingConfiguration> recording = am.getActiveRecordingConfigurations();
            if (recording.isEmpty()) {
                sb.append("  nothing is recording\n");
            } else {
                for (AudioRecordingConfiguration c : recording) {
                    sb.append(String.format(Locale.US, "  recording: source=%s format=%dHz%n",
                            sourceName(c.getClientAudioSource()),
                            c.getClientFormat() != null ? c.getClientFormat().getSampleRate() : 0));
                }
            }
        } catch (Throwable t) {
            sb.append("  could not list recorders: ").append(t).append('\n');
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                sb.append(String.format(Locale.US, "  %d stream(s) playing%n",
                        am.getActivePlaybackConfigurations().size()));
            } catch (Throwable ignored) {
            }
        }
        sb.append('\n');
    }

    // -------------------------------------------------------------------------------------------
    // the microphone
    // -------------------------------------------------------------------------------------------

    /**
     * Opens every audio source in turn and says what happened.
     *
     * <p>Three outcomes matter and they are not the same problem. A source that will not open is
     * usually held by the voice assistant's hotword listener; one that opens but delivers silence
     * is a routing or amplifier problem; one that opens and delivers signal is fine. The cabin
     * measurement uses {@code VOICE_RECOGNITION}, so that one is tried first.
     */
    public static String microphoneProbe() {
        StringBuilder sb = new StringBuilder("MICROPHONE\n");
        sb.append("  the cabin measurement uses VOICE_RECOGNITION; the rest are for comparison\n");
        for (int source : MIC_SOURCES) {
            sb.append(String.format(Locale.US, "  %-20s %s%n", sourceName(source), probeSource(source)));
        }
        sb.append('\n');
        return sb.toString();
    }

    @SuppressLint("MissingPermission")
    private static String probeSource(int source) {
        final int rate = 48000;
        AudioRecord record = null;
        try {
            int minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBytes <= 0) return "the platform reports no usable buffer size";
            record = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBytes * 4);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                return "WILL NOT OPEN - most often the voice assistant is holding it";
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                return "opens but will not start";
            }
            short[] buffer = new short[minBytes / 2];
            int peak = 0;
            long deadline = System.currentTimeMillis() + 400;
            while (System.currentTimeMillis() < deadline) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) break;
                for (int i = 0; i < read; i++) {
                    int value = Math.abs(buffer[i]);
                    if (value > peak) peak = value;
                }
            }
            int effective = record.getSampleRate();
            String rateNote = effective == rate
                    ? "48000 Hz"
                    : effective + " Hz (asked for " + rate + ")";
            return peak == 0
                    ? "opens at " + rateNote + " but delivers pure silence"
                    : "works, " + rateNote + ", peak " + peak;
        } catch (Throwable t) {
            return "threw " + t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (record != null) {
                try {
                    record.stop();
                } catch (Throwable ignored) {
                }
                try {
                    record.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // the timeline
    // -------------------------------------------------------------------------------------------

    private static final Deque<String> LINES = new ArrayDeque<>();
    private static BroadcastReceiver receiver;

    /**
     * Starts listening for the moments the audio path changes.
     *
     * <p>Called from the service, once. Everything here is a broadcast the platform already sends;
     * nothing is polled, so an armed recorder that never sees an event costs nothing.
     */
    public static synchronized void arm(Context context) {
        if (receiver != null) return;
        final Context app = context.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                String detail = "";
                if (VOLUME_CHANGED_ACTION.equals(action)) {
                    detail = String.format(Locale.US, " stream=%s %d->%d",
                            streamName(intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)),
                            intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1),
                            intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1));
                } else if (AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(action)) {
                    detail = " state=" + intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
                } else if ("com.qf.action.VOLUME_CHANGED".equals(action)) {
                    detail = " pushed=" + intent.getIntExtra("EXTRA_VOLUME_VALUE", -1);
                }
                record(app, shortAction(action) + detail);
            }
        };
        IntentFilter filter = new IntentFilter();
        // The platform's own announcements. These bracket a call and a navigation prompt exactly,
        // which the Android ones do not: on this unit the call is on the phone, not on the head.
        filter.addAction("com.qf.action.PHONE_CALL_START");
        filter.addAction("com.qf.action.PHONE_CALL_END");
        filter.addAction("com.qf.action.NAVI_SOUND_START");
        filter.addAction("com.qf.action.NAVI_SOUND_STOP");
        filter.addAction("com.qf.action.BT_STATE");
        filter.addAction("com.qf.action.MUTE_EQ");
        filter.addAction("com.qf.action.can.vol");
        // Sent by the MCU service from RPC_SetChannel, so it marks the exact moment the source
        // changed and carries the volume that was pushed to the hardware with it.
        filter.addAction("com.qf.action.VOLUME_CHANGED");
        filter.addAction("com.qf.action.ACC_ON");
        filter.addAction("com.qf.action.ACC_OFF");
        // Android's, for the units where the platform stays quiet.
        filter.addAction(VOLUME_CHANGED_ACTION);
        filter.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
        filter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        filter.addAction("android.bluetooth.headset.profile.action.AUDIO_STATE_CHANGED");
        filter.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
            record(app, "recorder armed");
        } catch (Throwable t) {
            Log.w(TAG, "could not arm the diagnostic recorder", t);
            receiver = null;
        }
    }

    /**
     * One line: the time, what happened, and the whole volume model as it stood at that instant.
     *
     * <p>The volume model is repeated on every line rather than only when it changes, because the
     * question these reports have to answer is what a value was <i>before</i> the event that broke
     * it, and a diff against the previous line gives that for free.
     */
    public static synchronized void record(Context context, String event) {
        String line = String.format(Locale.US, "%s  %-28s  type=%-11s media=%-4s radio=%-4s call=%-4s mute=%-5s androidMusic=%d",
                timestamp(), event,
                orUnset(HardwareProfile.systemProperty("sys.current.vol.type")),
                orUnset(HardwareProfile.systemProperty("sys.media.vol")),
                orUnset(HardwareProfile.systemProperty("sys.radio.vol")),
                orUnset(HardwareProfile.systemProperty("sys.call.vol")),
                orUnset(HardwareProfile.systemProperty("sys.mute.state")),
                androidMusicVolume(context));
        LINES.addLast(line);
        while (LINES.size() > TIMELINE_LINES) LINES.removeFirst();
        Log.i(TAG, line);
    }

    public static synchronized String timeline() {
        StringBuilder sb = new StringBuilder("TIMELINE\n");
        if (LINES.isEmpty()) {
            sb.append("  nothing recorded yet\n");
        } else {
            sb.append("  an empty radio= or media= means the property does not exist, and the\n");
            sb.append("  platform is using the persist.* default instead of the value it had\n\n");
            for (String line : LINES) sb.append("  ").append(line).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    // -------------------------------------------------------------------------------------------
    // small helpers
    // -------------------------------------------------------------------------------------------

    private static AudioManager audioManager(Context context) {
        return (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    private static int androidMusicVolume(Context context) {
        AudioManager am = audioManager(context);
        return am == null ? -1 : am.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    @SuppressWarnings("deprecation")
    private static boolean isBluetoothA2dpOn(AudioManager am) {
        try {
            return am.isBluetoothA2dpOn();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isStreamMute(AudioManager am, int stream) {
        try {
            return am.isStreamMute(stream);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Which devices a stream is routed to, as the audio policy sees it.
     *
     * <p>Hidden API, so it is reflected and allowed to fail: it is the single most useful line for
     * the BitPerfect reports, and worth asking for even when it is not going to answer.
     */
    private static String devicesForStream(int stream) {
        try {
            @SuppressLint("PrivateApi")
            Class<?> audioSystem = Class.forName("android.media.AudioSystem");
            Object mask = audioSystem.getMethod("getDevicesForStream", int.class).invoke(null, stream);
            if (mask instanceof Integer) return "0x" + Integer.toHexString((Integer) mask);
        } catch (Throwable ignored) {
        }
        return "(hidden)";
    }

    private static String appVersion(Context context) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String orUnset(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String join(int[] values) {
        if (values == null || values.length == 0) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static String shortAction(String action) {
        int dot = action.lastIndexOf('.');
        return dot < 0 ? action : action.substring(dot + 1);
    }

    private static String modeName(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "NORMAL";
            case AudioManager.MODE_RINGTONE: return "RINGTONE";
            case AudioManager.MODE_IN_CALL: return "IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION: return "IN_COMMUNICATION";
            default: return "MODE_" + mode;
        }
    }

    private static String streamName(int stream) {
        switch (stream) {
            case AudioManager.STREAM_MUSIC: return "MUSIC";
            case AudioManager.STREAM_VOICE_CALL: return "VOICE_CALL";
            case AudioManager.STREAM_RING: return "RING";
            case AudioManager.STREAM_ALARM: return "ALARM";
            case AudioManager.STREAM_NOTIFICATION: return "NOTIFICATION";
            case AudioManager.STREAM_SYSTEM: return "SYSTEM";
            case AudioManager.STREAM_DTMF: return "DTMF";
            case AudioManager.STREAM_ACCESSIBILITY: return "ACCESSIBILITY";
            default: return "STREAM_" + stream;
        }
    }

    private static String sourceName(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.UNPROCESSED: return "UNPROCESSED";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.DEFAULT: return "DEFAULT";
            default: return "SOURCE_" + source;
        }
    }
}
