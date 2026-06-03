# F-Droid Readiness Plan

This plan turns PocketSync into a project that is practical to submit to the
official F-Droid repository, while keeping local development and verification
fully Docker-driven. No Android Studio, host Android SDK, host Gradle, host Go,
or host native toolchain should be required for a normal build.

## Policy Baseline

F-Droid inclusion requires the app and its dependencies to be Free/Libre and
Open Source Software, buildable from published source, and free of proprietary
tracking, advertising, or proprietary service SDK requirements.

Relevant upstream references:

- Inclusion policy: <https://f-droid.org/en/docs/Inclusion_Policy/>
- Inclusion how-to: <https://f-droid.org/en/docs/Inclusion_How-To/>
- Build metadata reference: <https://f-droid.org/en/docs/Build_Metadata_Reference/>
- Reproducible builds: <https://f-droid.org/en/docs/Reproducible_Builds/>
- Anti-features: <https://f-droid.org/en/docs/Anti-Features/>

Current PocketSync status:

- Original app code is MIT.
- Bundled third-party notices are present.
- AndroidX, Compose, sshj, BouncyCastle, Kotlin, and Gradle dependencies are
  from standard public repositories.
- No ads, analytics, Firebase, Google Play Services, or proprietary tracking
  SDKs are used.
- Main blocker: release APKs package prebuilt native tools and libraries
  (`rsync`, OpenSSH, shared libraries, and `tsnet-nc`) that are committed under
  `app/src/main/assets/native/arm64-v8a/` and `app/src/main/jniLibs/arm64-v8a/`.

## Non-Negotiable Build Rule

All project build and verification commands must run through local Docker
wrappers.

Required local command shape:

```bash
./scripts/docker-fdroid-test.sh
./scripts/docker-fdroid-build-native.sh
./scripts/docker-fdroid-build-apk.sh
./scripts/docker-fdroid-verify.sh
```

Host requirements should be limited to:

- Docker or a compatible container runtime
- Git
- POSIX shell

The official F-Droid build server will not literally run this local Docker
workflow. The Docker workflow is the local reproducibility and development
contract. The same source-build steps must also be expressible in F-Droid
metadata without relying on host-private caches, checked-in generated binaries,
or proprietary tools.

## Phase 1: Add An F-Droid Build Variant

Goal: separate the current sideload release from the F-Droid-compatible build.

Tasks:

- Add a Gradle product flavor or build type for F-Droid, for example
  `fdroidRelease`.
- Keep the package ID stable unless F-Droid review requires a suffix.
- Make the F-Droid variant fail fast if any native executable or shared library
  is sourced from checked-in prebuilt files.
- Move committed native binary assets out of the F-Droid source set.
- Keep native license text and notices in the F-Droid APK.
- Add a build-time generated native asset manifest that records exact source
  refs, build container image digest, toolchain versions, and output hashes.

Acceptance checks:

```bash
./scripts/docker-fdroid-build-apk.sh
find app/src/main/assets app/src/main/jniLibs -type f \
  | grep -E '\.(so|a)$|/rsync$|/ssh$|/tsnet-nc$' \
  && exit 1 || true
```

## Phase 2: Build `tsnet-nc` From Source In Docker

Goal: keep built-in Tailscale support while making the helper source-built and
auditable.

Tasks:

- Convert `scripts/build-tsnet-helper.sh` into an F-Droid-specific Docker build
  path that pins the Go image by digest.
- Vendor or otherwise lock Go module dependencies using reproducible module
  downloads.
- Add a Docker command that builds only from `native/tsnet-helper/go.mod` and
  `go.sum`, with no local host Go cache assumptions.
- Generate and store a license summary for Go modules as a build artifact.
- Confirm `tsnet-nc` does not download executable code at runtime.

Acceptance checks:

```bash
./scripts/docker-fdroid-build-native.sh --only tsnet-nc
./scripts/docker-fdroid-verify.sh --native tsnet-nc
```

## Phase 3: Replace Termux Prebuilt Native Packages

Goal: stop relying on downloaded Termux `.deb` binaries for the F-Droid path.

Preferred approach:

- Build `rsync`, OpenSSH client tools, OpenSSL, zstd, and required runtime
  libraries from source inside a pinned local Docker image.
- Use the Android NDK from a known, redistributable source.
- Pin every source tarball by version and SHA-256.
- Keep source tarball URLs and hashes in a single manifest, for example
  `native/fdroid-native-sources.lock`.
- Build only `arm64-v8a` unless the app expands ABI support.
- Strip or preserve symbols deterministically.
- Stage outputs into a generated directory, not committed source paths.

Fallback approach, only if source-building the full native stack is too slow:

- Document exactly why each prebuilt source is allowed.
- Prefer Debian main or another F-Droid-accepted trusted source when policy
  permits it.
- Expect review friction if using Termux prebuilt packages, even if FLOSS.

Acceptance checks:

```bash
./scripts/docker-fdroid-build-native.sh --from-source
./scripts/docker-fdroid-verify.sh --native rsync
./scripts/docker-fdroid-verify.sh --native ssh
```

## Phase 4: Remove Committed Generated Binaries From The F-Droid Path

Goal: make scanner results boring.

Tasks:

- Keep current prebuilt binary assets only for sideload releases if desired, or
  move them to a separate release-assets branch/artifact process.
