# Personal Dashboard Android

Android companion for the local Personal Dashboard.

The mobile collector reuses the ActivityWatch Android data engine and session
parsing approach under MPL-2.0, while the mobile interface and device-purpose
split are Personal Dashboard code. A phone keeps sessions locally, mirrors
them into its local ActivityWatch store, and can upload them to the PC service
when a paired server is configured.

The app supports two independent labels:

- device role: `work` or `personal`;
- session purpose: `work` or `personal`, changed from the app and applied at
  session boundaries. This lets the same app, such as Douyin, appear in both
  work and personal summaries.

Build output:

`output/PersonalDashboard-0.1.2.apk` (Android 9+, ARM64 and x86_64)

The PC sync service is in `tools/mobile_service.py`. Data sync requires pairing.
Updates are independent and use public GitHub Releases in
`utlastr/personal-dashboard-android`; no PC connection or GitHub login is needed.

Run `tools/start-mobile-service.ps1` on the PC. In the phone settings enter
`http://192.168.1.244:5668` and the code in
`.local/mobile-service/pairing-token.txt`. Use a trusted LAN. The PC address
may change. Android usage-access permission must be enabled manually.

Collection runs on opening the app and with Android JobScheduler (minimum
15 minutes, subject to Android battery restrictions). Closed sessions only
are committed. The current purpose applies to future intervals; this version
does not automatically distinguish Douyin videos from Douyin mini games.
Switch purpose before starting work or leisure. The phone shows local data
and cached summaries of the other phones; the PC receives phone buckets in
its ActivityWatch dashboard. PC activity history is not downloaded to phones.

Updates are checked daily by a persisted network job and on launch when due.
Android power management or force stopping can delay background execution.
The homepage and settings offer a forced manual check. New APKs download
automatically and are checked for SHA-256, package, version and signing identity.
Android 12+ self-updates request installation without user action. Android may
still require installation permission or confirmation; the homepage shows the
required action. Android 9-11 need confirmation. No installer screen opens
unsolicited from a background job. Grant this app permission to install updates
once when prompted. Initial upgrade from 0.1.0/0.1.1 must be installed manually.

To publish, increment AndroidManifest.xml and Updates.VERSION/NAME, build with
the same signing key, test, and run `tools/publish-release.ps1`.
The script pushes source and creates a GitHub release containing the APK,
manifest and matching source archive. A source commit alone is not a release.
Keep `.local/personal-dashboard-release.jks` private and backed up.

Build: `powershell -ExecutionPolicy Bypass -File tools/build-apk.ps1`.
Run `tools/fetch-engine.ps1` first to download and verify the pinned native engine.
The source archive includes the matching bundled dashboard assets for rebuilding
when the PC frontend checkout is absent.

Version 0.1.1 fixes daily Android totals: rawQuery binds selectionArgs as TEXT,
so scalar MAX(start, ?) incorrectly selected the midnight parameter for every
session. The query now casts parameters to INTEGER and clips both day bounds.
Regression tests cover multiple sessions, midnight, future timestamps and a
private replay of the connected phone's records. No usage data reset is needed.

USB diagnostics can use `adb -s DEVICE reverse tcp:5668 tcp:5668`; then the
phone service address is `http://127.0.0.1:5668` with the same pairing token.
This tunnel lasts only while the relevant ADB connection remains available.
For Wi-Fi use the PC LAN address and the scoped firewall rule. A continuously
available HTTPS server or private network is needed for unattended remote sync;
this release does not deploy either.
Build tool paths are recorded in that script. Native libraries come from the
official ActivityWatch Android v0.14.3 release; upstream source is checked
out under vendor/aw-android (tag v0.14.3). The source archive excludes private
keys, pairing tokens and personal records.
