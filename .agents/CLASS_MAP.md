# Class map — what every class is and what it offers

> **Generated** by `tools/classmap.py` from the code's own doc comments — do not edit by hand;
> fix the comment in the class and run the tool again. The overview (layers, main flows, where to
> start) is in [FOR_THE_AUTHOR.md](FOR_THE_AUTHOR.md); the design in [ARCHITECTURE.md](ARCHITECTURE.md).

For each file: its size, the first sentence of its class comment, and its non-private methods with
the first sentence of theirs (at most 40 a file).

## Java (`java/com/radiorubka/wdsp`)

### `AudioCheck.java` — 666 lines
Audio Check - the author's 1.0 test-signal tab: the four stems of one song (bass, vocal, drums, melody) looped in sync and freely combined, pink noise, a sine generator, a logarithmic sweep that can be normalised to the 
- `isTonePlaying()` — A narrow-band tone is playing - the spectrum can stop smoothing it (the author's reason).
- `setStem(Stem stem, boolean on)` — Starts or stops one stem, joined in phase with the stems already playing: a stem switched on late starts where the others are in the shared loop, not at its beginning (the author's sync).
- `setSineHz(int hz)` — A new frequency, clamped to 20 Hz ..
- `startSweep(float startHz, float endHz, float durationSec, boolean norma)` — Starts the up-and-down logarithmic sweep with the author's limits; one cycle is durationSec.
- `toggleSpeaker(Speaker speaker)` — Plays one speaker's spoken test with the fader turned to it; a second tap on the same speaker stops it, a tap on another switches.
- `setOnlySub(boolean on)` — EQ at -12 on every band, the doors' filters at 250 Hz, their boost at 0 - only the sub plays.
- `stopAll()` — Stops every signal and clears both overrides.
- `release()` — Frees the buffers as well - the stems hold a few megabytes each while paused.
- `db(double freqHz)` — dB SPL to just hear freqHz, interpolated on a log-frequency axis, clamped to the table.
- `maxDb(float loHz, float hiHz)` — The least sensitive point within a range, sampled at 200 log points - the dip is inside.
- `gain(double freqHz, double tqMaxDb)` — Linear gain (at most 1) that cancels the ear's threshold shape against the range's maximum.

### `AudioCheckPanel.java` — 241 lines
The Audio Check tab's controls (the author's 1.0), bound to AudioCheck, which owns the sound.
- `buttons()` — Every button of the panel, for the activity's theme pass.
- `tintFields(int textColor, int hintColor)` — The sweep fields in the theme's colours - text and hint - for the activity's theme pass.
- `refresh()` — Sets every button from what the engine does now, and restyles them.

### `AudioConfig.java` — 375 lines
Shared audio configuration and constants for wDSP.
- `eqGainDb(float index)` — A gain index as decibels: 6 is flat, each step 2 dB.
- `bassBoostFreqHzForIdx(int bassBoostFreqIdx)` — BASS_BOOST_FREQS[0] ("off") or any index outside BASS_BOOST_FREQS_HZ falls back to the tuned default/most-optimal frequency, LOUDNESS_BASS_SHELF_FREQ_HZ.
- `isoRawTargetForFreqHz(float freqHz)` — Picks the ISO_RAW_TARGET_BY_FREQ row matching a shelf frequency in Hz, falling back to the 86Hz row (also the "off"/unrecognized default) when nothing matches closely enough.
- `prewarpEq(float[] targetDb)` — Jointly pre-warps a 16-band target curve (dB, neutral-relative) so the real composite response of 16 overlapping Q=2.2 bells hits targetDb[i] exactly at every band i's own center - same principle as ISO_RAW_TARGET_BY_FRE
- `compositeResponseDb(float[] gains, float freqHz)` — Same as compositeResponseDb(int[], float) but for continuously-animated (non-integer) gain values - see EqVisualizerView's animatedGains, which glides toward the real (integer) slider gains each frame instead of snapping
- `subFilterResponseDb(float freqHz, float cutoffHz, int order, float passbandGainD)` — BU32107 Sub LPF magnitude response (dB) at a given frequency - see SUB_FILTER_ORDER's declaration for the filter model this assumes.
- `frequencyAt(float t)` — Maps a continuous band position to Hz - see this method's original doc above for the in-range (0..NUM_BANDS-1) mapping, which is unchanged.
- `bandPositionOf(float freqHz)` — The inverse of #frequencyAt: the continuous band position of freqHz, extended past the first and last band with the same edge spacing.
- `bandValueAt(float[] perBand, float freqHz)` — A per-band value read at any frequency: linear in band position between the two nearest bands, the edge band's value past either end.

### `AudioPolicySnapshot.java` — 652 lines
What the audio policy on this particular unit actually decided.
- `appendRawPolicies(StringBuilder sb)` — The policy files themselves, verbatim, at the end of the report.

### `AudioSpectrumEngine.java` — 2553 lines
Centralized audio spectrum capture and 16-band Post-DSP analysis engine.
- `getLatestWaveform(byte[] outBuffer)` — Copies the latest raw PCM waveform snapshot (unsigned 8-bit, 128 = zero baseline).
- `storeMeasuredLatency(Context context, LatencyProbe.Result result)` — Turns a measurement into the base the analyser will use from now on, and returns it.
- `storedLatencyBaseMs(Context context)` — The stored base, or -1 when this head unit has never been measured.
- `loadDisplaySettings(Context context)` — Re-reads the display preferences and pushes them into the native analyser.
- `getDisplayRangeDb()` — How many dB the 0..1 display level spans: level = (dB - top + range) / range.
- `isMicrophoneOpen()` — The spectrum's own microphone capture is open and reading.
- `isMicrophoneFirstOnInput()` — The spectrum's capture opened the input itself - see RadioMicCapture.firstOnInput.
- `microphoneSessionId()` — Session of the spectrum's latest recorder (kept after release), 0 when there has been none.
- `registerListener(OnSpectrumDataListener listener, int consumer)` — @param consumer which automatic-gain profile this listener wants - the main analyser is an instrument and may be asked to show absolute levels, while the status bar widget is decoration and is usually allowed to normalis
- `setDspState(int[] gainIdx, boolean[] qNarrow, int subFreqIdx, int subGai)` — Publishes the DSP state exactly as it was sent to the hardware.
- `setDebugDump(boolean enabled)` — Logs one line per second: content level and DSP curve per band, so the numbers can be checked.
- `dumpCapture(int ms, boolean asPlayed)` — Starts a capture dump of ms milliseconds, 500..2700 (the stitched ring holds 2.7 s).
- `readCurve(float[] contentDb, float[] correctionDb)` — The live RTA's input for one display frame: the shown analyser's curve as measured (contentDb, see NativeAnalyzer#getCurveDb) and the correction its mode adds (correctionDb): the chip's response in the calculated mode, t
- `rtaZeroDb()` — The zero the live curve is drawn against, for the analyser shown now; NaN for the floating average.
- `onRtaZero(float zeroDb)` — @param zeroDb the zero taken, NaN when too little was heard
- `captureRtaZero(RtaZeroListener listener)` — Takes the RTA's zero from what plays now - meant for Audio Check's pink noise - over RtaReference#CAPTURE_MS, read straight from the analyser shown at a third of an octave, so it does not depend on the curve being on scr
- `resolveForOwnPlayback()` — Our own test signal started (Audio Check): find its session now rather than waiting for the previous one to go quiet.
- `onMeasuredCurvesChanged()` — Re-pushes whatever the measurements have taught us into the running analyser.
- `describeForReport()` — What the analyser is doing, in the words a report needs.
- `hasSignalNow()` — Whether the spectrum engine has delivered signal just now, from either AudioFlinger media playback or the cabin microphone for analogue radio.
- `hasMediaSignalNow()` — Whether the AudioFlinger media session has delivered signal just now (excluding mic capture).
- `isRadioCaptureActive()` — Whether the microphone capture is actually reading - not merely whether it was started.
- `applyNativeSettings()` — Pushes the current settings - ballistics, latency and both gain profiles - into every analyser.
- `onWake()` — The head unit has woken up (ACC_ON; also called at boot, where nothing is running yet).
- `onRootAnswerChanged()` — RootAccess took a new answer (the root card, a first microphone use elsewhere).

### `BalancePointerView.java` — 198 lines
A small draggable Material-style dot meant to be overlaid directly on top of the car image in the Fader (Positioning) screen.
- `setBalance(float lr, float fr)` — Update the shown position.

### `BootReceiver.java` — 100 lines
Receiver to start McuService on boot or quick boot.

### `ButtonBacklight.java` — 97 lines
The head unit's button backlight in wDSP's accent while the main screen is shown, and the unit's own colour back when it is not - the author's 0.5 (setButtonRgbCyan).
- `apply(Context ctx)` — Paints the buttons in the accent of the theme now on screen.
- `restore()` — Gives the buttons back the colour the unit had before #apply.

### `CabinDefect.java` — 197 lines
One fault the cabin measurement found: what it is, where, how sure we are, and what to do about it.
- `reportLine()` — One line for the English report: what, where, how sure, and what to do.
- `isNote()` — A note rather than a fault: the screen shows the list calmly when nothing else was found.
- `screenLine(android.content.Context context)` — The same finding for the screen, in the person's language: how sure, then what and what to do.

### `CabinGeometry.java` — 171 lines
Where the microphone and the loudspeakers stand in the cabin - the measurement's one geometry.
- `mic(int micPlace, float spotLr, float spotFr, float halfWidthCm,)` — Where the microphone was, as {x, y, z}.
- `speaker(Channel ch, float halfWidthCm, float distListen, int subPlac)` — Where a loudspeaker is, as {x, y, z}.
- `distanceCm(float[] a, float[] b)` — Straight-line distance between two points, in centimetres.

### `CabinProfile.java` — 217 lines
Everything this app knows about the car it measures in, and the physics that follows from it - the cabin's counterpart of MicProfile, and like it the one place that answers.
- `hasSubwoofer(Context context)` — Whether a subwoofer is connected.
- `isSubwooferAnswered(Context context)` — Whether anybody has actually answered the subwoofer question, as opposed to inheriting the default - "true because nobody said" and "true because somebody said" are different facts.
- `setPairs(Context context, boolean front, boolean rear)` — Which door pairs the car has.
- `isClosed(Context context)` — A closed cabin; false for open space - the bench, a cabriolet with the roof down.
- `transitionHz(float lengthCm)` — Where a sealed cabin stops behaving as a room and becomes a pressure vessel: the frequency whose half-wavelength is the cabin's acoustic length, c / (2L), never above #MAX_TRANSITION_HZ.
- `axialModesHz(float dimensionCm)` — Standing waves along one dimension, n * c / (2d), up to #MODES_UP_TO_HZ.
- `expectedRiseDb(float hz, float transitionHz, boolean closed)` — What the cabin itself adds at hz: the pressure-zone rise below the transition in a closed cabin, nothing in open space or above the transition.
- `expectedRiseDb(Context context, float hz)` — #expectedRiseDb(float, float, boolean) for the car the person described.
- `expectedRiseCurve(Context context)` — #expectedRiseDb(Context, float) at the sixteen band centres - what the microphone estimate expects the cabin itself to add there; zeros in open space.
- `describe(Context context)` — For the measurement report, in English as the rest of it.

### `CallPreset.java` — 199 lines
The service preset for phone calls.
- `intValue(String suffix)` — The array itself.
- `readView(SharedPreferences base)` — Wraps a store so that reads of the service preset come from the array above.

### `CallState.java` — 89 lines
Whether a phone call is in progress.
- `announce(boolean start)` — The vendor broadcast: PHONE_CALL_START (true) or PHONE_CALL_END (false).
- `expireOrphanedAnnouncement(String activeVolumeType)` — Clears an announcement the hardware has not confirmed within #ANNOUNCED_WATCHDOG_MS.
- `isCallType(String activeVolumeType)` — The predicate itself, for a caller that has already read the active volume type this poll.
- `isPhysicalCallActive()` — Checks whether a call is physically active on hardware via sys.qf.call_state or active volume type.
- `isActive()` — Checks whether a call is active via broadcast announcement or hardware state.

### `CaptureConsentActivity.java` — 67 lines
The one screen that has to exist so a projection can be asked for.

### `CaptureProbe.java` — 372 lines
Finds out what an application's audio actually looks like from outside it.
- `carriedSound()` — Whether the application handed anything over at all.
- `run(Context context, MediaProjection projection, String pkg, int)` — Records one application for a while and reports what came out.
- `run(Context context, MediaProjection projection, String pkg, int)` — @param usages when given, ask by what the sound is for instead of by who made it.

### `CaptureProbeService.java` — 244 lines
Holds a media projection while CaptureProbe measures with it.

### `ChannelSwapCheck.java` — 192 lines
Do the speakers arrive in the order the cabin says they should - or only with two of them swapped?
- `describe()` — One line for the English report: the numbers, whatever the verdict.
- `pairLabel(Channel[] pair)` — "front left front right" for a pair.
- `judge(float[] arrivalMs, float[] flightMs)` — @param arrivalMs by Channel#ordinal(): when the direct sound arrived, NaN when it was not heard directly (absent, not declared, or heard only through the cabin) @param flightMs by Channel#ordinal(): the time of flight th

### `CrashLog.java` — 99 lines
Keeps the last crash and the last swallowed failure where a person can hand them over.
- `install(Context context)` — Idempotent; called from every entry point of the process.
- `recordCaught(Context context, String where, Throwable error)` — A failure the app caught and carried on after - kept so it is not lost with the logcat.
- `describe(Context context)` — Both records, for the screen report.

### `Downloads.java` — 147 lines
Writes a file straight into the public Downloads folder.
- `initialUri()` — The folder a file picker should open at, so restoring lands where saving put things.
- `pathFor(String fileName)` — Where a file will end up, in the form a person can look for.
- `create(Context context, String fileName, String mimeType)` — Creates the file and hands back a stream to write it.
- `finish(Context context, Pending pending)` — Closes the file and makes it visible.
- `discard(Context context, Pending pending)` — Removes a file that was never finished, so a failed save leaves nothing behind.

### `DspResponse.java` — 245 lines
Models what the BU32107 actually does to the signal, as a per-band dB curve.
- `isSubOff(int subFreqIdx)` — Whether a stored "_sub_f" is the author's "No Sub".
- `nearestSubLpfIndex(float hz)` — The chip's code for the subwoofer low-pass nearest to hz.
- `doorHpfIndexOf(float hz)` — The chip's code for the door high-pass nearest to hz; 0 Hz is code 0, Through.
- `faderStepsForCut(float cutDb)` — The most fader steps whose cut does not exceed cutDb: never more than asked.
- `compute(int[] gains, boolean[] qNarrow, float[] fmOffsets, int subFr)` — Full DSP response in dB per band - what the preset makes the hardware do, the "target" the calculated spectrum shows (owner, 14.09.2026), with nothing of the car in it.
- `computeAt(float[] freqsHz, int[] gains, float[] fmOffsets, int subFreq)` — The same response as #compute, at any frequencies instead of the band centres - what the live RTA curve adds in the calculated mode, one point per frequency.
- `doorHpfHz(int code)` — Hz for a door high-pass code; 0 for Through or an unknown code.
- `highPass2Db(float freqHz, float cutoffHz)` — The door high-pass in dB, 0 when off - the author's model with its boost shelf left out (the shelf is the separate P2Bass control, not the crossover).
- `lowPass2Db(float freqHz, float cutoffHz)` — The subwoofer low-pass in dB, the author's model at his filter order, 0 when off.
- `powerSumDb(float aDb, float bDb)` — Adds two levels as energy rather than as numbers.

### `EqVisualizerView.java` — 531 lines
The main screen's EQ curve.
- `setLoudnessCorrection(float[] curveGains, boolean active)` — What loudness drives the EQ with: gain indices 0..12 as the chip will get them (the sliders and the curve pre-warped and rounded - LoudnessCurve.eqDriveDb / gainIndex).
- `setSubFilter(float cutoffHz, float gainDb)` — The subwoofer's crossover (Hz, 0 = no subwoofer) and its total gain in dB.
- `setBassShaping(float frontFilterHz, float frontBoostHz, float frontBoostDb,)` — The doors' bass stage, front and rear: high-pass cut-off (Hz, 0 = through), shelf frequency (Hz, 0 = off) and shelf gain (dB) - as they reach the chip, loudness's share included.

### `FmVisualizerView.java` — 351 lines
The loudness tab's preview (the author's "Correction" view): the equaliser's gains with the loudness offsets on top, as MainActivity computes them for the volume the sliders show, and the warnings LoudnessCheck raises ab
- `setGains(int[] newGains)` — The EQ indices 0..12 as sent to the chip (slider plus correction, pre-warped and rounded).
- `setSubFilter(float cutoffHz, float gainDb)` — The subwoofer's low-pass in Hz (0 = no subwoofer, nothing drawn) and its gain in dB.
- `setBassShaping(float filterHz, float boostHz, float boostDb)` — The front doors' high-pass and bass shelf, as AudioConfig.bassShapingResponseDb takes them.

### `HardwareProfile.java` — 595 lines
Answers the two questions that decide how much this head unit can be trusted to sound the same as the next one: which sound processor is fitted, and whether the audio policies are the stock ones.
- `mcuCode()` — The hardware code from the MCU version string, or null if the property is not there.
- `mcuType()` — Which microcontroller family the board carries, or "unknown".
- `soundProcessor()` — Which sound processor is fitted, by its factory name, or "unknown".
- `tuner()` — Which tuner is fitted, by its factory name, or "unknown".
- `outputPath()` — Analogue or I2S between the MCU and the processor - and what the platform published from the same character.
- `audioHub()` — Which AKM audio hub sits between the MCU and the amplifier, if any.
- `hasBu32107()` — True when the unit carries the ROHM BU32107, false when it is the cut-down BD37534.
- `captureHasVoiceProcessing()` — Whether the capture path carries voice processing - noise suppression in particular.
- `appVersion(android.content.Context context)` — The build that produced whatever is being written, as a human reads it.
- `appVersionTag(android.content.Context context)` — The same fact shaped for a file name: no spaces, no brackets, sorts sensibly.
- `describe()` — One line for the log and for the diagnostics screen.
- `describeScreen(android.content.Context context, android.view.View view)` — The screen, and where the system says its bars are.
- `sampleScreen(android.content.Context context, android.view.View view)` — Takes the screen description while a window exists, so a report written later can use it.
- `screenDescription(android.content.Context context)` — The last sampled screen description, or a fresh one without insets if none was taken.
- `systemProperty(String key)` — Package-visible: SystemDiagnostics reads a long list of these for its report.

### `LatencyProbe.java` — 750 lines
Measures, rather than guesses, how far the spectrum analyser runs ahead of the sound.
- `measureAsync(final AudioManager audioManager, final Listener listener)` — Runs the whole measurement on its own thread and logs the outcome.
- `measureAsync(final AudioManager audioManager, final boolean lowLatency, f)` — @param lowLatency ask for the low-latency output instead of the deep buffer one.
- `measureAsync(final AudioManager audioManager, final boolean lowLatency, f)` — @param acoustic also listen with the microphone.
- `measure(AudioManager audioManager, boolean lowLatency, boolean acous)` — The measurement itself.
- `find(long expectedNs)` — Finds the burst nearest the moment it was expected and returns when it was heard.

### `LivePreset.java` — 231 lines
Preset values the screen has changed and the disk has not stored yet.
- `publish(SharedPreferences stored, Map<String, Object> values)` — One tick of the screen: a preset's values as they are now.
- `stored(Map<String, Object> values)` — The disk holds these now: forget each one that still has the same value.
- `readView(SharedPreferences base)` — Reads through the values not stored yet, then the store.
- `writeTo(SharedPreferences.Editor e)` — Puts everything remembered into a real editor.

### `LoudnessCheck.java` — 288 lines
Checks a preset's loudness curve and says what is wrong with it.
- `inspect(int[] gains, int subGainIdx, int subFreqIdx, int bassBoost, )` — @param gains 16 gain indices 0..12 as stored in the preset @param subGainIdx subwoofer gain index 0..12 @param subFreqIdx index into DspResponse#SUB_FREQS_HZ @param bassBoost the larger of the front and rear bass boost l
- `maxDeliverableStrength(int[] gains, int subGainIdx, int subFreqIdx, boolean subComp)` — The strongest curve these band gains leave room for.

### `LoudnessCurve.java` — 283 lines
The Fletcher-Munson loudness curve and the fatigue trim, in one place.
- `loudnessRatio(int vol, int cal)` — How far the loudness curve has travelled at this volume: 0 where it does nothing, 1 at the bottom of the scale.
- `rampAbove(int vol, int startVol)` — How far a curve that lives above a start volume has travelled: 0 at the start, 1 at the top of the scale.
- `ultraBassOffset(int vol, boolean enabled, int startVol, int maxDb)` — Ultra Bass, the author's 0.5: extra subwoofer gain that grows with the volume - 0 at its start volume, maxDb at 32 - for when the bass gets thin as the music gets loud.
- `offsets(int vol, int cal, int strengthPct, boolean fmEnabled, boolea)` — Fills out with the 16 band offsets in dB - the target, before the pre-warp (#eqDriveDb).
- `eqDriveDb(int[] gains, float[] offsets)` — What the equaliser is driven with, per band in dB, before the chip's 2 dB rounding and +/-12 dB clamp (#gainIndex): the sliders plus the offsets, pre-warped together while an offset is active (AudioConfig#prewarpEq, the 
- `gainIndex(float driveDb)` — The chip's gain index for a drive in dB: 2 dB steps around index 6, clamped to 0..12.
- `frontHz()` — The front shelf's frequency in Hz, 0 when off - for the models that draw it.
- `bassShelf(int vol, int cal, int strengthPct, boolean fmEnabled, int fr)` — The shelf as the author's 0.5 sends it.
- `subOffset(int vol, int cal, int strengthPct, boolean fmEnabled, boolea)` — The extra subwoofer gain the loudness curve asks for, in gain-index steps of the 0x8B command.
- `maxSubBoost(int subFreqIdx)` — The subwoofer's share of the loudness curve at volume 1, by crossover: the ISO 226 boost (AudioConfig#ISO_FULL_TARGET_DB) of the highest equaliser band the subwoofer plays on its own - one band below the band its crossov
- `hzOf(int subFreqIdx)` — The crossover in hertz.

### `MainActivity.java` — 3762 lines
The main screen: the 16-band equaliser with its live spectrum behind it, presets, the subwoofer and the door filters, delays and fader, the loudness tab (FM curve, fatigue, bass shelf, Ultra Bass), GALA and Audio Check.

### `McuLink.java` — 54 lines
The one way into the QF MCU manager (mcu_service), by reflection: the DSP frame (RPC_SetEQData, the 0x80..0x8C commands) and the MCU message (RPC_SendMcuMsgData, message 0x18 and its sub-commands - the power-amp pre-gain
- `setEqData(byte[] frame)` — Sends one DSP frame.
- `sendMsg(byte message, byte[] payload)` — Sends one MCU message.

### `McuService.java` — 2748 lines
Background service to handle MCU communication, dynamic Fletcher-Munson EQ adjustment, player-based preset switching, and GALA (Speed Sensitive Volume).

### `MeasurementFailure.java` — 59 lines
Why a room measurement or a microphone calibration stopped, said twice from one place: in English for the log and the report a tester sends us, and in the person's language for the dialog.
- `english(Object... args)` — The log and report line.
- `localized(Context context, Object... args)` — What the dialog says.

### `MicInputWindow.java` — 186 lines
Waits for the moment the microphone input is ours to take again.
- `watch(Context context, BooleanSupplier ownRecorderOpen, int ownSes)` — Start waiting.

### `MicProbe.java` — 288 lines
Opens the head unit's microphone and writes what it hears to a WAV, so the capture path itself can be examined before anything is built on top of it.
- `probeAsync(final Context context, final int source, final int durationM)` — Records for the given time, writes a WAV next to the app's files, logs what it found.
- `probeAsync(final Context context, final int source, final int durationM)` — @param lowLatency ask for the MMAP input path.
- `restore()` — Puts every effect back the way it was and lets go of it.

### `MicProfile.java` — 403 lines
Everything this app knows about the microphone it is measuring with: what it is built into, where it sits, and what each of those does to a measurement.
- `placeNames(Context context)` — The list as the person sees it, in order.
- `place(Context context)` — -1 when nobody has said yet, which the report prints as "not stated".
- `statedBody(Context context)` — -1 when nobody has said, which is not the same as "open".
- `effectiveBody(int statedBody, int place)` — The construction to work with: what was stated, or what the place implies beyond doubt.
- `effectiveBody(Context context)` — The same question, answered straight from what the person stored.
- `heightCm(int place)` — Height of the microphone above the ear line, in centimetres.
- `spotLeftRight(Context context)` — Where the person says the microphone is, on the same −1..1 axes the balance control uses.
- `mountingCurve(Context context)` — The mounting curve to start a calibration from - sixteen zeros when nothing is known.
- `applyPlacementLimits(Context context, int[] autoEqGains16)` — Limits the synthesized gains for what the microphone's SURROUNDINGS did, not its housing.
- `bodyDescription(Context context)` — What the microphone is built into, in words.
- `placeDescription(Context context)` — In English regardless of the person's language: the report is read by us.
- `spotDescription(Context context)` — The spot in words - "front right", "centre", and so on.

### `MicrophoneGuard.java` — 658 lines
Gets the microphone back from whatever is holding it, so a measurement is not made through half of one.
- `ensureOurs(Context context)` — Claims the microphone and checks, by the device rate, that it is full band; if another app holds it narrow, takes it back through root or says a restart is needed.
- `holderSessionId()` — The placeholder's session, 0 when none was ever held - ours, never another app's recording.
- `releaseHold()` — Lets the placeholder go.
- `openCaptureRecord()` — Opens a capture the way this app records and measures - the Single Source of Truth (SSOT).
- `openCaptureRecord(int minBufferSize)` — Opens a capture with a requested minimum buffer size.
- `takeInputAsRoot(Context context)` — Stops whoever records from a microphone input right now, through root, and waits for the input to close under them - so that whoever opens next opens it fresh and first.
- `inputHoldersAsRoot(Context context)` — Installed packages other than ours whose recorders are active on a microphone input, read through root from AudioFlinger's record tracks (the client pid of every active one, and the process behind it).
- `checkCameBackAsync(Context context, List<String> packages)` — Reports, off the caller's thread, whether each stopped package is running again - the second half of the owner's order.
- `repairAssistantMicOnce(Context context)` — Gives the assistant its microphone back, once, on units where 0.4.9.x took it away.

### `NativeAnalyzer.java` — 263 lines
Java side of the native spectrum analyser.
- `isAvailable()` — False when the shared library could not be loaded; callers must keep the Java path alive.
- `NativeAnalyzer(int sampleRate, int captureSize, boolean acoustic)` — @param acoustic whether the input is a microphone.
- `isAcoustic()` — Whether this analyser listens to a microphone - see the constructor.
- `push(byte[] waveform, int length, long captureTimeNs)` — Feeds one polled block of unsigned 8-bit samples.
- `pushPcm16(short[] samples, int count, float gain)` — Feeds continuous 16-bit PCM samples directly into the analyser ring buffer, skipping the stitcher.
- `process(int timeoutMs)` — Runs analysis for whatever has been captured, blocking until there is enough or the timeout expires.
- `stop()` — Unblocks process() so its thread can exit.
- `setHop(int hop)` — Samples between frames: 512 gives 94 frames a second at 48 kHz, 1024 gives 47.
- `setDspCurve(float[] curve16)` — The response the hardware DSP adds, on the 16 hardware bands.
- `forgetNoiseFloor()` — Forgets the learned noise floor without dropping the stream - for waking up.
- `getLevels(int consumer, float[] out32, float[] out16)` — Display levels, 0..1, for one consumer.
- `getLevelsDb(float[] out32)` — Raw band levels in dB, before normalisation - used by the diagnostics dump.
- `getLevelsDb16(float[] out16)` — The same frame folded onto the 16 equaliser bands, in dB, through the one native fold - with no consumer's offset or gain.
- `getCurveDb(float[] outCurve)` — The live curve, held back by the playback latency like the bands: #CURVE_POINTS values in dB, each the power in a third of an octave around its point - pink noise reads level - with no correction curve and no noise floor
- `setCurveFraction(int octaveFraction)` — The curve points' width as a fraction of an octave: 3 (the bands' third, the default) or 24 for a test tone, which a third would spread into a plateau.
- `curveHz(int j)` — Frequency of curve point j, the same log spacing the native side uses.
- `setLevelOffsetDb(int consumer, float offsetDb)` — A shift in dB added to a consumer's bands before they are scaled; 0 in a new analyser.
- `discontinuities()` — Blocks that could not be aligned.
- `getWaveform(byte[] outBuffer)` — Reads latest samples into byte array as 8-bit unsigned waveform centered at 128.
- `readStream(float[] out)` — Diagnostics: the newest stitched samples as the transforms read them.
- `getTermsDb(float[] power32, float[] floor32, float[] curve32)` — Diagnostics: band power before the floor, the learned floor and the added curve, in dB.

### `NativeSweep.java` — 350 lines
Java side of the sweep measurement in cpp/sweep.cpp.
- `thirdCentreHz(int i)` — Centre of third-octave band i, the native grid's own formula: 1000 * 2^((i - 18) / 3).
- `levelOffsetDbAgainst(NativeSweep other)` — How much louder this sweep's bands read than those of a sweep covering other's span, in dB.
- `length()` — Length of the sweep in samples.
- `generate(float[] out, float amplitude)` — Fills the buffer with the sweep at the given amplitude.
- `analyse(float[] recorded, int length, float[] result)` — Deconvolves one recording and fills result with #RESULT_SIZE numbers.
- `bandwidthRatioDb(float[] recorded, int length, int sampleRate)` — How much of a recording sits above 8 kHz, relative to the band below, in decibels.
- `noiseFloor(float[] signal, int length, float[] out16)` — Calculates the 16-band energy spectrum (in dB) of an ambient noise signal slice.
- `subtractNoise(float[] sweepDb16, float[] noiseDb16, float[] outCleanDb16, )` — Performs spectral subtraction band-by-band: clean_power = max(sweep_power - noise_power, 1e-12) snr_db = sweep_db - noise_db
- `estimateMicCompensation(float[] envelope16, float[] worstEnvelope16, float[] meanEnv)` — Estimates the 16-band microphone inverse compensation curve.
- `deconvolve(float[] recorded, int length, float[] outImpulse)` — Deconvolves one recording into its impulse response.
- `gccPhatDelay(float[] hRef, int refLen, float[] hCh, int chLen, float[] ou)` — Estimates the time difference of arrival (TDOA) in fractional samples between a channel impulse response and a reference channel impulse response using GCC-PHAT.
- `crossoverNotch(float[] a32, float[] aNoise32, float[] b32, float[] bNoise32)` — A tweeter fighting its mid-bass on one side of a mirrored pair: a notch at 2..5 kHz in one channel's third-octave response and not in the other's (sweep.cpp, crossoverNotch).
- `lowResonances(float[] ch32, float[] noise32, int count, int[] band, int[] )` — Narrow peaks below 200 Hz across count channels' thirds, laid one after another (sweep.cpp, lowResonances).
- `pairImbalanceDb(float[] a16, float[] snrA16, float[] b16, float[] snrB16, fl)` — How much louder channel A plays than its mirror B at 200 Hz ..
- `detectMidbassRollOffHz(float[] avgClean16)` — Where the door speakers stop delivering bass, in Hz (50, 63, 80 or 100) - the chip's code for it is DspResponse#doorHpfIndexOf.
- `synthesizeAutoEq16(float[] avgClean16, float[] micComp16, float[] snr16, float )` — Synthesizes 16-band Auto-EQ gains and subwoofer settings matching the chosen TargetCurve.

### `NotificationAccess.java` — 21 lines
Declared so that media sessions can be read, and for nothing else.

### `NowPlaying.java` — 861 lines
What is playing right now: who, what, how far through, and a picture.
- `titleLine()` — The part of #line() without the artist: album and title, in reading order.
- `line()` — One line for the marquee, in the order a person reads it.
- `progress()` — How far through, 0..1, or -1 when it cannot be known.
- `playerIcon()` — The icon of whichever app owns the sound, or null.
- `hasTrack()` — Whether there is anything worth putting on the strip at all.
- `playerPackage()` — The application the sound belongs to, for anyone who has to name it to the platform.
- `radioPackageFor(String reported)` — Which radio application a reported package really is - the ONE place that decides it.
- `playPackage(String pkg)` — Asks a particular player to play, if it has a live media session.
- `isRadioSource()` — Whether the sound is coming from the analogue tuner rather than from a file or a stream.
- `canReadSessions()` — Whether the owner has given us notification access, which is what sessions need.

### `OverlayHealth.java` — 72 lines
Remembers whether the last attempt to put a window over other apps worked.
- `lastAttemptRefused(Context context)` — True when the last window we tried to put over other apps was refused.
- `describe(Context context)` — One line for the screen report.

### `PcmStatus.java` — 126 lines
Which sound-card stream the kernel currently has open, read straight from /proc/asound.
- `openPlayback()` — Every playback substream that is not closed, in device order.

### `PlaybackFocus.java` — 92 lines
Audio focus for sound this app plays itself - a sweep, a test tone, a test track - held while it plays, so the platform treats wDSP as the player that owns the sound.
- `isRequested()` — Whether a request is standing - granted or not, until #abandon or a loss.
- `request(Context context, Listener listener)` — Asks for GAIN focus for media.
- `abandon(Context context)` — Gives the focus back.

### `PlayerResume.java` — 619 lines
Starts the player that was playing again, after a restart or after the car slept.
- `onPlaybackState(String pkg, boolean playing)` — @param pkg the player's package, empty when there is no session at all
- `onAccOff()` — Ignition off: decide now what "was playing" means, before the platform pauses and kills.
- `onServiceReady()` — The service has loaded and applied the presets.
- `onAccOn()` — Ignition on.

### `PresetsDatabaseValidator.java` — 432 lines
Validates, repairs, and migrates the wDSP EQ & audio presets database (EqPresets.xml).
- `validateAndMigrate(Context context)` — Inspects the entire presets database, migrating any legacy presets, repairing corrupted or out-of-range values, recovering orphaned presets, and updating the DB version.
- `sanitizePreset(SharedPreferences.Editor editor, SharedPreferences prefs, St)` — Sanitizes a single preset by name, ensuring all required parameters exist, are within valid hardware ranges, and mutual exclusion rules are respected.

### `PropertyWatch.java` — 109 lines
Wakes a listener when any system property changes - a subscription where Android has none.
- `start(String[] keys, Runnable onChange)` — @return the running watch, or null when the native wait cannot be had on this unit.

### `RadioMicCapture.java` — 809 lines
🎙️ Captures cabin acoustic audio via microphone for analogue FM Radio visualization.
- `onPcmChunk(short[] boosted, short[] raw, int count, float gain)` — One chunk, delivered twice over.
- `forgetNoiseFloor()` — Forget the cabin's floor and the gain, as a fresh start would, without closing the stream.
- `setPlaybackSilent(boolean silent)` — Called by the engine whenever its picture of who is playing changes.
- `noiseFloorRms()` — What the floor currently reads, in RMS counts, for whoever wants to report or subtract it.
- `onMicrophoneUnavailable()` — The microphone cannot be had at full band and root cannot fix it.
- `onMicrophoneFullBand()` — Half a second into an open, the platform reports the input under our recorder at full band - after a cold start, and after taking the input back through a gap.
- `isRunning()` — Started and not yet stopped - that is, holding a recorder that stop() has to give back.
- `isCapturing()` — Started, and the read loop is still alive.
- `firstOnInput()` — Capturing, and nobody was on the input when our recorder opened it - so the input is set up the way we opened it, whoever joined later.

### `RoomMeasurement.java` — 4468 lines
Measures the car, one loudspeaker at a time.
- `measureVolumeFor(int current)` — The volume a pass plays at, given the one set now.
- `lastMeasureVolume(SharedPreferences prefs)` — Where the loudness curve should be anchored: the volume the car was last measured at, 0 when it was never measured.
- `subPlace(Context context)` — -1 when nobody has said; the boot is then assumed, as it always was.
- `pauseMedia(Context context)` — Pauses any active media player using KEYCODE_MEDIA_PAUSE before the sweep.
- `getMicCompensationCurve(Context context)` — Reads the calibrated 16-band microphone inverse compensation curve from SharedPreferences.
- `hasMicCompensation(Context context)` — Checks if a calibrated microphone compensation curve exists in SharedPreferences.
- `setMicCompensationCurve(Context context, float[] curve)` — Persists the calibrated 16-band microphone inverse compensation curve to SharedPreferences.
- `micCompensationHistory(Context context)` — Every microphone curve this unit has had before the current one, newest first.
- `hasCabinResponse(Context context)` — True when a cabin measurement has left a response curve behind - see #PREF_CABIN_RESPONSE.
- `getCabinResponseCurve(Context context)` — The car's own response shape, or sixteen zeros when nothing has been measured.
- `failureText(Context context)` — What the dialog says about a failed run, in the person's language where we know why.
- `isUsable()` — Every speaker the person said the car has was heard.
- `isDeclared(int index)` — Whether the speaker at this channel index is part of the layout the person declared.
- `declaredButUnheard()` — Speakers the person declared and the measurement did not hear, for the log and the report.
- `measureAsync(final Context context, final float amplitude, final float se)` — Runs a full measurement on its own thread with subwoofer, soundstage mode, target curve, body type and listening distance.
- `measureAsync(final Context context, final float amplitude, final float se)` — The short way in, used by the debug broadcast: what the car is gets read here, once, from the place that owns it.
- `restoreIfInterrupted(Context context)` — Puts back anything a measurement changed but did not manage to restore.
- `autoEqPresetName(Result result)` — The name a measurement's preset is saved under - the target curve and the stage, both fixed English (see SoundstageMode), so measuring the same stage again replaces it.
- `applyAutoEqPreset(Context context, Result result, String presetName)` — Applies the synthesized Auto-EQ preset directly to SharedPreferences and broadcasts to McuService.
- `runsDir(Context context)` — The folder every run lives under, one subfolder each.
- `micCalibrationReport(Context context)` — The last microphone calibration's report, kept apart from the runs.
- `outputDir(Context context)` — The folder holding the measurement being taken, or the last one taken.
- `hasResult(Context context)` — True when there is a measurement on disk worth sending.
- `measureAsync(Context context, Listener listener)` — Convenience for the defaults, used by the debug broadcast.

### `RootAccess.java` — 251 lines
Asks Magisk for root out loud, instead of finding out in silence that it was never coming.
- `hasRoot()` — Whether this process has root, as Magisk last answered.
- `checkAtStart(Context context)` — The start check: once per process, and only when Magisk has answered this installation before.
- `checkForMicrophone(Context context)` — The first time in this process that a person switches the microphone on: asks Magisk, and may raise its prompt - that is the point of asking here, on an action, rather than at start.
- `request(Context context)` — The root card: a person asked for root, so Magisk is asked whatever the last answer was, and its prompt may appear.

### `RtaCurve.java` — 152 lines
The live RTA's display model - the author's, from his 1.0 SpectrumAnalyzerView - applied to this branch's measurement (AudioSpectrumEngine#readCurve).
- `update(float[] contentDb, float[] correctionDb, float rangeDb, long)` — One display frame.
- `update(float[] contentDb, float[] correctionDb, float rangeDb, long)` — One display frame against a fixed zero.
- `contentLevelDb(float[] contentDb, float rangeDb)` — The level of a measured curve, as the fixed zero is taken: the dB average of its points that have sound - within rangeDb of the loudest - without any correction, so the equaliser still moves the curve after the zero is f
- `smoothedDb()` — The curve after the ballistics and before any correction - what the screen draws, in the measurement's dB.
- `relativeDb()` — The last frame, dB relative to the zero: the average of what had sound, or the fixed one.
- `reset()` — Forgets the ballistics, as after a stop: the next frame is taken as it is.

### `RtaReference.java` — 59 lines
The live RTA's fixed zero: the level pink noise reads, taken once and kept (owner, 08.10.2026: calibrate the RTA by pink noise, a fixed zero rather than the frame's floating average - see RtaCurve).
- `zeroDb(boolean microphone)` — The zero of the channel's or the microphone's curve, or NaN when none was taken.

### `ScreensaverManager.java` — 1920 lines
A full-screen visualiser that appears when the head unit has been left alone.
- `isEnabled()` — On unless it has been switched off.
- `onPreferencesRestored()` — Called when preferences have been restored from backup or migrated.
- `infoBarFraction()` — How much of the screen the now-playing strip takes, within its limits.
- `coversStatusBar()` — Whether the screensaver may sit over the system status bar.
- `heightFraction()` — How tall the band is, as a fraction of the screen height.
- `brightness()` — Bar brightness for the theme in force, 10..100.
- `blockedPackages()` — Packages the screensaver is never shown over.
- `setScreenState(boolean on)` — Called from the service, once, and whenever the screen goes on or off.
- `onCallInProgress()` — A call takes the screen, whatever else is true (owner, 14.09.2026: "подзвонили - скрінсейвер зняти").
- `describeForReport()` — Everything needed to say, from a unit nobody here has seen, why the screensaver did or did not appear: each rule with the value it read, whether the idle clock ever got near the delay, and what last held it back.
- `packageOf(String currentActivity)` — com.example/.MainActivity -> com.example
- `currentForegroundPackage()` — What the platform says is in front right now, for the settings screen to offer as a hint.
- `launchablePackages(Context context)` — For the settings screen: the packages worth offering, newest-looking first.

### `SessionProbe.java` — 184 lines
Diagnostic-only helper: answers the single question the spectrum engine cannot answer by itself - which audio session, if any, this app is actually allowed to tap on this head unit.
- `probeAsync(final int sessionId, final int durationMs)` — Probes a single session id and logs the result.
- `scanAsync(final Context ctx, final int maxCandidates, final int perPro)` — Walks candidate session ids downwards from the platform's current counter and logs every one that carries audio.
- `probe(int sessionId, int durationMs)` — Attaches a Visualizer to one session, measures it, releases it.

### `SessionResolver.java` — 301 lines
Decides which audio session AudioSpectrumEngine should attach its Visualizer to.
- `onSessionResolved(int sessionId)` — Called off the main thread with a session known to carry audio, or -1 if none found.
- `start()` — Starts listening for players that announce their session.
- `forget(int sessionId)` — Drops a session from every cache - call when it stopped delivering audio.
- `resolveAsync(final String playerPackage, final Callback callback)` — Resolves asynchronously and calls back on a worker thread.
- `mediaTrackStarted(AudioManager am)` — Whether a media track is started in AudioFlinger - the only thing a Visualizer can be put on.

### `SettingsActivity.java` — 3782 lines
The settings screen: style and theme, the spectrum's look, the cabin card (speaker layout, cabin size, open or closed, microphone and subwoofer placement), the microphone calibration and the cabin measurement wizard with

### `SourceLevelWatch.java` — 73 lines
Watches the level the platform stores for every source that is not playing, so a change made somewhere else reaches wDSP's per-source memory instead of being overwritten by it.
- `poll(String liveType)` — Reads every source but the live one and returns those whose stored level changed since the last read, with the new level.
- `resync(String liveType)` — Learns every non-live level as it stands, reporting nothing.
- `note(String type, int level)` — Records a level this service itself wrote for a non-live source, so it is not reported back.

### `SpeakerLayoutView.java` — 164 lines
Which speakers the car has, drawn over the measurement's car picture and changed by a tap (owner's plan, 02.10.2026: «комплектація — тап по динаміках»).
- `setColors(int accent, int ink, int plateColor)` — Accent for a speaker the car has.

