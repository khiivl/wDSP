<img width="1280" height="646" alt="wDSP_repo" src="https://github.com/user-attachments/assets/13869fb3-748f-4d1f-aa26-e6bf8a090b9e" />

----------------------------------------------------------------------------------------------------------------------

This is a free and open-source DSP app designed to fully replace the stock DSP app on K706/QF Android head units. 

The app communicates with the MCU using the framework, so it doesn't require rooting the device.
Compatible with vertical and horizontal head units.

Telegram group for discussion: https://t.me/wDSPapp

If you want to buy me a coffee or otherwise support me financially, use this link: https://buymeacoffee.com/radiorubka or this link: https://paypal.me/wDSPApp

----------------------------------------------------------------------------------------------------------------------

Screenshots:

<img width="1024" height="600" alt="Screenshot_20260531_220710" src="https://github.com/user-attachments/assets/009e54f0-6bab-433e-bf0a-d837e69970f3" />


<details>

<summary>More screenshots</summary>

<img width="1024" height="600" alt="Screenshot_20260531_220716" src="https://github.com/user-attachments/assets/1a3dea59-97b6-4a31-8bc0-b4fe709b7fa9" />

<img width="1024" height="600" alt="Screenshot_20260531_220719" src="https://github.com/user-attachments/assets/32da504c-c540-469c-9679-902174cb4cc0" />

<img width="1024" height="600" alt="Screenshot_20260531_220724" src="https://github.com/user-attachments/assets/37ff2c4a-df97-4209-9a51-da6a6e68fe2b" />

<img width="1024" height="600" alt="Screenshot_20260531_220728" src="https://github.com/user-attachments/assets/1055af53-7c7b-43cc-9975-af0a485cb8b5" />

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
