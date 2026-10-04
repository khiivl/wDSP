<img width="1280" height="646" alt="wDSP_repo" src="https://github.com/user-attachments/assets/13869fb3-748f-4d1f-aa26-e6bf8a090b9e" />

----------------------------------------------------------------------------------------------------------------------

This is a free and open-source DSP app designed to fully replace the stock DSP app on K706/QF Android head units. 

The app communicates with the MCU using the framework, so it doesn't require rooting the device.
Compatible with vertical and horizontal head units with the BU32107 chip.

Made in Germany, by a Ukrainian guy.

I have to give credits to the people who taught me a lot i know about sound for free: oratory1990 (legend), Crinacle (headphone), Underbelly from YSAP (Uncle Joe) and, of course, Dan Worall (the trustworthy British accent). Credits to Sam from LMNC for making me interested in audio circuits.

Measure accurately, correct surgically, and stop trying to boost acoustic nulls.

Telegram group for discussion: https://t.me/wDSPapp

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