### `SpectrumAnalyzerView.java` — 368 lines
The live spectrum behind the equaliser's sliders: the RTA curve (RtaCurve, the author's display model) or the 16 bars, chosen in the settings; on the equaliser's grid, relative to the frame's average or to the fixed zero
- `shapeOf(android.content.SharedPreferences prefs)` — Reads the stored shape; anything but bars is the curve, the default.
- `onDraw(@NonNull Canvas canvas)` — 🔴 Owner, 15.09.2026 (option В): the bars are drawn on the equaliser's grid in the equaliser's own decibels, relative to the frame's average.

### `StateEvents.java` — 35 lines
"Something the sound state is made of may have changed" - one bell for the whole app, so the service's check, the analyser's watchdog and the screensaver look when it rings instead of each on its own clock (owner, 12.10.

### `StatusBarVisualizerManager.java` — 969 lines
Manages the Status Bar Visualizer overlay lifecycle, positioning, styling, and audio gating.

### `StatusBarVisualizerView.java` — 1512 lines
Transparent status bar overlay visualizer view.
- `sampleCurrentTheme(float fraction)` — Samples the active theme or palette at a given normalized fraction (0.0 to 1.0) using current default alpha.
- `sampleCurrentTheme(float fraction, int baseAlpha)` — Samples the active theme or palette at a given normalized fraction (0.0 to 1.0) with an explicit alpha channel value (0..255).
- `styleButtonInset(float density, float stripPx)` — Distance of the button's centre from the right edge and from the bottom edge, in px: half the now-playing strip, so the centre is on the strip's axis.
- `setBackdropCoversInsets(boolean covers)` — Whether the backdrop is painted over the status bar's own height or starts below it.

### `SystemDiagnostics.java` — 884 lines
Everything about this head unit's audio, in one block of text, plus a running log of the moments it changes.
- `report(Context context, boolean withMicrophoneProbe)` — The whole diagnostic, ready to be written to a file.
- `microphoneProbe()` — Opens every audio source in turn and says what happened.
- `arm(Context context)` — Starts listening for the moments the audio path changes.
- `record(Context context, String event)` — One line: the time, what happened, and the whole volume model as it stood at that instant.
- `sourceName(int source)` — Capture source by name - the one spelling the logs and reports share.

### `Toaster.java` — 73 lines
Short messages to the person, drawn in the current style and theme instead of the system's toast.

### `ToneSource.java` — 96 lines
A sine, played as if it were something in particular.

### `TouchWatcher.java` — 104 lines
Notices that somebody touched the screen, anywhere.

### `VolumeHelper.java` — 253 lines
VolumeHelper handles both standard Android volume and K706 (QF) hardware volume.
- `ensureInit(Context context)` — For callers that may run before anything called #init (the screensaver).
- `setVolumeShown(Context context, int val)` — Sets the person's own volume - the level of the source playing now, the one the encoder turns - and shows the factory volume banner, as the encoder does.
- `canReachOtherSources()` — Whether this unit can store a level for a source that is not the live one.
- `getVolumeForType(String volumeType)` — The stored level of any source, live or not.
- `setVolumeForType(String volumeType, int val)` — Stores a level for a source.

### `ui/Disclosure.java` — 54 lines
A title that folds the controls under it - the author's 1.0 pattern (the loudness groups, GALA's "Advanced"), one implementation for every such title instead of a click listener per section.

