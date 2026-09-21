package com.radiorubka.wdsp;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the audio policy on this particular unit actually decided.
 *
 * <h2>Why a report and not an answer</h2>
 *
 * The same firmware behaves differently across the fleet, and the two complaints we cannot
 * reproduce - navigation too quiet, radio too loud - differ in severity between units that look
 * identical from here. The owner of one reports "a little quiet"; the owner of a unit with the
 * other tuner reports "much worse". Nothing in an app can guess which of those a stranger has.
 *
 * <p>So this collects the few numbers that decide it, from the places the platform keeps them:
 *
 * <ul>
 *   <li>which legacy stream each <em>usage</em> is actually mapped to - asked of the platform
 *       rather than assumed, because the mapping is in framework code and differs by version;
 *   <li>the volume curve each stream is on, and what it comes to in dB at the volume the owner
 *       is running - which is the whole of the navigation complaint, expressed as one number;
 *   <li>what the policy accepts as a format, which separates a factory unit from one carrying
 *       a modified 24/48 policy without asking the owner what they installed;
 *   <li>the sound card's own device list, where the hardware taps live.
 * </ul>
 *
 * <p>None of it needs root. The XML under {@code /vendor/etc} is world-readable, and the stream
 * mapping is a public getter on a track nobody hears.
 */
public final class AudioPolicySnapshot {

    private static final String TAG = "wDSP_AudioPolicy";

    private static final String VOLUMES = "/vendor/etc/audio_policy_volumes.xml";
    private static final String TABLES = "/vendor/etc/default_volume_tables.xml";
    private static final String PRIMARY = "/vendor/etc/primary_audio_policy_configuration.xml";
    private static final String PCM_LIST = "/proc/asound/pcm";

    /** The speaker is the only category that matters in a car; the rest are there for contrast. */
    private static final String SPEAKER = "DEVICE_CATEGORY_SPEAKER";

    private AudioPolicySnapshot() {
    }

    public static void append(StringBuilder sb, Context context) {
        appendNavigationApps(sb, context);
        appendUsageMapping(sb, context);
        appendVolumeCurves(sb, context);
        appendPolicyFormats(sb);
        appendSoundCard(sb);
    }

    /**
     * The policy files themselves, verbatim, at the end of the report.
     *
     * <h2>Why the summary above is not enough</h2>
     *
     * Everything above is this app's reading of these files, and a reading cannot be diffed. When a
     * unit behaves unlike every other one we have seen, the question is always "what is different
     * about its policy", and answering it means putting two files side by side - not two summaries.
     * One report already showed a mix of outputs belonging to no configuration we knew, and there
     * was nothing to compare it against.
     *
     * <p>They are world-readable, they are not large, and they are the same for every unit with the
     * same firmware, so nothing here is personal. Put last, after everything a human would read.
     */
    public static void appendRawPolicies(StringBuilder sb) {
        sb.append("RAW POLICY FILES - the summary above is this app's reading of these\n\n");
        for (String path : new String[]{
                // The four that decide how loud everything is and where it goes.
                "/vendor/etc/audio_policy_configuration.xml",
                "/vendor/etc/primary_audio_policy_configuration.xml",
                "/vendor/etc/audio_policy_volumes.xml",
                "/vendor/etc/default_volume_tables.xml",
                // The submix module, because whether playback capture can work at all turns on it.
                "/vendor/etc/r_submix_audio_policy_configuration.xml",
                // The sibling configuration some builds ship instead of the primary one above. Reading both
                // is how we learn which a stranger's firmware actually uses.
                "/vendor/etc/primary_audio_policy_configuration_smart_pa.xml",
                // Microphones, mute controls and the PCM map - small, and they differ across the
                // fleet in ways nothing else reports.
                "/vendor/etc/audio_config.xml",
                "/vendor/etc/audio_pcm.xml",
                // The lists the platform checks before it will duck the music or treat an app as a
                // voice assistant. A navigator missing from the first one gets nothing at all.
                NAVI_LIST,
                "/system/config/VoiceApp.ini",
                "/system/config/SkipAppWhenAudioStart.ini",
        }) {
            File file = new File(path);
            sb.append("----- ").append(path);
            if (!file.canRead()) {
                sb.append("  NOT READABLE\n\n");
                continue;
            }
            sb.append("  ").append(file.length()).append(" bytes\n");
            String text = readAll(path, RAW_LIMIT);
            if (text == null) {
                sb.append("  could not be read\n\n");
                continue;
            }
            sb.append(text);
            if (!text.endsWith("\n")) sb.append('\n');
            if (file.length() > RAW_LIMIT) {
                sb.append("  ... truncated at ").append(RAW_LIMIT).append(" bytes\n");
            }
            sb.append('\n');
        }
    }

