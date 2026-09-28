# DECISIONS — why the rules in AGENTS.md exist

Each entry is the episode that produced a rule. Kept because a rule without its cost is the first thing a session
argues its way out of. No dates of sessions, no status — only what was learned and what it cost.

## A matching number is a reason to look, not a finding

An interval measured and an interval observed agreed to within 2 ms and had nothing to do with each other. The
broadcast delivery skew between two applications here is 191 ms; the radio's two announcements were 193 ms apart, and
this side declared one the cause of the other. The real cause was in the neighbour's code — two call sites reading the
level from different places, so the announcements carried different numbers and its de-duplication was right not to
fold them. The coincidence was strong enough that asking for the source never came up, and it took the other side
reading its own code to end it.

## A criterion that cannot fail proves nothing

"At least one" is satisfied by one and by three alike, so a test written that way passes while the fault it was meant
to catch happens in front of it. A burst of three announcements where one was expected lived for weeks behind exactly
such a check, and was found only when somebody counted.

## Name the state by reading it, never by remembering what you did

Both sides broke this twice in one night. One read the word `muted` in the other's log as an action when it is a state,
and built a causal model on it. The other wrote "the property is still true" from the memory of its own `setprop`, half
an hour after the radio's service had rewritten it. Both mistakes look like confidence; a claim narrowed to what was
actually read seems weaker and is stronger by exactly the amount it never has to be withdrawn.

## Fix one side of a transition, then look at its pair

The same seam was repaired three times running — an announcement with no gate, then a reply with no gate, then a
handover that announced nothing. Each fix was correct and each was half. The second half is never found by re-reading
the first.

## Name the thing, not the index

The owner was told a decision was waiting on him and given its number instead of its content. A report is not a pointer
into the reporter's own notes.

## One fact, one function — and the second source is often a widget

The cabin measurement computed a 100 Hz subwoofer crossover and wrote it to the preferences. `savePreset` then read the
frequency from the **text in a spinner**, and the resolver answered "80 Hz" both to "the field is empty" and to "I do
not recognise this" — so a screen that had not finished loading was indistinguishable from a deliberate choice, and an
autosave put 80 Hz back over the measured 100. The door high-pass stayed at 100 because it comes from a slider, so the
octave between 80 and 100 Hz belonged to nobody and the owner heard it as missing bass.

The guard that should have prevented it (`isFullyInitialized`) already existed and was **older than the bug** — but the
sixteen band gains, the sixteen Q switches, the subwoofer gain and frequency and the power level all sat outside it.
The crossover is simply the field that happened to be audible.

## Do not re-measure settled physics

The microphone curve was driven to zeros by demanding that established acoustics be re-derived from our own sweep
before it could enter the code. The mounting matrices (a 1.5–2 mm hole with a cavity behind it is a Helmholtz
resonator) were refused as "a model, not our measurement"; the cabin-gain anchor was deleted while fixing a bug that
was really in the measurement — the calibration swept the doors with the subwoofer silent, so the doors' roll-off was
charged to the capsule. The result was an app reporting no bass in a car whose bass was plainly audible.

Three things settled it. The physics is not in doubt. The bench cannot arbitrate a model of a cabin anyway. And the
output is 16 bands at 2 dB with a fixed `Q = 2.2`, so a number tighter than ±2 dB is resolution the equaliser does not
have. What replaced the refusal is not blind trust: a premise that is checkable gets checked — §24 claimed the signal
below 31.5 Hz sinks under the converter's noise at SNR ≤ 0 dB, and this hardware measures ~50 dB there, so the band
limit is decided by measured SNR rather than by a hard-coded band index.

## Absence of measurement is not a measurement

When the assistant holds the microphone at 16 kHz the sweep narrows itself to 7 kHz and says so — and the analysis then
read the silence above 7 kHz as a deep hole in the car and boosted 8 k, 12.5 k and 20 kHz to the ceiling. Bands that
were never excited decided the top octave. A band outside the sweep now gets a confidence of none, which is the same
path a badly measured band already takes.

## The microphone is won by holding it, not by killing the holder

Force-stopping the assistant hotword never settled it: it restarts and finds a free input. With the spectrum analyser
in microphone mode the app holds the input continuously and the hotword cannot take it; switching the analyser to
calculated mode released it, and the hotword had the device at 16 kHz before the next measurement asked. Between the
guard's verdict and the measurement's own open sit the scratch preset, the volume lock, the push to the chip and its
settle delay — three seconds of idle input, every pass. The guard now claims the device and holds it until the real
capture is open, and taking, holding and opening all live in one class.

## A threshold must sit inside the gap it detects

The bandwidth warning fired on five consecutive healthy passes because it used the same −30 dB figure as the
microphone guard — but the guard measures half a second of room noise and this is asked of a six-second sweep to
20 kHz. Healthy reads −36 dB here and broken reads −97; the threshold sat above healthy, so the warning was
unconditional and therefore worthless, and it is why the logs could not tell whether a genuinely broken pass had
warned at all.

## A stale document is worse than none

Two claims in `ROOM_CALIBRATION.md` contradicted the code and nearly caused a non-bug to be "fixed"; the handoff spent
ten days describing work as uncommitted that had been pushed.

## Gemini and git: constructive yes, destructive no (owner, 29.09.2026)

The rule used to be carried as "Gemini has no git rights". That was our over-reading. The owner:
*«Я ніколи не забороняв ДЖ конструктивні права і інструменти гіт, навпаки настоюю на повному його
праві, заборона стосується на комміти без пояснень, хардресети чи аменди. І коммітити в проекти,
що ведеш ти»*.

**Banned:** `reset --hard`, `clean`, `stash`, `checkout -- .`, `push --force`, `--amend`; a commit
with no explanation; any commit into a project a Claude session leads.
**His by right:** reading (`status`, `diff`, `log`, `show`), his own branch, `restore` on a single
file he corrupted himself.

**Why not wider.** On 02.09.2026 a `reset --hard` of his destroyed a day of uncommitted work in the
radio project — but the cause was that he was guessing: he had corrupted a file through a
PowerShell pipe and reached for the biggest hammer. Blindness is what produced the reset, so a ban
on looking makes the next one more likely, not less. What actually protects a tree is committing
before letting anyone in. Our own share of that day is recorded too: the risky instruction was
written without the technique for carrying it out safely.

**The sanctioned pattern for experiments:** when a Claude session is unavailable, the owner may put
Gemini on experimental features — in a separate branch, ideally in a sandbox. It has worked.