### `ui/OpenInDownloads.java` — 99 lines
Opening a document, starting in the folder this app saves into.
- `aim(Context context, Intent intent)` — Points an ACTION_OPEN_DOCUMENT intent at Download/wDSP.

### `ui/PermissionsWizard.java` — 555 lines
🛡️ Єдиний стандарт онбордингу та майстра дозволів (Permissions Onboarding Wizard).
- `areAllGranted(Context context)` — Перевіряє чи всі дозволи надані (включно з Root, якщо на пристрої є su).
- `checkAndShowIfNeeded(Activity activity)` — Opens the wizard on its own only when it has something to say.
- `show(Activity activity)` — Показує майстер дозволів вручну (наприклад, з Налаштувань).
- `refreshCurrent()` — Оновлює активний відкритий діалог (викликається в onResume та onRequestPermissionsResult).
- `refresh()` — Оновлює візуальний стан кожної картки (зелена/сіра).

### `ui/RowFit.java` — 281 lines
Shrinks a row of controls as a whole, in proportion, when the window is too narrow for it.
- `attach(LinearLayout row, View flexible, int flexibleMinPx, View... )` — @param flexible the view that stretches (a slider), or null for a row with none - a row of buttons sharing the width by weight, such as the loudness toggles (owner, 02.10.2026: «навчи RowFit стискати ряд без повзунка»)
- `scale()` — What the row was last shrunk to, 1 when it fits as designed.

