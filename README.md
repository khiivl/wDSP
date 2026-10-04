<img width="1280" height="646" alt="wDSP_repo" src="https://github.com/user-attachments/assets/13869fb3-748f-4d1f-aa26-e6bf8a090b9e" />

----------------------------------------------------------------------------------------------------------------------

This is a free and open-source DSP app designed to fully replace the stock DSP app on K706/QF Android head units. 

The app communicates with the MCU using the framework, so it doesn't require rooting the device.
Compatible with vertical and horizontal head units with the BU32107 chip.

Made in Germany, by a Ukrainian guy.

I have to give credits to the people who taught me a lot i know about sound for free: oratory1990 (legend), Crinacle (headphone), Underbelly from YSAP (Uncle Joe) and, of course, Dan Worrall (the trustworthy British accent). Credits to Sam Battle from LMNC for making me interested in audio circuits. Many thanks to Konstantyn Matviyevsky for mentoring, insides into the inner workings of the head unit and the inspiration for this project and making difficult things simple. Try his mod of wDSP if you want something different, because everyone has their own preferences and vision.

Measure accurately, correct surgically, and stop trying to boost acoustic nulls.

Telegram group for discussion and bug reporting (also use Issues on GitHub): https://t.me/wDSPapp

If you want to buy me a coffee or otherwise support me financially, use this link: https://buymeacoffee.com/radiorubka or this link: https://paypal.me/wDSPApp. If you don't want to support me specifically, but want to make a donation for the good, please use this link to support the soldiers who are currently defending Ukraine from the Russian aggression: https://www.sternenkofund.org/en/donate

----------------------------------------------------------------------------------------------------------------------

<details>

<summary>Screenshots</summary>

<img width="1024" height="600" alt="Screenshot_20261005_005940" src="https://github.com/user-attachments/assets/09721a9f-3791-4137-a185-770a3648caf9" />

<img width="1024" height="600" alt="Screenshot_20261005_005954" src="https://github.com/user-attachments/assets/6c704193-b5e7-4991-b266-04a1b7f4f289" />

<img width="1024" height="600" alt="Screenshot_20261005_010001" src="https://github.com/user-attachments/assets/e0c89c59-40c6-44c6-a871-062c8d1eec62" />

<img width="1024" height="600" alt="Screenshot_20261005_010007" src="https://github.com/user-attachments/assets/c65fb258-926d-4663-9780-54b7c099f088" />

<img width="1024" height="600" alt="Screenshot_20261005_010015" src="https://github.com/user-attachments/assets/9f2a6137-e363-4f0f-bcc1-a7ba70bb4b2e" />

<img width="1024" height="600" alt="Screenshot_20261005_010021" src="https://github.com/user-attachments/assets/614a968c-a940-4f0e-b8dd-ca9f7e2c109e" />

</details>

----------------------------------------------------------------------------------------------------------------------

Features:

Equalization:
- EQ that is true-to-hardware (16 bands with 2dB per step), correctly labeled with true curve visualization, with a fixed Q of 2.2. Subwoofer control on the same page. Amp gain control.
- Visualization of every filter, every setting, all on the same page, a curve that follows the shape of all the filters (self-developed approximation algorithm) with a self-developed RTA (1024-sample, Hann-windowed STFT, ~50% overlap, radix-2 Cooley-Tukey FFT, Catmull-Rom spectral interpolation, 0.45-octave triangular smoothing, plus an asymmetric attack/release envelope follower for the temporal ballistics)
- ISO 226 Loudness Correction curve which uses all the settings available to compensate for the non-linearity of hearing at low volumes. Self-developed anti-ripple algorithm for the EQ (2.2Q bell filters cause ripple!), Bass Boost for the low frequencies, dynamic subwoofer gain tweaking, Ultra Bass function to compensate for the head unit not scaling subwoofer gain correctly with the volume. All done to make music sound as perceptually "flat" as possible at all volumes, true to the coloring settings set by the user.
- Bass filtering and boost just like in the stock DSP, but with correctly labeled values.

Other:
- "Positioning" delays for careful tweaking of the sound center with 0.5ms step up to 5ms for all the speakers and subwoofer, also expressed in centimeters for convenience.
or
- "Surround" delays more suited for Haas effect, no subwoofer tweaking, max 10ms with 1ms step. "Rear fill enhancement", which i'm not sure if it does anything, but i have copied the control logic verbatim for a possible future MCU update.
- Speaker balance adjustable with an XY controller and step buttons featuring my car, beloved Skoda Fabia mk1.

