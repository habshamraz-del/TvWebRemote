# TV Web Remote

An Android TV app that opens any website and makes it usable with a TV remote.

- **TV menu**: every link, button and search box on the page, shown as big tiles
  grouped into Search and forms, Site menu, With pictures, Links and Page footer.
- **Page view**: the real website, with a highlight you move between clickable things.

## Remote controls

| Button            | What it does                                              |
|-------------------|-----------------------------------------------------------|
| Arrows            | Move between tiles (menu) or clickable things (page)       |
| OK                | Open the highlighted thing                                 |
| Hold OK, or MENU  | Show / hide the TV menu                                    |
| Back              | Close the TV menu, then go to the previous page, then home |

## Build without Android Studio (GitHub)

1. Make a free GitHub account and create a new **public** repository (e.g. `TvWebRemote`).
2. Upload everything in this folder, including the `.github` folder.
3. Open the repository's **Actions** tab and wait for "Build APK" to show a green check.
4. The APK is now at:
   `https://github.com/YOUR-USERNAME/TvWebRemote/releases/latest/download/TvWebRemote.apk`
5. On the TV, install **Downloader** (by AFTVnews) from the Play Store, type that address in,
   and install. Allow Downloader to install unknown apps when asked.

## Build and install with Android Studio

1. Install Android Studio and choose **File > Open**, then pick this folder.
   Let it sync; accept any offer to update Gradle or the Android plugin.
2. On the TV: Settings > Device Preferences > About, click **Build** 7 times to turn on
   Developer options, then enable **USB debugging** / **Network debugging**.
3. Connect: `adb connect <TV-IP-address>` (the IP is in the TV's network settings),
   then press **Run** in Android Studio with the TV selected.
   Or build an APK (Build > Build APK) and install it with `adb install app-debug.apk`.

You can also test on the Android Studio "Television" emulator.

## Files worth knowing

- `app/src/main/assets/tvify.js`: the script injected into every page. It finds the
  clickable things, builds the TV menu, and handles arrow-key movement. Most tweaking
  (layout, sections, colors) happens here.
- `BrowserActivity.kt`: hosts the WebView and routes remote buttons to the script.
  `AUTO_OPEN_TV_MENU` and `USER_AGENT` at the top change the default behavior.
- `MainActivity.kt`: the start screen with your saved sites.

## Known limits

- Google sign-in refuses to run inside embedded browsers, so sites that need a Google
  login may not let you sign in.
- DRM video services (Netflix, Disney+, etc.) won't play in a WebView.
- Sites with their own keyboard controls (games, some video players) may fight with the
  arrow keys. Use the TV menu there, or fullscreen video, where keys go to the player.