### `ui/SettingsAccordion.java` — 356 lines
🪗 Акордеон для екрана налаштувань wDSP — секції динамічно згортаються/розгортаються зі збереженням стану в SharedPreferences.

### `ui/SupportBanners.java` — 219 lines
The two credit-and-support capsules at the top of Settings.
- `build(Activity a, ViewGroup host, boolean night)` — Builds both capsules into host, replacing whatever was there.

### `ui/ThemedDialog.java` — 1322 lines
Unified dialogs that strictly follow the app's palette and ThemeManager.
- `notice(Context context, CharSequence title, CharSequence message)` — A dialog that says one thing and waits to be dismissed.
- `message(Context context, CharSequence title, CharSequence message, C)` — Convenience for the common "title, message, one or more buttons" case.
- `showInput(Context context, String title, String currentText, String po)` — Unified text input dialog (renaming presets, slots, etc.).
- `showConfirmation(Context context, String title, String message, String positi)` — Unified confirmation dialog (preset deletion, reset actions, etc.).
- `showOptions(Context context, String title, String subtitle, Option[] opt)` — Unified interactive option cards with touch feedback.
- `showList(Context context, String title, String[] items, OnItemClickLi)` — Unified card-based list dialog (matching QFRadio ThemedDialogBuilder.showList).
- `showCustom(Context context, String title, View customView, String posit)` — Unified modal dialog for custom view layouts.