Presets:
- All the settings in the app are saved to a preset that the user is able to duplicate, export, import and rename.
- Automatic preset switching system that makes it possible to apply presets to different audio types, like Media, AUX, Radio and Bluetooth calls. Default Call preset with optimized values.

GALA:
- Uses GPS data to read vehicle speed and calculates a volume boost based on the parameters the user sets. Just like on VAG radios, or in the Nightrunners demo.

Soundcheck:
- Theme song loop that i composed that allows you to play the bass, drums, melody and vocals separately or combined in stems for a repeatable tuning reference.
- Pink noise generator.
- Sine wave generator with precise control, sweep function and a perceptual normalization at 0 phon (hearing threshold) which can be really quiet and expose the distortion of the DSP or the amp.
- Test buttons for all the speakers
- Sub-only toggle to make everything apart from the sub as quiet as possible.

----------------------------------------------------------------------------------------------------------------------

How to use:

1. Install, launch, give all the permissions, add to sleep whitelist in 8888. Ready to use. Don't use the stock DSP app, since it will reset your settings if launched and closed, if that happens - restart wDSP.
2. If you want to disable the stock DSP app (recommended!), run the 'adb shell pm disable com.qf.soundeffect' command to disable it. Use 'adb shell pm enable com.qf.soundeffect' to enable it back.
3. (Root only) You can install the wDSP-Proxy as an update to the stock DSP app to have the button in the quick settings open wDSP. You need to have PMPatch3.zip Magisk module installed, if you have disabled it, you need to enable it again. https://github.com/vova7878-modules/PMPatch/releases

My recommendations:
1. You have to know that the 2.2Q filters on the EQ don't tolerate shelving. The inter-frequency interaction (in the worst case - ripple) can:
- Destroy the phase at high frequencies, making everything sound "smeared" even on high-end setups.
- Furthermore, they can affect the phase of the bass and make the interaction between the sub and the door speakers unpredictable, destroying the linearity of the sound and even causing gaps in the frequency response.
Therefore:
- Cut, not boost. Boosting can cause DSP clipping if overdone and cause ripple if too many bands are adjusted at the same time. Surgical cuts (like at the lower treble region, where the hearing is the most sensitive, or at the resonance points of the cabin/speaker setup) can make all the difference in the world.
- If you have to boost the bass, adjust the sub or the bass boost sliders. That's generally all you need.
2. If you have a sub, a generous crossover is recommended. Start by setting the sub freq to 63Hz and the HPF (Bass filter) freq of the front and rear speakers to 100Hz. If you have door rattle, that will tame it as well. Adjust if needed. Also turn on Ultra Bass, because the sub gets quieter as you adjust the master volume (QF platform quirk). Adjust if needed, rinse and repeat. Look at the RTA and the curves on the EQ screen for clues. If using the Loudness feature, try Sub tweaking, it will boost the sub at low volumes.
3. Turn on the Loudness, Sub tweaking, Trim Highs and Show on main. Set the Loudness calibration volume to roughly 80db (Freight train at 50ft, food blender, highway car interior). That is the point where the hearing is the flattest. The Loudness curve will be applied to the sound below that level. Don't treat as gospel, adjust by taste afterwards. Be aware that Loudness causes some inter-frequency ripple that can't be avoided. Don't use the feature if that bothers you or if you always listen to music at the same volume.
4. GALA feature is used to compensate for road noise at higher speeds. Set the values conservatively, i recommend a max adjustment of 6 and a step every 50km/h for a start, reduce if it gets too loud, increase if it gets too quiet. Only touch the Advanced settings if you have specific issues.

Report, report and report bugs again. It is crucial to me that you share what doesn't work so i can keep improving the app.

----------------------------------------------------------------------------------------------------------------------

Written on Java, set to target API29. Reverse-engineered proprietary MCU communication protocol. Communicates with the framework.jar service using reflections.

The app is licensed with the GPLv3 license. 
If you improve the program and share it, you must share the source code too.

License rights belong to Volodymyr Chebanenko, the creator and the maintainer.
