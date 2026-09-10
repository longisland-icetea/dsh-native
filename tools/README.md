# Gradle-free build

`./tools/build.sh` builds the APK without Gradle, which cannot run in this WSL2
VM (see `gradle.properties` for the measurements). It drives the same tools
Gradle orchestrates:

| step | tool |
|---|---|
| dependency graph | `tools/fetch-deps.py` (walks POMs, arbitrates versions, extracts aar resources) |
| resources | `aapt2 compile` + `aapt2 link`, over a merged tree from `tools/merge-res.py` |
| library R classes | `tools/gen-r.py` (the aars ship none; they are a build-time product) |
| Kotlin | `kotlinc` with the bundled Compose and serialization plugins |
| dexing | `d8` |
| packaging | `zip`, `zipalign`, `apksigner` |

## Installing

With a device on adb there is no download step: `adb install --no-incremental -r
build/dsh-native-debug.apk`. A local HTTP server was used earlier, before adb was
available, and is not part of this flow.

## Editing hazard

`tools/*.py` that patch sources use a `sub()` helper that raises when its anchor
is missing. `str.replace` silently no-ops on a missing anchor, which twice
produced a "successful" edit and a build of unchanged code. Keep using it.