### `ui/theme/FrostedGlassDrawable.java` — 405 lines
Багатошаровий скляний Drawable (Glassmorphism / Frosted Glass).

### `ui/theme/ThemeManager.java` — 1107 lines
Керує обчисленням палітри кольорів та шпалер у runtime.
- `getUiStyle(Context ctx)` — Modern unless the store says Classic: a backup from another version may carry anything else.
- `isColorDark(int color)` — Визначає, чи є колір темним за формулою відносної яскравості (WCAG / ITU-R BT.601).
- `getContrastColor(int background)` — Повертає гарантовано контрастний колір (білий або темний #11171D) для заданого фону.
- `contrastText(int textColor, int background)` — Перевіряє контраст тексту до фону.
- `linkBlue(boolean night)` — Standard hyperlink blue color, contrast-tuned for day/night themes.
- `styleAsLink(android.widget.TextView tv, boolean night)` — Styles a TextView as a standard hyperlink (link blue + underline).
- `tintSlider(Slider s, int accent, boolean night)` — One look for a slider, by style: Classic is the author's thin bar on a 4 dp track, Modern our round thumb on a 5 dp one.
- `getThemedColor(Context ctx, boolean night, int colorRes)` — Повертає колір з ресурсів із гарантованим урахуванням теми (Day/Night) через ConfigurationContext.
- `getThemedDrawable(Context ctx, boolean night, int drawableRes)` — Повертає Drawable з ресурсів із гарантованим урахуванням теми (Day/Night) через ConfigurationContext.
- `fastBlur(Bitmap src, int radius, int passes)` — Швидке розмиття (Box blur, 2-3 проходи) у пам'яті для генерації матового скла.
- `getWallpaperChromaticSpectrum(Context ctx, boolean night)` — Компілює горизонтальний спектр кольорів шпалер (8 точок) для скляного бару.
- `dockSubstrateColor(Context ctx)` — Повертає ефективний середній колір скомпільованої підкладки дока для перевірки автоконтрасту.
- `presetsDockBackground(Context ctx, boolean night)` — Фонова підкладка верхнього бару пресетів з органічним асиметричним огинанням: - Лівий край огинає прямокутний дропдаун (спінер) пресетів (концентричний радіус 18dp під внутрішній 14dp).

### `ui/theme/TouchGlow.java` — 202 lines
✨ ПІДТВЕРДЖЕННЯ ДОТИКУ — СПАЛАХ АКЦЕНТНИМ КОЛЬОРОМ З ПІСЛЯСВІЧЕННЯМ 150 мс.

### `ui/views/HueWheelView.java` — 156 lines
HSV-диск вибору кольору: відтінок (hue) — по колу, насиченість — від білого центру (0) до чистого кольору на краю (1).

### `ui/views/ScaleToFitLayout.java` — 116 lines
Holds its child at the child's designed size and scales it, whole and uniformly, to fit the room it is given - never larger than designed.

### `ui/views/SegmentedPillNavView.java` — 543 lines
Плаваючий сегментований навігаційний бар (Floating Pill Navigation Bar).
- `onMeasure(int widthMeasureSpec, int heightMeasureSpec)` — Narrow the tabs before resorting to a swipe.

### `ui/views/TextScroller.java` — 130 lines
Slides one line of text sideways when it is too long for its box, and stands still when it fits.
- `attach(final TextView view)` — Starts scrolling view's text whenever it does not fit.

## Native (`cpp`)

### `analyzer.h` — 271 lines
The spectrum analyser.
- `setDspCurve(const float* curve16)` — Response the hardware DSP will add, in dB, on the 16 hardware bands.
- `forgetNoiseFloor()` — Forgets the learned noise floor, as a new analyser would start, without dropping the stream.
- `getWaveform(uint8_t* out, int maxLen)` — Reads the newest samples as 8-bit unsigned waveform (0..255, centered 128).
- `readStream(float* out, int count)` — Diagnostics: the newest samples of the stitched stream exactly as the transforms read them, -1..1.
- `getTermsDb(float* powerDb32, float* floorDb32, float* curveDb32)` — Diagnostics: for each of the 32 bands, the power of the last frame before the noise floor came off, the learned floor, and the curve added on top - all in dB.
- `setNoiseFloorEnabled(bool enabled)` — Whether a noise floor is learned and taken off.
- `pushWaveform(const uint8_t* block, int len, int64_t captureTimeNs)` — Feeds one polled Visualizer block.
- `waitAndProcess(int timeoutMs)` — Runs the analysis for whatever has accumulated, blocking until there is enough or the timeout expires.
- `stop()` — Unblocks waitAndProcess so the thread can exit.
- `setHop(int hop)` — Sets how many samples pass between frames.
- `pushPcm16(const int16_t* samples, int count, int channels, float gain )` — Feeds signed 16-bit PCM, the format AudioRecord delivers.
- `getLevels(int consumer, float* out32, float* out16)` — Fills 32 and 16 band levels, normalised to 0..1 for the given consumer.
- `getLevelsDb(float* out32)` — Fills the raw band levels in dB, before any normalisation.
- `getLevelsDb16(float* out16)` — The same frame folded onto the 16 equaliser bands, in dB - through the one fold getLevels uses - without any consumer's offset or gain.
- `getCurveDb(float* outCurve)` — The live curve, held back by the playback latency like the bands: kCurvePoints values in dB, each the energy of a third of an octave around its point, computed by the bands' own function - a point on a band centre reads 
- `setCurveFraction(int octaveFraction)` — How wide each curve point is: 3 = a third of an octave (the default, the bands' width), 24 = a twenty-fourth for a test tone - a third of an octave turns a sine into a plateau, and the author turns his smoothing off whil
- `setLevelOffsetDb(int consumer, float offsetDb)` — A shift in dB added to every band before a consumer's levels are scaled - for drawing one analyser on another's scale (the microphone on the calculated spectrum's).
- `processFrame(bool haveLong)` — One frame of analysis.
- `readDelayedFrame(float* out32)` — Body of getLevelsDb, for callers that already hold the lock.
- `delayedFrameIndex()` — The ring slot the display reads: the newest frame held back by the playback latency.
- `foldTo16Db(const float* db32, float* out16Db)` — 32 third-octave bands in dB onto the 16 equaliser bands in dB - the only fold there is.
- `makePlan(float centerHz, int octaveFraction = 3)` — A third of an octave around centerHz: its edges, its transform and its bins.
- `buildCurvePlan(int octaveFraction)` — Rebuilds the curve's plan at a new width; analysis thread only.
- `planEnergy(const BandPlan& p, const float* power, int binCount)` — The energy a plan holds in a power spectrum - one function for the bands and the curve.

### `bands.h` — 19 lines
Bands of the standard third-octave grid, 16 Hz ..
- `thirdOctaveCentreHz(int i)` — Centre of third-octave band i: exactly 1000 * 2^((i - 18) / 3), so band 31 is 20 kHz and every hardware centre (20, 31.5, 50 ...

### `fft.h` — 42 lines
Iterative radix-2 FFT with precomputed twiddles and a precomputed Hann window.
- `powerSpectrum(const float* input, float* out)` — Windows the input, transforms it, and writes the power of each bin (|X|^2) into out.

### `stitcher.h` — 71 lines
Rebuilds a continuous audio stream out of the overlapping blocks the platform Visualizer hands back.
- `push(const uint8_t* block, int len, int expectedNew)` — Appends one freshly polled block.
- `appendContinuous(const float* samples, int count)` — Appends samples that are already known to be continuous, skipping the alignment search.
- `readNewest(float* dst, int count)` — Copies the newest count samples into dst, oldest first.
- `totalSamples()` — Total samples ever appended - the caller uses it to decide when a hop is due.
- `discontinuities()` — Blocks that could not be aligned; a rising count means the poll rate is too slow.

### `sweep.h` — 281 lines
Room measurement by exponential sine sweep.
- `generate(float* out, float amplitude)` — Writes the sweep, scaled to the given amplitude, into a caller-owned buffer.
- `deconvolve(const float* recorded, int recordedLength, std::vector<float)` — Recovers the impulse response from a recording of the sweep.
- `findArrival(const float* impulse, int length, float& prominence)` — Index of the direct sound within an impulse response.
- `polarityAt(const float* impulse, int length, int arrival)` — Sign of the direct sound: negative means the loudspeaker is wired the wrong way round.
- `clarityDb(const float* impulse, int length, int arrival, int sampleRat)` — How much the direct sound stands above what follows it, in decibels.
- `bandwidthRatioDb(const float* signal, int length, int sampleRate)` — How much of a recording lives above 8 kHz, relative to the band below it, in decibels.
- `bandLevels32Db(const float* impulse, int length, int arrival, float* out32)` — The same response on the standard third-octave grid (bands.h): 32 bands, 16 Hz ..
- `spectrum16Db(const float* signal, int length, float* out16)` — 16-band energy spectrum (in dB) of an arbitrary signal slice (such as ambient noise floor).
- `subtractNoise(const float* sweepDb16, const float* noiseDb16, float* outCl)` — Performs spectral subtraction band-by-band: clean_power = max(sweep_power - noise_power, 1e-12) snr_db = sweep_db - noise_db
- `estimateMicCompensation(const float* avgClean16, const float* worstClean16, const fl)` — Estimates the 16-band microphone inverse compensation curve from the 4-channel average clean response using the Cabin Gain Anchor (+12 dB/oct below 80 Hz) and high-frequency acoustic port roll-off correction.
- `gccPhatDelay(const float* hRef, int refLen, const float* hCh, int chLen, )` — Time difference of arrival (TDOA) in fractional samples between a channel impulse response and a reference channel impulse response using Generalized Cross-Correlation with Phase Transform (GCC-PHAT) and sub-sample parab
- `detectMidbassRollOffHz(const float* avgClean16)` — Estimates the natural acoustic roll-off frequency of midbass speakers by comparing the clean 16-band energy spectrum to the midrange reference (200..800 Hz).
- `crossoverNotch(const float* a32, const float* aNoise32, const float* b32, c)` — A tweeter fighting its own mid-bass on one side only: a notch at the crossover, 2..5 kHz, in one channel of a mirrored pair and not in the other.
- `pairImbalanceDb(const float* a16, const float* snrA16, const float* b16, con)` — How much louder channel A plays than its mirror B in the midrange (200 Hz ..
- `midbandReference(const float* bands16)` — Mean of bands 5..8 (200..800 Hz) that read above -70 dB; NaN when none does.
- `snrConfidence(float snrDb)` — Trust in a band measured snrDb above its noise: 0 below 6 dB, 1 above 18, a ramp between.
