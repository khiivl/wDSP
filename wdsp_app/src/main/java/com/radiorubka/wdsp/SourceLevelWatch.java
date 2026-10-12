package com.radiorubka.wdsp;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Watches the level the platform stores for every source that is not playing, so a change made
 * somewhere else reaches wDSP's per-source memory instead of being overwritten by it.
 *
 * <h2>Why a watch and not a listener</h2>
 *
 * 🔬 QF_CarSettings ({@code VolumeSetFragment.onProgressChanged}) sets the call, radio and AUX
 * levels with {@code VolumeState.setVolumeVal}, which writes {@code sys.<source>.vol} and calls
 * {@code RPC_SetVolume} only when that source is the live one. So a level changed for a source
 * that is not playing raises no {@code com.qf.action.VOLUME_CHANGED}, and the config service
 * ({@code ConfigInfoManagerService.updateConfigItemInfo}) broadcasts nothing either. Android has no
 * listener for a system property. The stored levels are four property reads; the service's
 * one-second idle tick reads them.
 *
 * <p>📻 The bug this answers (owner, 12.10.2026): call volume set to 10 in the car settings between
 * calls came back as 13 on the next call - the figure wDSP remembered from the call before.
 *
 * <h2>Who changed it</h2>
 *
 * A change seen on an ordinary tick is somebody else's - the car settings, the radio, another
 * app - and the memory follows it. A change seen on the tick where the live source switched is
 * not: that is the platform's {@code VolumeManager.resetDefValIfNeed}, which drops every source
 * standing on one number to {@code persist.sys.main_volume} at that moment, and it is exactly
 * what the memory exists to undo. {@link #resync} learns those values without reporting them.
 */
final class SourceLevelWatch {

    static final String[] SOURCES = {"media_type", "radio_type", "btcall_type", "aux_type"};

    /** The stored level of each non-live source as last read. Absent = not read yet. */
    private final Map<String, Integer> seen = new HashMap<>();

    /**
     * Reads every source but the live one and returns those whose stored level changed since the
     * last read, with the new level. A source read for the first time reports nothing, nor does
     * one whose level cannot be read.
     */
    Map<String, Integer> poll(String liveType) {
        Map<String, Integer> changed = new LinkedHashMap<>();
        for (String type : SOURCES) {
            if (type.equals(liveType)) continue;
            int level = VolumeHelper.getVolumeForType(type);
            if (level < 0) continue;
            Integer before = seen.put(type, level);
            if (before != null && before != level) changed.put(type, level);
        }
        // The live source is the person's knob and the poll's own business; once it stops being
        // live, it is learnt afresh rather than compared with a figure from before it played.
        seen.remove(liveType);
        return changed;
    }

    /** Learns every non-live level as it stands, reporting nothing. For the tick of a switch. */
    void resync(String liveType) {
        seen.remove(liveType);
        for (String type : SOURCES) {
            if (type.equals(liveType)) continue;
            int level = VolumeHelper.getVolumeForType(type);
            if (level >= 0) seen.put(type, level);
        }
    }

    /** Records a level this service itself wrote for a non-live source, so it is not reported back. */
    void note(String type, int level) {
        if (level >= 0) seen.put(type, level);
    }
}
