# Changelog

Every HushPinterest release, newest first.

## Unreleased

Nothing has been released yet. This is the work toward 0.0.2.

* **Local builds:** Gradle now runs at low priority with two workers, a bounded heap and a short idle timeout to keep the desktop responsive. Build caching stays enabled.

* **Download history:** Hush-owned Android Downloads requests are checked across restarts and missed result broadcasts. The history shows current status and safe retry or reopen-pin guidance. Removing an entry keeps its file. Active cancellation stays in system Downloads.
* **Save recovery:** Android 9 records pending save locations before writing. After an interruption, Pending saves explains what to check without resuming or deleting files. Offered recovery access is released when safe, including after a completed save.
* **Media details:** Pin actions can show supplied dimensions and media type without inspecting files or guessing addresses. Recognized unsupported media gets an explanatory row. Download and storage refusals explain what failed.

* **Settings import:** Review names and old/new values before applying allowed switches. Undo restores the previous snapshot once, and expires after a later saved edit or restart. Returning a switch to its imported value doesn't restore Undo. Failed writes keep Undo after rollback, and failed imports still roll back atomically.
* **Settings writes:** Explicit saves persist their requested value even when an unsaved live value already matches. Failed writes restore the exact previous storage, including missing keys, and keep Undo available.

* **Diagnostic exports:** Summaries and successful-save feedback now show the real destination on Android 9 and newer versions. Unavailable storage keeps Copy quick report available. Redaction and export bounds remain enforced.

* **Settings search:** Interface summaries describe their affected controls and refresh boundaries. Reviewed search aliases cover common names in every supported language, while features absent from the installed patch set stay hidden. The interface screenshot now shows the updated summaries.

* **Setup guide:** About now has dismissible guidance on patch choices, settings backup and signing keys. It reuses Supported links and offers public password and data-export help.

* **Account help:** Use existing-account password recovery when an account was created through Google. The guide distinguishes Pinterest and Google passwords and explains that Facebook login is no longer available. Push notifications remain unverified.

* **Download guarantees:** The docs distinguish the initial address check before Android Downloads from Android 9's check of every redirect. Native execution on newer Android versions is preserved.

* **Development installs:** Device helpers now require an exclusive serial lease and verify the device identity. Installs refuse different signing keys and downgrades without removing apps, clearing accounts or granting every permission. Child checks keep caller-owned leases.
* **Patch outputs:** Concurrent device builds keep separate workspaces and return their verified APK paths. Explicit output collisions are refused. Failed patch reports and early verification errors clean their own generated files.
* **Analytics:** All response factories and hook sites are checked before the Firebase manifest edit. Malformed generic responses and missing late targets fail without changing analytics code. Runtime controls also check whether the patch was installed.
* **Validation:** Compiled manifest checks now cover metadata, account and push components, filters and query declarations. Allowances follow the selected patches and use the full merged input. Older release receipts retain their original schemas.
* **Development checks:** Device leases retain the full device identity across borrowed calls. Runtime bytecode verification uses the device's processor type. Analytics preflight rejects inaccessible owners and malformed hook bodies without editing code.
* **Build dependencies:** Commons Lang, HttpClient and Guava now resolve to reviewed versions across tooling and provided graphs. Strict checksums were refreshed, and vulnerable or missing resolutions fail the advisory gate.
* **Settings recovery:** A settings page that fails to load now shows Retry and Back. Retry preserves the requested page and loads one settings view. Back returns to Pinterest.
* **Release verification:** Local helpers now sign canonical checksums and authenticate detached signatures offline against an independently pinned key. Hosted bundle checks require that authentication before accepting hashes. Manual verification is documented for consumers that don't check signatures. Checksum tests use the actual signer's companion for cleanup and verify their signing processes have stopped.
* **APK validation:** Final feature hooks are checked against the actual patch selection and installed capabilities. Inserted calls must resolve in the merged app or Android's public API. Newer Android types and inherited members require reviewed version guards. Family controls and native fallback calls must be reachable, including a boolean wrapper's disabled path after copied values and known integer operations. Changing loop values become unknown so the check terminates. Static reachability doesn't prove every runtime path or call argument. Missing hooks, broken fallback paths and unresolved members stop local delivery.
* **Source references:** The reviewed source ledger now follows the renamed Mubelotix project and distinguishes Oyasumi's development and stable builds. Searches record their exact scope. No third-party Pinterest code was adopted or added to supported versions.
* **Android 9 saves:** Downloads now have a five-minute limit and a 256 MiB size limit. Partial responses and empty files fail. Storage errors preserve possibly completed files and explain when incomplete-file cleanup failed.
* **Updates:** Release compatibility checks every explicitly supported Pinterest version, including the Android 9 target. It accepts repeated Pinterest names and doesn't confuse previous bug fixes with previous version support. Old single-version caches no longer invent support. Release notes and installation steps open from Updates without automatic downloads.

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