    /**
     * Per file. Generous enough for every policy seen so far and small enough that a strange
     * firmware cannot turn a report somebody has to send over Telegram into a megabyte.
     */
    private static final int RAW_LIMIT = 48 * 1024;

    // ------------------------------------------------------------------------------------------
    // the navigators this owner has, against the list the platform will listen to
    // ------------------------------------------------------------------------------------------

    private static final String NAVI_LIST = "/system/config/NaviApp.ini";

    /**
     * Every navigator installed, and whether the platform has ever heard of it.
     *
     * <h2>Why this is the first thing in the report</h2>
     *
     * The platform will not duck the music for a spoken prompt unless the app that made it is
     * named in {@code /system/config/NaviApp.ini}. An app that is missing gets nothing at all: no
     * ducking, no navigation volume, no mixing slider. It plays at full scale straight into the
     * music and its owner reports that it "went silent" - which is exactly what happened here with
     * AutoMapa, measured over five prompts with the music never moving a decibel.
     *
     * <p>The second column of that file decides whether the platform's navigation volume applies.
     * iGO ships with it off, so the slider does nothing for it however far it is turned.
     *
     * <p>So the two facts worth having from a stranger's unit are: what they run, and what the
     * firmware knows. Installed apps are found by asking who handles a {@code geo:} link, which is
     * how a navigator declares itself, rather than by matching a list of names we made up.
     */
    private static void appendNavigationApps(StringBuilder sb, Context context) {
        sb.append("NAVIGATORS - installed, against /system/config/NaviApp.ini\n");

        Map<String, String> listed = readNavigationList();
        if (listed == null) {
            sb.append("  ").append(NAVI_LIST).append(" not present on this firmware\n");
        } else {
            sb.append(String.format(Locale.US, "  the firmware list has %d entries%n", listed.size()));
        }

        List<String> installed = new ArrayList<>();
        try {
            android.content.Intent geo = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=x"));
            for (android.content.pm.ResolveInfo r
                    : context.getPackageManager().queryIntentActivities(geo, 0)) {
                if (r.activityInfo != null && !installed.contains(r.activityInfo.packageName)) {
                    installed.add(r.activityInfo.packageName);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not ask who handles geo links", t);
        }

        if (installed.isEmpty()) {
            sb.append("  no app claims geo: links\n\n");
            return;
        }
        for (String pkg : installed) {
            String verdict;
            if (listed == null) {
                verdict = "cannot tell - no list";
            } else {
                String allow = match(listed, pkg);
                if (allow == null) {
                    verdict = "NOT LISTED - platform will not duck music for it";
                } else if ("false".equalsIgnoreCase(allow)) {
                    verdict = "listed, but navigation volume does NOT apply (column 2 = false)";
                } else {
                    verdict = "listed, navigation volume applies";
                }
            }
            sb.append(String.format(Locale.US, "  %-44s %s%n", pkg, verdict));
        }
        sb.append('\n');
    }

    /** {@code package -> the second column}, with the wildcard entries kept as written. */
    private static Map<String, String> readNavigationList() {
        String text = readAll(NAVI_LIST);
        if (text == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            out.put(parts[0], parts.length > 1 ? parts[1] : "");
        }
        return out;
    }

    /** The file allows entries like {@code com.autonavi.*}, so a plain lookup is not enough. */
    private static String match(Map<String, String> listed, String pkg) {
        String exact = listed.get(pkg);
        if (exact != null) return exact;
        for (Map.Entry<String, String> e : listed.entrySet()) {
            String key = e.getKey();
            if (key.endsWith("*") && pkg.startsWith(key.substring(0, key.length() - 1))) {
                return e.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // which stream a usage lands in
    // ------------------------------------------------------------------------------------------

    private static final int[][] USAGES = {
            {AudioAttributes.USAGE_MEDIA, 0},
            {AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, 0},
            {AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, 0},
            {AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, 0},
            {AudioAttributes.USAGE_ASSISTANT, 0},
            {AudioAttributes.USAGE_NOTIFICATION, 0},
            {AudioAttributes.USAGE_ALARM, 0},
            {AudioAttributes.USAGE_VOICE_COMMUNICATION, 0},
    };

    /**
     * Asks the platform where each usage goes, by building a track and reading it back.
     *
     * <h2>Why ask rather than look it up</h2>
     *
     * The usage-to-stream mapping lives in framework code, and the answer is the hinge of the
     * navigation complaint: if guidance is mapped to the music stream then it shares the music
     * volume curve exactly, and no amount of editing the spoken-prompt curve will move it. That is
     * worth knowing per unit rather than per assumption - a vendor is free to have changed it.
     *
     * <p>The track is never started and carries no data, so nothing is heard.
     */
    private static void appendUsageMapping(StringBuilder sb, Context context) {
        sb.append("USAGE TO STREAM - as this platform maps it\n");
        // One session for all eight, not one each.
        //
        // A track built without a session id takes a fresh one, and the analyser's last-resort way
        // of finding the music is to sweep session ids downwards from the newest. Eight throwaway
        // sessions per report would push that sweep further out and give it more dead candidates to
        // walk - and on factory policies, where session 0 is a dead thread and the sweep is the only
        // route left, that sweep is what the whole analyser rests on. Taking one id instead of eight
        // keeps a diagnostic from disturbing the thing it is diagnosing.
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int session = AudioManager.AUDIO_SESSION_ID_GENERATE;
        if (am != null) {
            try {
                session = am.generateAudioSessionId();
            } catch (Throwable ignored) {
            }
        }

        for (int[] row : USAGES) {
            int usage = row[0];
            String stream;
            AudioTrack track = null;
            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(usage).build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(48000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build())
                        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(48000,
                                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT))
                        .setSessionId(session)
                        .build();
                stream = streamName(track.getStreamType());
            } catch (Throwable t) {
                stream = "could not ask (" + t.getClass().getSimpleName() + ")";
            } finally {
                if (track != null) {
                    try {
                        track.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
            sb.append(String.format(Locale.US, "  %-38s -> %s%n", usageName(usage), stream));
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------------------------------
    // the curves, and what they come to right now
    // ------------------------------------------------------------------------------------------

    private static final String[][] CURVE_STREAMS = {
            {"AUDIO_STREAM_MUSIC", "3"},
            {"AUDIO_STREAM_TTS", "9"},
            {"AUDIO_STREAM_NOTIFICATION", "5"},
            {"AUDIO_STREAM_SYSTEM", "1"},
            {"AUDIO_STREAM_ALARM", "4"},
            {"AUDIO_STREAM_RING", "2"},
            {"AUDIO_STREAM_VOICE_CALL", "0"},
    };

    /**
     * The gain each stream is getting on the speaker, in dB, at the volume the owner has set.
     *
     * <p>This is the navigation complaint reduced to arithmetic. A factory unit attenuates music
     * and leaves spoken prompts at full scale, so the prompt sits well above the music. A policy
     * that puts music at full scale for the sake of unity gain removes that headroom, because full
     * scale is the ceiling and nothing can be placed above it. Printing both numbers side by side
     * says which of the two a stranger's unit is in without a word of explanation.
     */
    private static void appendVolumeCurves(StringBuilder sb, Context context) {
        sb.append("VOLUME CURVES ON THE SPEAKER - and what they come to at the current setting\n");
        Map<String, List<float[]>> tables = readReferenceCurves();
        Map<String, List<float[]>> streams = readStreamCurves(tables);
        if (streams.isEmpty()) {
            sb.append("  could not read ").append(VOLUMES).append("\n\n");
            return;
        }
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        Float musicDb = null;
        Float ttsDb = null;
        for (String[] row : CURVE_STREAMS) {
            String name = row[0];
            int legacy = Integer.parseInt(row[1]);
            List<float[]> curve = streams.get(name);
            if (curve == null) {
                sb.append(String.format(Locale.US, "  %-26s not listed%n", shortName(name)));
                continue;
            }
            String at = "";
            if (am != null) {
                try {
                    int index = am.getStreamVolume(legacy);
                    int max = am.getStreamMaxVolume(legacy);
                    int min = android.os.Build.VERSION.SDK_INT >= 28
                            ? am.getStreamMinVolume(legacy) : 0;
                    float db = volumeDb(curve, index, min, max);
                    at = String.format(Locale.US, "  index %2d/%2d -> %6.1f dB", index, max, db);
                    if ("AUDIO_STREAM_MUSIC".equals(name)) musicDb = db;
                    if ("AUDIO_STREAM_TTS".equals(name)) ttsDb = db;
                } catch (Throwable t) {
                    at = "  (no index)";
                }
            }
            sb.append(String.format(Locale.US, "  %-26s %-24s%s%n",
                    shortName(name), describe(curve), at));
        }
        if (musicDb != null && ttsDb != null) {
            sb.append(String.format(Locale.US,
                    "  spoken prompt sits %+.1f dB relative to music%n", ttsDb - musicDb));
        }
        sb.append('\n');
    }

    /** AOSP interpolates the curve over the index expressed as a percentage of its own range. */
    private static float volumeDb(List<float[]> curve, int index, int min, int max) {
        float span = Math.max(1, max - min);
        float percent = 100f * (index - min) / span;
        float[] before = curve.get(0);
        for (float[] point : curve) {
            if (point[0] >= percent) {
                if (point[0] == before[0]) return point[1] / 100f;
                float t = (percent - before[0]) / (point[0] - before[0]);
                return (before[1] + t * (point[1] - before[1])) / 100f;
            }
            before = point;
        }
        return before[1] / 100f;
    }

    private static String describe(List<float[]> curve) {
        boolean flat = true;
        for (float[] p : curve) {
            if (p[1] != 0f) {
                flat = false;
                break;
            }
        }
        if (flat) return "full scale (0 dB always)";
        float[] first = curve.get(0);
        return String.format(Locale.US, "%.0f..0 dB", first[1] / 100f);
    }

    /** {@code name -> points}, each point {@code {index percent, millibel}}. */
    private static Map<String, List<float[]>> readReferenceCurves() {
        Map<String, List<float[]>> out = new LinkedHashMap<>();
        parse(TABLES, new Handler() {
            String current;

            @Override
            public void start(XmlPullParser p, String tag) {
                if ("reference".equals(tag)) {
                    current = p.getAttributeValue(null, "name");
                    if (current != null) out.put(current, new ArrayList<>());
                }
            }

            @Override
            public void text(String tag, String text) {
                if ("point".equals(tag) && current != null) {
                    float[] point = point(text);
                    if (point != null) out.get(current).add(point);
                }
            }
        });
        return out;
    }

    private static Map<String, List<float[]>> readStreamCurves(Map<String, List<float[]>> refs) {
        Map<String, List<float[]>> out = new LinkedHashMap<>();
        parse(VOLUMES, new Handler() {
            String stream;
            List<float[]> inline;

            @Override
            public void start(XmlPullParser p, String tag) {
                if (!"volume".equals(tag)) return;
                stream = null;
                inline = null;
                if (!SPEAKER.equals(p.getAttributeValue(null, "deviceCategory"))) return;
                stream = p.getAttributeValue(null, "stream");
                if (stream == null) return;
                String ref = p.getAttributeValue(null, "ref");
                if (ref != null) {
                    List<float[]> curve = refs.get(ref);
                    // A reference we could not read is still worth naming: "silent" and
                    // "full scale" are the two that explain most complaints, and the name alone
                    // carries that even when default_volume_tables.xml did not open.
                    if (curve != null) out.put(stream, curve);
                    else out.put(stream, single(ref.contains("SILENT") ? -9600 : 0));
                    stream = null;
                } else {
                    inline = new ArrayList<>();
                }
            }

            @Override
            public void text(String tag, String text) {
                if ("point".equals(tag) && inline != null) {
                    float[] point = point(text);
                    if (point != null) inline.add(point);
                }
            }

            @Override
            public void end(String tag) {
                if ("volume".equals(tag) && stream != null && inline != null && !inline.isEmpty()) {
                    out.put(stream, inline);
                }
            }
        });
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // what the policy accepts, and what the card offers
    // ------------------------------------------------------------------------------------------

    /**
     * The formats each output is declared to take.
     *
     * <p>A factory unit declares 16 bit at 44.1 kHz; a unit carrying a modified policy declares
     * 8_24 at 48 kHz. So this one block tells us which of the two a stranger is running without
     * asking them what they installed - and people do not always remember.
     */
    private static void appendPolicyFormats(StringBuilder sb) {
        sb.append("POLICY OUTPUTS - what the primary module declares it accepts\n");
        final boolean[] any = {false};
        parse(PRIMARY, new Handler() {
            String port;
            boolean output;

            @Override
            public void start(XmlPullParser p, String tag) {
                if ("mixPort".equals(tag)) {
                    port = p.getAttributeValue(null, "name");
                    output = "source".equals(p.getAttributeValue(null, "role"));
                    String flags = p.getAttributeValue(null, "flags");
                    if (output) {
                        any[0] = true;
                        sb.append(String.format(Locale.US, "  %-18s %s%n",
                                port, flags == null ? "" : flags));
                    }
                } else if ("profile".equals(tag) && output) {
                    sb.append(String.format(Locale.US, "      %-24s %s Hz%n",
                            String.valueOf(p.getAttributeValue(null, "format"))
                                    .replace("AUDIO_FORMAT_", ""),
                            p.getAttributeValue(null, "samplingRates")));
                }
            }
        });
        if (!any[0]) sb.append("  could not read ").append(PRIMARY).append('\n');
        sb.append('\n');
    }

    /**
     * The sound card's own devices.
     *
     * <p>Here because the hardware taps are in this list and nowhere else: a {@code DUMP} capture
     * device and a {@code LOOP} pair exist on this chip, and whether a given unit has them decides
     * what is possible on it at all.
     */
    private static void appendSoundCard(StringBuilder sb) {
        sb.append("SOUND CARD DEVICES\n");
        String pcm = readAll(PCM_LIST);
        if (pcm == null) {
            sb.append("  could not read ").append(PCM_LIST).append("\n\n");
            return;
        }
        for (String line : pcm.split("\n")) {
            if (!line.trim().isEmpty()) sb.append("  ").append(line.trim()).append('\n');
        }
        sb.append('\n');
    }

    // ------------------------------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------------------------------

    private abstract static class Handler {
        void start(XmlPullParser p, String tag) {
        }

        void text(String tag, String text) {
        }

        void end(String tag) {
        }
    }

    private static void parse(String path, Handler handler) {
        File file = new File(path);
        if (!file.canRead()) return;
        try (InputStream in = new FileInputStream(file)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);
            String tag = null;
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    tag = p.getName();
                    handler.start(p, tag);
                } else if (e == XmlPullParser.TEXT && tag != null) {
                    String text = p.getText();
                    if (text != null && !text.trim().isEmpty()) handler.text(tag, text.trim());
                } else if (e == XmlPullParser.END_TAG) {
                    handler.end(p.getName());
                    tag = null;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not parse " + path, t);
        }
    }

    private static float[] point(String text) {
        int comma = text.indexOf(',');
        if (comma <= 0) return null;
        try {
            return new float[]{
                    Float.parseFloat(text.substring(0, comma).trim()),
                    Float.parseFloat(text.substring(comma + 1).trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<float[]> single(float millibel) {
        List<float[]> out = new ArrayList<>();
        out.add(new float[]{0f, millibel});
        out.add(new float[]{100f, millibel});
        return out;
    }

    private static String readAll(String path) {
        return readAll(path, 16384);
    }

    private static String readAll(String path, int limit) {
        File file = new File(path);
        if (!file.canRead()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[limit];
            int total = 0;
            while (total < limit) {
                int read = in.read(buf, total, limit - total);
                if (read <= 0) break;
                total += read;
            }
            return total <= 0 ? "" : new String(buf, 0, total, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String shortName(String stream) {
        return stream.replace("AUDIO_STREAM_", "");
    }

    private static String streamName(int stream) {
        switch (stream) {
            case AudioManager.STREAM_VOICE_CALL: return "VOICE_CALL";
            case AudioManager.STREAM_SYSTEM: return "SYSTEM";
            case AudioManager.STREAM_RING: return "RING";
            case AudioManager.STREAM_MUSIC: return "MUSIC";
            case AudioManager.STREAM_ALARM: return "ALARM";
            case AudioManager.STREAM_NOTIFICATION: return "NOTIFICATION";
            case AudioManager.STREAM_DTMF: return "DTMF";
            case AudioManager.STREAM_ACCESSIBILITY: return "ACCESSIBILITY";
            case 9: return "TTS";
            default: return "stream " + stream;
        }
    }

    private static String usageName(int usage) {
        switch (usage) {
            case AudioAttributes.USAGE_MEDIA: return "MEDIA";
            case AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE:
                return "ASSISTANCE_NAVIGATION_GUIDANCE";
            case AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY:
                return "ASSISTANCE_ACCESSIBILITY";
            case AudioAttributes.USAGE_ASSISTANCE_SONIFICATION:
                return "ASSISTANCE_SONIFICATION";
            case AudioAttributes.USAGE_ASSISTANT: return "ASSISTANT";
            case AudioAttributes.USAGE_NOTIFICATION: return "NOTIFICATION";
            case AudioAttributes.USAGE_ALARM: return "ALARM";
            case AudioAttributes.USAGE_VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            default: return "usage " + usage;
        }
    }
}
