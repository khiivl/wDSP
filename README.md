<img width="639" height="646" alt="wDSP" src="https://github.com/user-attachments/assets/0baf552c-7748-44b9-a6af-c55643d18e3c" />

This is a free and open-source DSP app designed to fully replace the stock DSP app on K706/QF Android head units. 

The app communicates with the MCU using the framework, so it doesn't require rooting the device.
Compatible with vertical and horizontal head units.

Telegram group for discussion: https://t.me/wDSPapp

If you want to buy me a coffee or otherwise support me financially, use this link: https://buymeacoffee.com/radiorubka or this link: https://paypal.me/wDSPApp

----------------------------------------------------------------------------------------------------------------------

Screenshots:

<img width="1024" height="600" alt="Screenshot_20260531_220141" src="https://github.com/user-attachments/assets/b71dc595-7a18-4795-ae06-8c1b568ebce8" />

<img width="1024" height="600" alt="Screenshot_20260531_220154" src="https://github.com/user-attachments/assets/7bc92c5f-1833-408c-8285-fd38b1fbbba8" />

<img width="1024" height="600" alt="Screenshot_20260531_220154" src="https://github.com/user-attachments/assets/b008a339-1852-4e49-a05c-0b884227a0f5" />

<img width="1024" height="600" alt="Screenshot_20260531_220210" src="https://github.com/user-attachments/assets/bf32da75-672f-47a9-897d-7f7f082f17d6" />

<img width="1024" height="600" alt="Screenshot_20260531_220215" src="https://github.com/user-attachments/assets/2192e503-9929-43b3-a70d-8a188a820588" />

<img width="1024" height="600" alt="Screenshot_20260531_220220" src="https://github.com/user-attachments/assets/64579a4b-9302-4b17-987f-15d427a88d6b" />

----------------------------------------------------------------------------------------------------------------------

How to use:

1. Install, launch, give all the permissions, add to sleep whitelist in 8888. Ready to use. Don't use the stock DSP app, since it will reset your settings if launched and closed.
2. If you want to disable the stock DSP app, run the 'adb shell pm disable com.qf.soundeffect' command to disable it. Use 'adb shell pm enable com.qf.soundeffect' to enable it back.
3. (Root only) You can install the wDSP-Proxy as an update to the stock DSP app to have the button in the quick settings open wDSP. You need to have PMPatch3.zip Magisk module installed, if you have disabled it, you need to enable it again. https://github.com/vova7878-modules/PMPatch/releases

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
