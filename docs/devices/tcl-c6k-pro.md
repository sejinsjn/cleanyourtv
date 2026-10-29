# TCL C6K Pro (G08): install-only launcher build and Home-key behavior

Owner-reported device: TCL C6K Pro, model `Smart TV Pro`, device `G08`, Android 14 / SDK 34.
The installed apps selected for the dock are IPTV (`ar.tvplayer.tv`), Stremio
(`com.stremio.one`), YouTube (`com.google.android.youtube.tv`), Netflix (`com.netflix.ninja`),
Spotify (`com.spotify.tv.android`) and YouTube Music (`com.google.android.youtube.tvmusic`).

The launcher APK installed and opened, and its dock order and D-pad navigation worked. After a
reboot, however, the remote's physical Home button still opened
`com.google.android.apps.tv.launcherx/.home.HomeActivity`, even though
`cmd package set-home-activity` reported success and the HOME role holder was
`com.gathin.tvhome`. The stock launcher is deliberately left enabled. This is an observed firmware
behavior, not a verified launcher takeover.

The saved Wi-Fi network is owned by `com.google.android.tungsten.setupwraith`. Do not disable
setupwraith: Android can delete its saved network, taking Wi-Fi and ADB away.

## Build and install without changing Home

On Windows, from the repository root, with ADB enabled and approved on the TV:

```powershell
powershell -ExecutionPolicy Bypass -File launcher\build-and-install-no-home.ps1 -TvHost <tv ip>
```

Only if `restore_key_services` is opted in and should be granted:

```powershell
powershell -ExecutionPolicy Bypass -File launcher\build-and-install-no-home.ps1 -TvHost <tv ip> -RestoreKeyServices
```

On macOS or Linux:

```sh
TV_HOST=<tv ip> bash launcher/build-and-install-no-home.sh
```

Add `RESTORE_KEY_SERVICES=1` before `TV_HOST` only for the same explicit opt-in:

```sh
RESTORE_KEY_SERVICES=1 TV_HOST=<tv ip> bash launcher/build-and-install-no-home.sh
```

These scripts build and install/update `com.gathin.tvhome` only. They do not call
`set-home-activity`, change the HOME role, enable or disable the stock launcher, or enable an
accessibility service. Add `-RestoreKeyServices` on Windows or set `RESTORE_KEY_SERVICES=1` for
Bash only if `restore_key_services` is explicitly enabled in `config.xml` or a theme; this grants
`WRITE_SECURE_SETTINGS` but still does not enable an accessibility service. Do not use the standard
`launcher/build-and-install.ps1` or `launcher/build-and-install.sh` for this device while takeover
remains unverified: those scripts change Home and disable the recognized stock launcher.

## Route the physical Home button

The current HomeKeyService handles `KEYCODE_ALL_APPS`, which is not the same as a standard
`KEYCODE_HOME`. The G08's Google TV launcher continues to receive the physical Home press despite
the stored HOME role. The repository already has a fallback for this case: `stock_home_redirect`
lets HomeKeyService handle a short physical `KEYCODE_HOME` directly when the service receives it,
while leaving long presses alone; its window-event redirect remains the fallback if TCL launches
the stock Home separately.
The C6K Pro build config points this setting to `com.google.android.apps.tv.launcherx`.

