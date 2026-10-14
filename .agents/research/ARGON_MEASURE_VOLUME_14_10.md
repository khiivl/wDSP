# Argon: the measurement volume and the loudness anchor, 14.10.2026 — digest

Chat: https://gemini.google.com/app/32890fb409d8df41 (new chat; the review chat 228743237ea71d21 hung
and the owner closed it — **never address it again**). Input: `measureVolumeFor` + `probeLevel` excerpts,
`VolumeSteps.java`, the volume-steps facts, the model-review digest.

## Findings

1. **Gain staging.** `probeLevel` moves only the digital amplitude (0.02..0.5) and never the MCU step;
   at a high step with a sensitive chain it drops to the floor (−34 dBFS into the DAC) and the analogue
   path is turned up instead — DAC/BU32107 noise raised 15–25 dB. Keep the digital level high, choose
   the step.
2. What the probe cannot cover: amplifier clipping (a deaf mic makes the probe raise 0.5 at a high
   step), door woofers past Xmax without HPF (the pass runs `_bf_f/_bf_r = 0`), cabin rattles
   (> ~80–85 dB SPL on bass), and at a low step the cabin's LF noise. At the 0.02 floor the mic can
   still overload.
3. **Automatic step, ~4 s:** (a) silence 0.4 s — refuse when broadband > −42 dBFS or 63/80 Hz > −38
   dBFS ("switch the engine and the fan off"); (b) a 0.35 s dual tone 80 Hz + 1 kHz at amplitude 0.10
   on step 8; (c) from the dB-per-step table pick the step v in 6..18 where a sweep at amplitude 0.22
   (window 0.12..0.35) would peak at −9 dBFS, raised while the 80 Hz SNR before deconvolution < 20 dB;
   (d) on v (via v−2 if v > 10) check H2+H3 of 80 Hz ≤ −18 dB, H2+H3 of 1 kHz ≤ −28 dB, and the 80 Hz
   gain from 8 to v within ±1.5 dB of the table; any failure → 1–2 steps down, the probe makes up the
   level. A transient during the tone reads as THD — fall back one or two steps, not to an error.
4. **Separate `_fm_cal` from the measurement volume** — the transfer function is level-independent,
   the equal-loudness contours are not. With `_fm_cal = 8` loudness never acts at the person's usual
   10..25. Anchor: the person's usual volume (the volume playing when the wizard starts, or a running
   median of media volume over trips, default 20); without history, the step +18 dB above the
   measurement step, clamped 16..24.
5. Wizard text (if not automatic): engine off, fan off, windows shut; volume a little below usual,
   "about 8–12, music like a calm conversation".

Not acted on yet: TODO «Ближнє», "Гучність виміру сама".
