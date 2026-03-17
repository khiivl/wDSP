This is a free and open-source DSP app designed to fully replace the stock DSP app on K706/QF Android head units. 

The app communicates with the MCU using the framework, so it doesn't require rooting the device.
Compatible with vertical and horizontal head units.

If you want to buy me a coffee or otherwise support me financially, use this link: https://buymeacoffee.com/radiorubka

----------------------------------------------------------------------------------------------------------------------

How to use:

1. Install, launch, give all the permissions, add to sleep whitelist in 8888. Ready to use. Don't use the stock DSP app, since it will reset your settings if launched and closed.
3. If you want to disable the stock DSP app, run the 'adb shell pm disable com.qf.soundeffect' command to disable it. Use 'adb shell pm enable com.qf.soundeffect' to enable it back.

----------------------------------------------------------------------------------------------------------------------

Features:

Equalization:
- EQ that is true-to-hardware (16 bands with 2dB per step), correctly labeled, with Q control with presets of 2.2 and 4.7. Subwoofer control on the same page.
- Customizable loudness curve to make music sound better at low volumes. Calibration and subwoofer tweaking included.
- Bass filtering and boost just like in the stock DSP, but with correctly labeled values.

Other:
- "Positioning" delays for careful tweaking of the sound center with 0.5ms step up to 5ms for all the speakers and subwoofer, also expressed in centimeters for convenience.
or
- "Surround" delays more suited for Haas effect, no subwoofer tweaking.
- Faders for speaker balance.

Presets:
- All the settings in the app are saved to a preset that the user is able to duplicate, export, import and rename.
- Automatic preset switching system that makes it possible to apply presets to different audio types, like Media, AUX, Radio and Bluetooth calls.

----------------------------------------------------------------------------------------------------------------------

Written on Java, set to target API29. Reverse-engineered proprietary MCU communication protocol. Communicates with the framework.jar service using reflections.

The app is licensed with the GPLv3 license. 
If you improve the program and share it, you must share the source code too.
