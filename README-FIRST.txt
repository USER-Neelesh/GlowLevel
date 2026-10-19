GlowLevel Android project source
================================

This ZIP contains project source code, not a prebuilt APK.

Build and install:
1. On a Windows/Mac/Linux computer, install Android Studio from:
   https://developer.android.com/studio
2. Extract this ZIP.
3. In Android Studio choose Open and select the extracted GlowLevel folder
   (the folder containing settings.gradle.kts).
4. Allow Gradle sync to finish and install any SDK components it requests.
5. Connect your Android phone by USB. Enable Developer options > USB debugging.
6. In Android Studio, select your phone as the run target and press the green Run button.
7. Open GlowLevel on the phone. Grant notification permission if requested.
8. Tap Configure Overlay Permission and allow Display over other apps.
9. Turn on Floating Side Panel.

Important:
- A physical phone is needed to test the actual flashlight.
- Variable torch intensity requires Android 13+ and hardware that supports it.
  Unsupported phones will offer ON/OFF only.
- This source has been assembled from the supplied Markdown bundle but has not
  been compiled or tested on your particular phone. If Gradle sync/build reports
  an error, send a screenshot or copy the first error message.
