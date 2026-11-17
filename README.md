# Personal Dashboard Android

Android companion for the local Personal Dashboard.

The mobile collector reuses the ActivityWatch Android data engine and session
parsing approach under MPL-2.0, while the mobile interface and device identity
are Personal Dashboard code. A phone keeps sessions locally, mirrors
them into its local ActivityWatch store, and can upload them to the PC service
when a paired server is configured.

Each phone is either a work phone or a personal phone. This is a device
identity, not a session or application category. The phone shows only its own
daily or weekly usage total and app usage. Existing purpose labels are
ignored in summaries; historical records and sync IDs are preserved.
The PC overview displays the PC, work phone and personal phone side by side.

Build output:

`output/PersonalDashboard-0.1.7.apk` (Android 9+, ARM64 and x86_64)

The PC sync service is in `tools/mobile_service.py`. Data sync requires pairing.
Updates are independent and use public GitHub Releases in
`utlastr/personal-dashboard-android`; no PC connection or GitHub login is needed.

Run `tools/start-mobile-service.ps1` on the PC. In the phone settings enter
`http://192.168.1.244:5668` and the code in
`.local/mobile-service/pairing-token.txt`. Use a trusted LAN. The PC address
may change. Android usage-access permission must be enabled manually.

Collection runs on opening the app and with Android JobScheduler (minimum
15 minutes, subject to Android battery restrictions). Closed sessions only
are committed. Apps are not split into work and personal categories.
The phone shows local data; the PC receives phone buckets in
its ActivityWatch dashboard. PC activity history is not downloaded to phones.

Updates use the official GitHub Releases API for both metadata and asset
downloads, avoiding release website download gateways that can return HTTP 504.
Updates are checked daily by a persisted network job, independently of the screen.
Android power management or force stopping can delay background execution.
The device menu offers a forced manual check. New APKs download
automatically and are checked for SHA-256, package, version and signing identity.
Android 12+ self-updates request installation without user action. Android may
still require installation permission or confirmation; the device menu shows the
required action. Android 9-11 need confirmation. No installer screen opens
unsolicited from a background job. Grant this app permission to install updates
once when prompted. Initial upgrade from 0.1.0/0.1.1 must be installed manually.

The only homepage tabs are Day and Week. Dates can be selected or stepped;
weeks run Monday through Sunday in the phone's time zone. Tap an app for
24-hour duration or session-frequency distributions. Week also offers seven
daily duration bars. Sessions crossing hour/day boundaries are clipped to each
bin; repeated daylight-saving hours are combined without losing elapsed time.
System home launchers are excluded from new collection and historical reports.
App rows show their display names without package-name subtitles.

Daily app limits are enabled by default: Bilibili 22:00 inclusive to 23:00
exclusive; Douyin 15:30 inclusive to 17:30 exclusive, using phone local time.
The connected phone's `com.bilibili.app.in` variant is included.
The device menu shows actual capabilities and an enable/disable checkbox.
Ordinary phones require the user to enable the accessibility service once.
It observes foreground package names only and returns home outside the window,
including when the time window ends while an app is open; icons remain visible.
Without accessibility or hiding permission, the interface reports inactive
limits instead of claiming protection. This is voluntary self-control, not
tamper-proof parental control; system force-stop or permission revocation stops it.

If the app already holds device/profile owner or delegated package-access
authority, it uses DevicePolicyManager application hiding. It never attempts
device-owner provisioning, root access or resetting the phone. A profile owner
only controls its own Android profile. Each boundary schedules an alarm;
exact-alarm permission is requested through the device menu when needed.
Without exact-alarm access Android may delay icon changes. Boot, time-zone/time
changes, package replacement, new target installs and a persistent recovery job
reconcile state. Disabling restores only packages this app hid, not preexisting
administrator restrictions. Revoked management permission can prevent restoring
previously hidden packages and is reported explicitly.

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
