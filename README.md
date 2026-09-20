# Security Alarm (Android)

A loud, fixed-volume alarm you arm by hand. Plays an audio file you choose (wav, mp3, m4a, flac, ogg, ...)
from the PLAY button, or from the locked screen by pressing Volume Up or Volume Down three times.

## Part A - Get the APK onto your phone

### Option 1: Android Studio (recommended)
1. Install Android Studio (free): https://developer.android.com/studio
2. Unzip `SecurityAlarm.zip`. In Android Studio choose **File > Open** and select the `SecurityAlarm` folder.
3. Wait for "Gradle sync" to finish (first time: several minutes, needs internet).
   If it says a package is missing (e.g. "Android SDK Platform 34"), click the blue **Install** link and accept.
   If it offers to upgrade the Android Gradle Plugin, you can decline ("Remind me later").
4. **Build > Build Bundle(s) / APK(s) > Build APK(s)**. When done click **locate**:
   the file is `app/build/outputs/apk/debug/app-debug.apk`.
5. Send that APK to the phone (USB cable copy, Google Drive, e-mail to yourself, ...).

   *Alternative - install straight from Android Studio:* on the phone enable **Developer options**
   (Settings > About phone > tap "Build number" 7 times), then **USB debugging**; connect the cable,
   accept the prompt on the phone, choose your phone at the top of Android Studio and press the green **Run** button.

### Option 2: No Android Studio - build in the cloud with GitHub (free)
1. Create a free account at github.com and a new repository (private is fine).
2. Upload everything from the unzipped folder to it (including the hidden `.github` folder).
3. Open the **Actions** tab > **Build APK** > **Run workflow**. After ~5 minutes open the finished run and
   download the artifact **SecurityAlarm-debug-apk** (a zip containing `app-debug.apk`).

### Install the APK on the phone
1. Open the APK file on the phone (Files app / Downloads).
2. Android asks to allow "Install unknown apps" for the app you opened it from (Files, Chrome, Drive...) -
   allow it, go back, tap **Install**.
3. If Google Play Protect warns about an unknown developer, choose **Install anyway** (you built it yourself).

## Part B - First-time setup on the phone (5 minutes)
1. Open **Security Alarm**. Allow **Notifications** when asked.
2. Tap **Choose sound file...** and pick your audio file. (If you choose none, the phone's default alarm tone is used.)
3. Set **Alarm volume** (100% = maximum). Leave "Force phone speaker" on for a loud, headphone-independent alarm.
4. Tap **Play** once to test - then **Stop**.
5. **Lock-screen trigger:** tap **Open Accessibility settings** > find **Security Alarm lock-screen trigger**
   (under Installed apps / Downloaded apps) > turn it **On** > confirm.
   * If the switch is greyed out or says "Restricted setting" (Android 13+): Settings > Apps > Security Alarm >
     top-right **&#8942;** menu > **Allow restricted settings**, then try again.
6. Optional: tap **Allow override of Do Not Disturb** and enable Security Alarm, so the alarm also sounds in
   "Total silence"/DND.
7. Recommended so the phone doesn't stop the armed app in the background:
   Settings > Apps > Security Alarm > Battery > **Unrestricted** (Samsung: also "Never sleeping apps").

## Part C - Daily use
* **ARM ALARM** when you want protection. A quiet "Security alarm ARMED" notification appears (hidden on the lock screen).
* Press Home/Back or lock the phone - it stays armed.
* **Disarm** = DISARM button, swipe the app away in Recents, or "Close all". It never arms itself after a reboot.
* **Sound it**: PLAY button, or on the locked phone press **Volume Up** or **Volume Down** three times
   consecutively within 10 seconds, using the same direction.
* **Stop it**: unlock the phone, open the app, tap **STOP**.

## Part D - Test the lock-screen trigger
1. ARM the alarm, lock the phone. 2. Press **Volume Up** three times within 10 seconds, or press **Volume Down** three times within 10 seconds.
3. The alarm should sound within a second. If not, unlock and look at **Troubleshooting** at the bottom of the app:
   it lists what the service noticed, including the volume press count and trigger result.

## Honest limitations
* The lock-screen trigger works through an Accessibility Service and depends on your phone brand / Android version.
   Some devices may reserve volume-key handling for system functions even when the service is enabled. Test it (Part D).
* An alarm cannot beat a powered-off phone, a killed process, or hardware volume limiters some Bluetooth devices apply
  (Force phone speaker helps). Android "Total silence" DND blocks alarms unless DND access is granted (Part B-6).
* Loud sounds can damage hearing at close range.

## Privacy
No internet permission, no analytics. The accessibility service is inert unless the alarm is armed, only inspects the
label of tapped buttons, and never records digits other than "1". Troubleshooting entries live in memory only.
