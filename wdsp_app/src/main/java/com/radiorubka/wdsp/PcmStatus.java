package com.radiorubka.wdsp;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which sound-card stream the kernel currently has open, read straight from {@code /proc/asound}.
 *
 * <h2>Why bother, when Android already has opinions about this</h2>
 *
 * Because Android's opinions are about sessions and packages, and both of those have been caught
 * lying on this platform. The kernel is not making a judgement: a substream is open or it is not,
 * and it names the device it belongs to.
 *
 * <p>The useful answer is <b>which</b> device. Media travels on the fast device with the factory
 * policies and on the primary one with the module installed, and that single fact decides whether
 * a session-0 effect can hear anything at all - which is otherwise discovered by attaching one,
 * waiting, and finding silence.
 *
 * <p>📻 The files are {@code -r--r--r--}, so this needs no root and works on a stranger's unit.
 *
 * <h2>🪤 What it does not tell you, and both traps are real</h2>
 *
 * <ul>
 *   <li><b>Not who is playing.</b> {@code owner_pid} is the audio HAL service on this platform -
 *       measured, {@code /vendor/bin/hw/android.hardware.audio@2.0-service} - and never the
 *       application. It is the same pid whoever is playing.</li>
 *   <li><b>Open is not the same as sounding</b>, on its own. The radio app writes PCM silence to
 *       hold the player role, so a device reads {@code RUNNING} while what you hear is the
 *       analogue tuner.
 *       <p>That is not fatal, because the radio announces itself: {@code sys.qf.radio.status} and
 *       the MCU channel already say when the tuner has the amplifier. Open <em>and not radio</em>
 *       is a sound answer to "is anything really playing", and a cheaper one than waiting for an
 *       effect to be attached and listened to. See {@link NowPlaying#isRadioSource()}.</li>
 * </ul>
 */
final class PcmStatus {

    private static final String CARD = "/proc/asound/card0";

    /** Names as they appear in {@code /proc/asound/pcm}, so a report reads without a lookup. */
    private static final String[][] KNOWN = {
            {"0", "FE_ST_NORMAL_AP01"},
            {"1", "FE_ST_NORMAL_AP23"},
            {"3", "FE_ST_FAST"},
            {"5", "FE_ST_VOICE"},
            {"6", "FE_ST_VOIP"},
            {"7", "FE_ST_FM"},
            {"10", "FE_ST_LOOP"},
            {"12", "FE_ST_A2DP_PCM"},
            {"15", "FE_ST_FM_DSP"},
    };

    static final class Open {
        final String device;
        final String name;
        final String state;

        Open(String device, String name, String state) {
            this.device = device;
            this.name = name;
            this.state = state;
        }
    }

    private PcmStatus() {
    }

    /** Every playback substream that is not closed, in device order. */
    static List<Open> openPlayback() {
        List<Open> out = new ArrayList<>();
        File card = new File(CARD);
        File[] entries = card.listFiles();
        if (entries == null) return out;
        for (File entry : entries) {
            String n = entry.getName();
            // pcmNp - playback. The capture ones end in c and are somebody else's question.
            if (!n.startsWith("pcm") || !n.endsWith("p")) continue;
            String device = n.substring(3, n.length() - 1);
            String status = read(new File(entry, "sub0/status"));
            if (status == null) continue;
            String first = status.split("\n", 2)[0].trim();
            if (first.isEmpty() || "closed".equals(first)) continue;
            out.add(new Open(device, nameOf(device), first.replace("state:", "").trim()));
        }
        return out;
    }

    static String describeForReport() {
        StringBuilder sb = new StringBuilder();
        List<Open> open = openPlayback();
        if (open.isEmpty()) {
            sb.append("  no playback stream is open\n");
        } else {
            for (Open o : open) {
                sb.append(String.format(Locale.US, "  pcm%-3s %-18s %s%n", o.device, o.name, o.state));
            }
        }
        sb.append("  note: open is not audible by itself - the radio app writes silence to hold the\n");
        sb.append("        player role. Read this against the radio properties above: open and not\n");
        sb.append("        radio does mean something is really playing.\n");
        return sb.toString();
    }

    private static String nameOf(String device) {
        for (String[] row : KNOWN) {
            if (row[0].equals(device)) return row[1];
        }
        return "";
    }

    private static String read(File file) {
        if (!file.canRead()) return null;
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[2048];
            int read = in.read(buf);
            return read <= 0 ? null : new String(buf, 0, read, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
