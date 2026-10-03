# Changelog

Every HushPinterest release, newest first.

## Unreleased

Nothing has been released yet. This is the work toward 0.0.2.

* **Development installs:** Device helpers now require an exclusive serial lease and verify the device identity. Installs refuse different signing keys and downgrades without removing apps, clearing accounts or granting every permission. Child checks keep caller-owned leases.
* **Patch outputs:** Concurrent device builds keep separate workspaces and return their verified APK paths. Explicit output collisions are refused. Failed patch reports and early verification errors clean their own generated files.
* **Analytics:** All response factories and hook sites are checked before the Firebase manifest edit. Malformed generic responses and missing late targets fail without changing analytics code. Runtime controls also check whether the patch was installed.

* **Feed:** An optional shopping filter removes shoppable pins, shopping stories and featured board placements. It starts off and reads both text and enum labels from Pinterest's models.
* **Privacy:** Disable analytics targets Pinterest usage uploads and AppsFlyer transport. Runtime switches and Pause restore those paths. Firebase Analytics is disabled at patch time and stays disabled until you patch without that patch. Strip link tracking removes known tracking parameters from copied and shared links without changing unknown or signed parameters.
* **Pin actions:** Download pins adds a native menu row for supplied original images and direct MP4 videos. Android 9 uses the save picker, and newer versions save in Downloads. Browser routing and Android's share sheet are separate choices. They start off.
* **Interface:** New switches hide the screenshot share menu, recent searches, comments and selected navigation, header and pin-menu controls. Email reminders and the Play Store update prompt have their own switches. They start off. The Play Store prompt hook only exists in 14.38.0.
* **Settings:** The new families appear in diagnostics and settings backups. Their controls and coverage labels are translated into all five supported languages. Current screenshots show the new groups. All 19 feature switches and restart-based Pause and Resume were exercised on Android 16.
* **Links:** The Supported links shortcut opens Android's app link settings. Manually selected Pinterest domains opened the patched app on Android 16.

* **Artwork:** A crimson Hush emblem with a push pin and a matching dark README hero bring Pinterest into the Hush family. The earlier artwork and its source are preserved with the selected masters.
* **Pinterest:** Hide ads is a new Feed switch, on by default. Promoted pins come out of the home feed, search, related pins and boards before Pinterest draws them, so they don't leave a gap. Four panels Pinterest only builds for an ad are folded away too.
* **Pinterest:** Hide AI-labeled pins is a new Feed switch, off unless you pick it when patching. It takes out the pins Pinterest itself labels as made or changed with AI, using the same label Pinterest shows on the pin. An AI image without that label still shows.
* **Pinterest:** HushPinterest settings opens from a long-press on Pinterest's icon or from Pinterest's App info page, with a switch for every patch, Pause, diagnostics and a settings backup. The screen is in German, Spanish, Indonesian, Brazilian Portuguese and Turkish as well as English.
* **Tooling:** The project starts from HushTelegram's build, shared extension library and release checks, retargeted at Pinterest 14.25.0 (version code 14258020). Every file carried over names HushTelegram in its header and in provenance.json. The catalog lists 17 Pinterest patches.
* **Tooling:** The bundle is built with Morphe patcher 1.15.0, so it needs Morphe Manager 1.33.0 or newer. Older Managers would refuse it and ask to be updated.
* **Pinterest:** Pinterest 14.38.0 is the new target, and 14.25.0 still patches for phones on Android 9. Every patch was checked against both builds.
