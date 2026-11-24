# CLAUDE.md

Mindmill for Android: the phone companion to Mindmill on the Mac (Kotlin, Jetpack Compose, Material 3). The Mac is
the server; the contract is `docs/remote-api.md` in the Mindmill repo (github.com/MadsLangkilde/MindMill; locally `~/projects/claude_plugins/kb`). Protocol changes go
there first and must keep the iOS app (`~/projects/mindmill-ios`) working too. Each app keeps its own logic.

## Build and test

- `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`, then `./gradlew assembleDebug testDebugUnitTest`.
- Deploy to a connected phone: `./gradlew installDebug`.
- Published to github.com/MadsLangkilde/mindmill-android only as a cleaned copy (the same rules as the Mindmill repo);
  never add it as this repo's remote or push from here. Commit only when asked. Never write test data to `~/knowledgebase` or the user's repos.

## Rules

- No API keys on the phone; the device token stays encrypted with an Android Keystore key.
- Nothing is cut off or squeezed at any width. Chip and button rows wrap whole, never scroll sideways. Lists and
  empty states are top-aligned.
- Nothing of ours floats over content: messages go in the layout, never toasts or snackbars over content.
- The launcher icon is the Mindmill logo from `art/mindmill-logo.svg` in the kb repo, as vector drawables
  (`res/drawable/ic_launcher_*.xml`); its navy is the brand colour (`ui/Theme.kt`).
- No comments that restate code.