The helper is not enabled by the installer. Before any helper test, confirm ADB is available and
the stock launcher remains enabled. Then, only with the owner's approval, enable
CleanYourTV's **HomeKeyService** in the TV's Accessibility settings. The service is declared as a
key-filtering accessibility service. Enabling it through ADB alone did not bind it on this unit;
enabling only **Home button** in TV Accessibility settings did. ADB then reported
`accessibility_enabled=1` and HomeKeyService under `Bound services`. Pressing the physical Home
button from YouTube opened CleanYourTV, and the foreground activity was
`com.gathin.tvhome/.MainActivity`. After reboot, however, Android retained HomeKeyService in
`enabled_accessibility_services` but reset `accessibility_enabled` to `0`; the service was no
longer bound, and physical Home opened Google TV. For the opt-in test, the Home button service was
enabled again in TV Accessibility with `restore_key_services` on and `WRITE_SECURE_SETTINGS`
granted. After reboot, ADB confirmed the same reset. Starting CleanYourTV's `MainActivity` once
then changed `accessibility_enabled` to `1` and bound HomeKeyService; pressing the real Home button
from YouTube returned to CleanYourTV. The HOME resolver still named Google TV, so the app did not
start itself after reboot: this test verifies repair after CleanYourTV is started, not automatic
boot launch. A separate opt-in `launch_home_after_boot` path is now implemented: with both
`restore_key_services` and `launch_home_after_boot` enabled, the boot receiver repairs the previously
enabled HomeKeyService and asks it to open CleanYourTV directly, without another HOME handoff through
Google TV. The existing `stock_home_redirect` remains as a fallback. It does not change the Home role
or disable the stock launcher. This automatic path
was tested on this model. The first reboot still showed Google TV because TCL had reset `AUTO_START`
to `ignore`; its boot manager logged that it blocked both HomeKeyService binding and the launcher
boot receiver. The installer now saves the app-op with `appops write-settings`, following the TCL
behavior documented below. On a second reboot with the saved permission, CleanYourTV became the
foreground activity, `accessibility_enabled` was `1`, HomeKeyService was bound, and `AUTO_START`
remained `allow`. Google TV still starts first; the owner confirmed it is replaced by CleanYourTV
after a few seconds. The optimized boot path now opens CleanYourTV directly from HomeKeyService and
coalesces a stock-Home redirect, avoiding an extra system HOME handoff. One ADB timeline measured
about 2.1 seconds from Google's Home activity start to CleanYourTV's activity start, with its first
window drawn about 5 seconds after Google's Home start; this is one run, not a multi-boot benchmark.
Later logs exposed a race: the boot dispatcher continued starting Google's `HomeActivity` after
CleanYourTV's first request, and that request arrived before Google's Home window finished drawing.
The launch path now waits 1.8 seconds before its direct boot request. A short physical Home press is
handled directly by HomeKeyService when TCL delivers `KEYCODE_HOME`; it opens CleanYourTV 250 ms
after key-up and leaves long presses to the system. If TCL separately opens its stock Home, the
window-event fallback requests CleanYourTV immediately, then retries after 700 ms up to three times
while stock-Home window events continue. The boot redirect uses the same immediate first request and
bounded retries to reassert CleanYourTV after TCL finishes its own startup sequence. After the
updated build was installed, the owner reported that Google TV appeared on the first presses but
subsequent physical Home presses opened only CleanYourTV; the device log confirmed the direct Home
key handler ran. This is one test session, not a guarantee across all boots. On this build,
a cold reboot with Google TV enabled brought CleanYourTV to the foreground automatically; the
retained activity log showed Google's Home start at 16:31:28.918 and CleanYourTV at 16:31:37.016
(about 8.1 seconds later). CleanYourTV was still foreground when checked, and no further Google Home
launch appeared in the captured startup log. This is one cold-boot test, not a guarantee after every
long power-off. The HOME role remains CleanYourTV, and neither Google TV nor `setupwraith` was
disabled. Do not disable the stock launcher unless it is separately confirmed after reboot. A
successful `set-home-activity` response or HOME role holder alone is not proof.

To opt in, set both booleans to `true` in the active theme or corresponding `config.xml` values and
install with `-LaunchHomeAfterBoot` (Windows) or `LAUNCH_HOME_AFTER_BOOT=1` (Bash). That explicitly
grants `WRITE_SECURE_SETTINGS`, sets TCL's `AUTO_START` app-op and writes app-op settings to disk,
but does not enable the Accessibility service. Open CleanYourTV once and confirm HomeKeyService is
enabled and bound before rebooting. The receiver stays disabled unless both options and
`stock_home_redirect` are configured.

If you approve enabling the helper but do not want it to filter keys, a theme overlay can set
`"key_filter": false` first. That also disables the helper's mapped remote app/settings keys; the
stock-window redirect still uses accessibility window events. Decide whether that trade-off is
acceptable before enabling the service.

## Re-enable an app or restore the stock Home

To re-enable one app previously disabled for user 0, use the TV's Settings > Apps > show system
apps (if needed) > select the app > Enable, or, when ADB is available:

```powershell
tools\venv\Scripts\python.exe -I tools\tv.py 'pm enable --user 0 <package.name>'
```

For example, replace `<package.name>` with `com.netflix.ninja`. To restore packages disabled by a
debloat list, use the existing state-aware restore script with the **same list** used to disable
them; it leaves packages that were already disabled alone:

```powershell
powershell -ExecutionPolicy Bypass -File tools\restore.ps1 -TvHost <tv ip> `
  -Stock com.google.android.apps.tv.launcherx examples\<your-list>\debloat.txt
```

This launcher-only setup did not apply a debloat list. To return Home to Google TV, leave its
launcher enabled and select Google TV in Settings > Apps > Default apps > Home app (wording varies
by firmware). Confirm with the physical Home button and after reboot. If the setting is ignored,
do not disable the stock launcher; keep using the TV's app/settings route or the launcher picker.
Only remove CleanYourTV from Settings > Apps after Google TV has been verified as the working Home.

## Change the dock or wallpaper

The built-in dock order and six slots are in
[`launcher/app/src/main/res/values/config.xml`](../../launcher/app/src/main/res/values/config.xml).
Edit the `<string-array name="dock_apps">` package order and keep `<integer name="columns">6</integer>`,
then rebuild and install with the install-only script above. On the TV, the launcher's
**Reorder apps** menu is another way to move Home tiles.

For a wallpaper without rebuilding, create a theme folder with a `config.json` and `wallpaper.jpg`.
For example, `themes/my-c6k-wallpaper/config.json` can contain:

```json
{
  "wallpaper": true,
  "wallpaper_dim": 25
}
```

Put your own dark, 1920x1080 JPEG wallpaper in that folder, then push the theme when ADB is
available:

```powershell
powershell -ExecutionPolicy Bypass -File tools\theme.ps1 -TvHost <tv ip> themes\my-c6k-wallpaper
```

The theme guide documents image collections in `wallpapers\` and rotation settings. Use art you
have permission to use; do not commit personal or unlicensed wallpaper files.
