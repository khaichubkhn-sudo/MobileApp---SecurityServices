# Security Services (Android)

A loud, fixed-volume alarm that arms itself whenever the app is open. Plays an audio file you choose
(wav, mp3, m4a, flac, ogg, ...) from the PLAY button. While armed, holding Volume Down for 1 second runs
the action you selected - play the alarm + capture one photo and email it, start microphone recording, send the GPS location by SMS, call the
first number, or capture one photo and email it - whether the phone is locked, unlocked or the screen is off.

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
   folder if the phone refuses that location). Once armed, **hold Volume Down for 1 second** to start recording,
   and tap **STOP RECORDING** in the app to finish the current AAC file (`.aac`; the notification shows "RECORDING VOICE"
   while it runs). A recording - voice or video - also stops automatically once its file reaches 1 GB, or when the
   app is closed. When a recording
   finishes it is also emailed to the **Send photo / recording to** address (see item 9). The recording is AAC,
   split into numbered parts of at most 16 MB each so every email stays under the 25 MB limit most providers
   enforce; concatenate the parts in number order to replay the whole recording, and the parts already received
   still play even if a later part is missing. The total emailed for one recording is capped at 500 MB.

9. To use **Photo by email** / **audio recording email**, select **Capture one photo and email it** (or
   **Start microphone voice recording**) in the Volume Down action section. The email settings are **always**
   shown in the same section: enter up to 10 recipient addresses separated by `;`
   (e.g. `a@example.com;b@example.com`), your own email address (the account emails are sent
   from), an email app password, the SMTP server and port, and choose SSL/TLS or STARTTLS. The same account and
   recipients are used for both the photo and the recordings. Allow the camera permission when Android asks.
   Example (Gmail): turn on 2-Step Verification, create an **App password** (Google Account > Security >
   App passwords), paste it as the password, and use server `smtp.gmail.com` with port `465` and STARTTLS off.
   No server of ours is involved - the email is sent straight from the phone through your own account, so the
   phone needs internet and the SMTP server must be reachable. The app checks for an internet connection for up
   to **2 minutes**: if a connection is available, the photo/recording is sent; if there is still no connection
   after 2 minutes, it is **not** sent and it is **not** sent later when internet returns. The result of the last
   send is shown as a notification line on the app's main screen.

## Part C - Daily use
* **The app is always armed while it is open** - there is no arm/disarm button. A quiet "Security Services ARMED"
  notification appears (hidden on the lock screen). It arms itself when you open it and **re-arms after every
  settings change**, so every setting applies to a fresh service.
* Press Home/Back or lock the phone - it stays armed.
* It switches off only if you swipe the app away in Recents / use "Close all" (reopen the app to re-arm).
  It never arms itself after a reboot.
* **Volumes**: the app applies its own **Alarm volume** only while an action is running - i.e. while the alarm is
  sounding, a voice recording is going, or a video is being captured. **As soon as that action stops, the alarm, media and ringer volumes go back
  to exactly the levels they had before it started, and while nothing is running the volume is yours to adjust.**
  Closing the app (swipe away in Recents / "Close all") stops any running action, so the volumes are handed back
  then too. The pre-action levels are also stored on the device, so they are still restored correctly even if Android
  kills the app while an action was running. While a recording or video with sound runs, the microphone is kept
  un-muted at full level no matter what the phone's mute switch or volume settings were before (the previous
  microphone state is put back afterwards).
* **Sound or record**: the selected Volume Down action starts after a 1-second hold (works locked, unlocked, or
   with the screen off; Volume Up is ignored). With recording selected, the hold starts a voice recording and the
   ongoing notification changes to "RECORDING VOICE". Android requires a foreground-service notification while recording.
* **Stop it**: tap **STOP RECORDING** to finish and save the voice file, or **STOP** to silence a sounding alarm.
* **Location SMS**: select **Send current GPS location by SMS after the trigger**, enter phone numbers separated
   by semicolons on a single line (the box holds up to 100 characters - at least five numbers - and scrolls
   sideways when the text is longer than the box), and optionally enter message content to place before the GPS data.
   Grant Location and SMS
   permissions when asked. The app waits for one valid location (up to 30 seconds), then sends one Google Maps link
   to each distinct number, with a maximum of 10 SMS messages per Volume Down hold - the allowance is counted per
   detected hold, not per app start, so holding Volume Down again sends to the list again (up to 10 more messages
   each time) with no need to reopen the app. The link and sending result are shown temporarily in Troubleshooting,
   and when the cap stops a send a red **SMS notification** banner appears at the top of the app stating that the
   **GPS SMS maximum limit** was reached, how many messages were sent and how many numbers were not; it disappears
   as soon as the next Volume Down hold starts a fresh allowance. Android does not
   allow an app to silently switch on the system Location setting, so Location must already be enabled on the phone.
* **Phone call**: select **Call the first phone number** and enter one or more semicolon-separated numbers. After
   the one-second Volume Down hold, the app calls the first number directly from its armed foreground service, only
   when no call is already in progress, including while the screen is locked. Grant the phone-call permission when
   Android asks.
* **Photo / recording by email**: select **Play the alarm sound + capture one photo and email it** for the combined alarm + photo action, **Capture one photo and email it** for photos only, or **Start microphone
   voice recording** for audio; the email settings are always shown in the same section. After the one-second
   Volume Down hold the app takes one photo with the main (back) camera and emails it as a JPEG attachment (at
   most one photo every 2 seconds). When a recording finishes it is emailed to the same recipients as numbered parts
   of at most 16 MB each; the total emailed for one recording is capped at 500 MB, and a recording (voice or video)
   is stopped automatically once it reaches 1 GB. Grant camera permission when Android asks. The app waits up to 2 minutes for internet:
   if none is available in that window the file is not sent and is not sent later. The last sent/failed result is
   shown as a notification on the app's main screen and in Troubleshooting.
* **Alarm flashlight**: when **Play the alarm sound + capture one photo and email it** is selected, the phone's camera flashlight blinks while the
   alarm sounds and switches off when the alarm stops. Grant camera permission when Android asks.

## Part D - Test the Volume Down trigger
1. Open the app (it arms itself), then press and hold **Volume Down** for 1 second (no need to lock the phone first -
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
     is still not enough (it must be a 1-second hold).
* An alarm cannot beat a powered-off phone, a killed process, or hardware volume limiters some Bluetooth devices apply
  (Force phone speaker helps). Android "Total silence" DND blocks alarms unless DND access is granted (Part B-6).
* Loud sounds can damage hearing at close range.

## Privacy
No analytics. The app asks for internet access only for the optional **Photo/recording by email** feature, which
sends the captured photo and any finished voice recording straight from the phone through your own email (SMTP)
account - nothing is routed through any third-party server, and the email password is kept only in the app's
private settings on the device. The
accessibility service is inert unless the alarm is armed, only inspects the label of tapped buttons, and never
records digits other than "1". Troubleshooting entries live in memory only.
