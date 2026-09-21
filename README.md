# Security Services (Android)

A loud, fixed-volume alarm that arms itself whenever the app is open. Plays an audio file you choose
(wav, mp3, m4a, flac, ogg, ...) from the PLAY button. While armed, holding Volume Down for 2 seconds either
plays the alarm or starts microphone recording, according to the selected action (locked, unlocked, or with
the screen off).

## Part A - Get the APK onto your phone

### Option 1: Android Studio (recommended)
1. Install Android Studio (free): https://developer.android.com/studio
2. Unzip the project archive. In Android Studio choose **File > Open** and select the `SecurityServices` folder.
3. Wait for "Gradle sync" to finish (first time: several minutes, needs internet).
   If it says a package is missing (e.g. "Android SDK Platform 34"), click the blue **Install** link and accept.
   If it offers to upgrade the Android Gradle Plugin, you can decline ("Remind me later").
4. **Build > Build Bundle(s) / APK(s) > Build APK(s)**. When done click **locate**:
   the file is `app/build/outputs/apk/debug/SecurityServices.apk`.
5. Send that APK to the phone (USB cable copy, Google Drive, e-mail to yourself, ...).

   *Alternative - install straight from Android Studio:* on the phone enable **Developer options**
   (Settings > About phone > tap "Build number" 7 times), then **USB debugging**; connect the cable,
   accept the prompt on the phone, choose your phone at the top of Android Studio and press the green **Run** button.

### Option 2: No Android Studio - build in the cloud with GitHub (free)
1. Create a free account at github.com and a new repository (private is fine).
2. Upload everything from the unzipped folder to it (including the hidden `.github` folder).
3. Open the **Actions** tab > **Build APK** > **Run workflow**. After ~5 minutes open the finished run and
   download the artifact **SecurityServices-apk** (a zip containing `SecurityServices.apk`).

### Install the APK on the phone
1. Open the APK file on the phone (Files app / Downloads).
2. Android asks to allow "Install unknown apps" for the app you opened it from (Files, Chrome, Drive...) -
   allow it, go back, tap **Install**.
3. If Google Play Protect warns about an unknown developer, choose **Install anyway** (you built it yourself).

## Part B - First-time setup on the phone (5 minutes)
1. Open **Security Services**. Allow **Notifications** when asked.
2. Tap **Choose sound file...** and pick your audio file. (If you choose none, the phone's default alarm tone is used.)
3. Set **Alarm volume** (100% = maximum). Leave "Force phone speaker" on for a loud, headphone-independent alarm.
4. Tap **Play** once to test - then **Stop**.
5. **Volume Down trigger (recommended):** the app opens **Accessibility settings** by itself the first time it
   starts while the trigger is off. Find **Security Services Volume Down trigger**
   (under Installed apps / Downloaded apps) > turn it **On** > confirm. (You can also tap
   **Open Accessibility settings** in the app at any time.)
   * If the switch is greyed out or says "Restricted setting" (Android 13+): Settings > Apps > Security Services >
     top-right **&#8942;** menu > **Allow restricted settings**, then try again.
   * The accessibility service gives the most precise Volume Down detection. This app also has a back-up
     detector that watches the system volume (no accessibility needed), so the trigger usually works even on
     phones whose accessibility key-handling is unreliable - but enabling it is still strongly recommended.
6. Optional: tap **Allow override of Do Not Disturb** and enable Security Services, so the alarm also sounds in
   "Total silence"/DND.
7. Recommended so the phone doesn't stop the armed app in the background:
   Settings > Apps > Security Services > Battery > **Unrestricted** (Samsung: also "Never sleeping apps").

8. To use voice recording, select **Start microphone voice recording** in the Volume Down action section.
   The app requests the microphone permission itself every time it opens - just allow the dialog when it
   appears (the status line shows "granted ✓" once done; there is no permission button any more). The app then
   arms itself **after** the dialog, so the armed service already runs with the microphone type (without it the
   phone refuses to record while the screen is locked; granting the permission while armed re-arms the service
   automatically). **START RECORDING now (test)** lets you verify the mic pipeline from the app.
   Choose a folder if needed; otherwise files are saved under **Recordings/Security Services** (with a fallback
   folder if the phone refuses that location). Once armed, **hold Volume Down for 2 seconds** to start recording,
   and tap **STOP RECORDING** in the app to finish the current M4A file (the notification shows "RECORDING VOICE"
   while it runs). Recording also stops when the file reaches 300 MB or the app is closed.

## Part C - Daily use
* **The app is always armed while it is open** - there is no arm/disarm button. A quiet "Security Services ARMED"
  notification appears (hidden on the lock screen). It arms itself when you open it and **re-arms after every
  settings change**, so every setting applies to a fresh service.
* Press Home/Back or lock the phone - it stays armed.
* It switches off only if you swipe the app away in Recents / use "Close all" (reopen the app to re-arm).
  It never arms itself after a reboot.
* **Volumes**: the app applies its own **Alarm volume** only while an action is running - i.e. while the alarm is
  sounding or a voice recording is going. **As soon as that action stops, the alarm, media and ringer volumes go back
  to exactly the levels they had before it started, and while nothing is running the volume is yours to adjust.**
  Closing the app (swipe away in Recents / "Close all") stops any running action, so the volumes are handed back
  then too. The pre-action levels are also stored on the device, so they are still restored correctly even if Android
  kills the app while an action was running.
* **Sound or record**: the selected Volume Down action starts after a 2-second hold (works locked, unlocked, or
   with the screen off; Volume Up is ignored). With recording selected, the hold starts a voice recording and the
   ongoing notification changes to "RECORDING VOICE". Android requires a foreground-service notification while recording.
* **Stop it**: tap **STOP RECORDING** to finish and save the voice file, or **STOP** to silence a sounding alarm.
* **Location SMS**: select **Send current GPS location by SMS after the trigger** and enter phone numbers separated
   by commas or new lines. Grant Location and SMS permissions when asked. The app waits for one valid location (up to
   30 seconds), then sends one Google Maps link to each distinct number. The link and sending result are shown
   temporarily in Troubleshooting. Android does not allow an app to silently switch on the system Location setting,
   so Location must already be enabled on the phone.

## Part D - Test the Volume Down trigger
1. Open the app (it arms itself), then press and hold **Volume Down** for 2 seconds (no need to lock the phone first -
   it works whether the phone is locked, unlocked, or the screen is off).
3. The alarm should sound within a second. If not, look at **Troubleshooting** at the bottom of the app:
   it lists what the app noticed, including the volume-press detection and the trigger result.

## Honest limitations
* The Volume Down trigger has two independent detectors, so it is quite robust:
  1. An Accessibility Service that reads the actual Volume Down key events (most precise). This depends on
     your phone brand / Android version - some devices reserve volume-key handling even when the service is enabled.
  2. A back-up detector that watches the system volume: holding Volume Down lowers it repeatedly, which the app
     sees even without accessibility. It needs the volume to be above its minimum for the keys to change anything,
     so if you keep the phone's media volume at zero, rely on the accessibility service above. A single short press
     is still not enough (it must be a 2-second hold).
* An alarm cannot beat a powered-off phone, a killed process, or hardware volume limiters some Bluetooth devices apply
  (Force phone speaker helps). Android "Total silence" DND blocks alarms unless DND access is granted (Part B-6).
* Loud sounds can damage hearing at close range.

## Privacy
No internet permission, no analytics. The accessibility service is inert unless the alarm is armed, only inspects the
label of tapped buttons, and never records digits other than "1". Troubleshooting entries live in memory only.
