# Argon model review, 14.10.2026 — digest, checked against the code

Chat (full answer): https://gemini.google.com/app/228743237ea71d21. Input: the owner's blind-calibration
PDF (text), `RESEARCH_RESULT_DYNAMIC_BASS.md`, `CABIN_MODEL.md`, `sweep.cpp`, `LoudnessCurve.java`, and
the open-space 20 Hz question. The owner pasted the answer from Gemini Desktop (the bridge hung on it).
Marks: ✅ confirmed in our code by Claude, 💭 Argon's inference not re-derived, ❌ rejected.

## Claims

1. **Mic estimate, open space.** `expected = refMid` (flat bottom) in open space breaks the physics of
   the radiator: on a bench no door speaker or sub plays 20–31.5 Hz at its midband level (sealed box
   −12 dB/oct below Fc ≈ 45–55 Hz, ported −24 dB/oct below tuning); in a car cabin gain mirrors it.
   High SNR at 20 Hz proves the ADC hears the speaker, not that the speaker is flat. → The 20 Hz rule
   is right **as a safeguard**; extend to 50 Hz when there is no sub.
2. 💭 **Shape constraint of the input HPF.** The input path is a smooth minimum-phase high-pass (RC 1st
   order + maybe a DSP 2nd order); its attenuation is monotonic. ✅ 14.1 dB at 80 Hz is recorded in
   `sweep.cpp`; a filter cannot take 14 dB at 80 Hz and only 16.6 at 20 Hz. Proposal: derive the
   ceiling of bands 0..2 from the measured deficit at 80/125 Hz (if D80 < 3 dB, at most a 72 Hz
   1st-order RC: 5.3 dB @50, 8.0 @31.5, 11.4 @20); anything above is speakers/cabin.
3. Bands 3..4 take the best channel; a mode antinode of +6 dB at one speaker under-estimates the deficit.
   Not acted on.
4. Long-sweep deconvolution makes the MEMS bottom measurable — the dynamic-bass report's "MEMS unusable
   below 1 kHz" is wrong; our SNR-ramp stands.
5. **Dual window**: long (341 ms) for bands 0..5, short (~43 ms) for 6..15 — `CABIN_MODEL.md` §12 plans it.
6. ✅ **Auto-EQ ignores band overlap.** `synthesizeAutoEq16` quantises each band alone (only
   `limitZigzag16`); `AudioConfig.prewarpEq` runs only while a loudness offset is non-zero
   (`LoudnessCurve`, `hasOffset`) — the same preset is pre-warped below the calibration point and raw
   above it. ❌ Argon's "three adjacent +4 dB read ≈ +7.5" is wrong: simulated with our own matrix
   14.10, they play 4.9 / 5.5 / 5.0. ✅ fixed 14.10: pre-warp on every send (`LoudnessCurve.eqDriveDb`).
7. ✅ The "+3 dB" boost cap rounds to +4 (`std::round(1.5) = 2` → index 8).
8. Do not boost a narrow notch (≥ 6 dB below both neighbours) above 315 Hz: Q 2.2 cannot fill it.
9. **Loudness.** `(t[cal] − t[vol]) / (t[25] − t[1])` is right (a fixed dB/dB gradient). ✅ a measured
   preset gets `_fm_cal = result.measureVolume` (≤ `MEASURE_VOLUME` 16), a level chosen for the
   microphone, not for listening; suggest the person's usual volume (≈ steps 20–22) as the start.
10. ✅ consistent with our model: the bass shelf reaches the doors only (`DspResponse.pathsAt`), so the
    sub misses the loudness boost carried by the shelf — the reason `_sub_comp` exists.