- Ensure the F-Droid variant ignores:
  - `app/src/main/assets/native/arm64-v8a/rsync`
  - `app/src/main/assets/native/arm64-v8a/ssh`
  - `app/src/main/assets/native/arm64-v8a/lib/`
  - `app/src/main/jniLibs/arm64-v8a/*.so`
- Add a scanner script that fails when ELF files are present in tracked source
  directories used by F-Droid.
- Keep license and notice text as source files.

Acceptance checks:

```bash
./scripts/docker-fdroid-verify.sh --scan-source
git ls-files | xargs file | grep ELF && exit 1 || true
```

## Phase 5: F-Droid Metadata Draft

Goal: make submission straightforward.

Tasks:

- Add a draft metadata file under `metadata/com.ttv20.rsyncbackup.yml` or a
  documented `fdroiddata` snippet.
- Include:
  - App name: PocketSync
  - Summary: Android backups to your own server with rsync, SSH, and built-in
    Tailscale client
  - License: MIT for app code, with bundled GPL/BSD third-party components
  - SourceCode URL
  - IssueTracker URL
  - Changelog URL if added
  - Build command equivalent to the Docker source-build steps
- Decide anti-feature disclosures:
  - `NonFreeNet` may be discussed because Tailscale connects to an external
    coordination service, even though LAN-only backup works and Tailscale is
    optional.
  - `Tracking` should not apply unless code changes introduce telemetry.
  - All-files access is a permission disclosure, not automatically an
    anti-feature, but it must be explained clearly.

Acceptance checks:

```bash
./scripts/docker-fdroid-verify.sh --metadata
```

## Phase 6: F-Droid Flavor UX And Disclosure

Goal: reviewers and users should understand exactly what the app does.

Tasks:

- Add an in-app About screen with:
  - MIT app license
  - GPL text pointer for rsync
  - third-party notices pointer
  - statement that Tailscale is optional
  - statement that no root and no Android VPN app are required
- Add README/F-Droid text explaining:
  - all-files access is needed to back up shared storage
  - Tailscale auth keys are discarded after login
  - private SSH/Tailscale state is stored locally
  - restore is not included in the first release
- Confirm LAN-only profiles can be configured and run without Tailscale setup.

Acceptance checks:

```bash
./scripts/docker-fdroid-test.sh
```

## Phase 7: Reproducibility Work

Goal: make independent rebuilds match or differ only for understood reasons.

Tasks:

- Pin Docker images by digest in every F-Droid build script.
- Pin Android Gradle Plugin, Kotlin, Compose BOM, Maven dependencies, Go
  modules, native source tarballs, and NDK version.
- Avoid timestamps in generated native manifests, APK assets, and build output.
- Set deterministic file permissions and archive ordering for staged native
  files.
- Add SHA-256 output summaries for:
  - native binaries
  - staged native assets
  - unsigned APK
- Document expected non-reproducible fields if any remain.

Acceptance checks:

```bash
./scripts/docker-fdroid-build-apk.sh
sha256sum app/build/outputs/apk/fdroid/release/*.apk
./scripts/docker-fdroid-build-apk.sh
sha256sum app/build/outputs/apk/fdroid/release/*.apk
```

## Phase 8: Local Docker CI Contract

Goal: every F-Droid-readiness check has one local Docker entry point.

Required scripts:

- `scripts/docker-fdroid-test.sh`
- `scripts/docker-fdroid-build-native.sh`
- `scripts/docker-fdroid-build-apk.sh`
- `scripts/docker-fdroid-verify.sh`
- `scripts/docker-fdroid-clean.sh`

Each script must:

- Use a pinned Docker image or locally built image from a checked-in Dockerfile.
- Mount the repo read/write only where generated outputs are expected.
- Use repo-local caches such as `.gradle-cache`, `.android-sdk-cache`, and a
  native source cache.
- Avoid writing into host-global tool caches.
- Restore host file ownership after container execution.
- Exit non-zero on policy failures.

## Phase 9: Submission Sequence

Goal: submit only after the local Docker path is boring.

Tasks:

- Create a new tag after the F-Droid changes, for example `v0.2.0`.
- Confirm the tag includes the MIT license update and third-party notices.
- Run all local Docker F-Droid checks from a fresh clone.
- Draft the metadata merge request for `fdroiddata`.
- Include notes for reviewers about:
  - built-in Tailscale helper source build
  - optional Tailscale usage
  - native tool source build
  - all-files access
  - backup-only scope

Acceptance checks:

```bash
git clone <repo-url> pocketsync-fdroid-check
cd pocketsync-fdroid-check
./scripts/docker-fdroid-test.sh
./scripts/docker-fdroid-build-native.sh
./scripts/docker-fdroid-build-apk.sh
./scripts/docker-fdroid-verify.sh
```

## Done Criteria

PocketSync is F-Droid-ready when:

- A fresh clone can build the F-Droid APK using only local Docker scripts.
- The F-Droid APK contains no committed prebuilt executable/native binaries.
- Native tools are built from source or from a policy-acceptable trusted path.
- All dependency versions and source hashes are pinned.
- Third-party notices are present in source and APK assets.
- The F-Droid metadata draft builds successfully.
- The app can run LAN-only backups without Tailscale configuration.
- Optional built-in Tailscale behavior is documented clearly enough for
  reviewer anti-feature decisions.
