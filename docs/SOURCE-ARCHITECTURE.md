# Shared-source architecture / 共通ソース構成

## Decision

StateLink keeps its existing **30 exact targets**, Java 17/21/25, Gradle 8.5/8.8/9.7.1 and per-target
Loom/mapping contracts. The refactor changes maintained source/build structure, not gameplay,
configuration defaults, serialized data, database migrations or public class names.

The baseline is commit `d1d117fe3900a4ba3837954208e2130085c2fca7` (2.4.2). Source contents are preserved
byte for byte. The Mod version remains 2.4.2 deliberately: this is a structural migration with a
class/resource equivalence check, **not a replacement release**. Existing fixed JARs, tags and drafts
must not be overwritten. Verification outputs are separate migration candidates.

## Maintained structure

```text
src/main/java/             28 classes identical across every target
src/main/resources/        one Mod metadata template and one icon
src/compat/mc-*/java/       only distinct class implementations (146 variants)
src/compat/mc-*/resources/  six existing Mixin configurations
src/test/java/             1.18.2 primary unit suite
src/test/compat/ports/java/ API-independent configuration suite for the other targets
src/test/resources/        data-free schema fixture
tests/integration/java/   real-MySQL and legacy-artifact interoperability tests
tests/fixtures/            immutable legacy JARs and migration hash manifests
tests/tools/               build/source-selection/artifact/publication rejection tests
tools/rehearsal/java/      explicit rehearsal helper, excluded from normal test/build artifacts
source-layout.json        exact class and Mixin selection for each supported target
minecraft-versions.json   dependencies, Java, Gradle, metadata constraints and baseline
build.gradle              shared dependencies, source sets and target packaging
settings.gradle           shared plugin repositories and isolated target project selection
gradle/common.gradle      packaging, licenses and test/admission policy
gradle/wrapper/           one wrapper JAR, three distribution configurations
```

The original 1,946 Java copies become **174 distinct maintained implementations**. A compatibility
profile's `mc-*` name identifies the first target using that implementation, not an assumption that all
nearby versions are equivalent. `source-layout.json` is authoritative per class. New accessors may be
absent from old targets; those absences are preserved. Exactly one implementation of each selected
logical class is compiled. Unknown targets, duplicate choices and missing files fail closed.

Primary-only database/coordinator/executor improvements are **not** blindly copied to other ports.
Class-level variants retain these existing differences. Likewise Yarn names, 26.x official names,
Mixin descriptors, annotation-processor/refmap modes and all Loader/API constraints remain distinct.
Do not merge variants because version numbers look close.

## Build model

`./gradlew -Pminecraft_version=<target> ...` selects the target's existing JDK and Gradle distribution.
Its shared wrapper bytecode is checksum-checked; distribution URLs/checksums are retained. The two
explicit 1.18.2 wrapper options are retained separately from the family defaults.

The root settings prepare an owned generated project under `.gradle/statelink/targets/<target>`.
It contains a tiny plugin bootstrap and a projection of the selected compatibility files. Common
sources are read directly from `src/main/java`. These generated projections are **not maintained
per-version source copies** and never enter Git or a release. Root and Loom caches are separated by
target; project outputs go to `build/targets/<target>`. There is no shared mutable active-version file.

All real Gradle configuration is shared. The generated bootstrap supplies only the selected plugin
ID/version and repository path. Fabric Loader/API use `modImplementation` in the existing remapped
projects and `implementation` in the existing official-name projects, matching the old build files.

The IDE/default target, inventory baseline and CI baseline are all 1.18.2. Import the repository root;
run `./gradlew prepareSources` first. For another target, use the same `minecraft_version` project
property for its Gradle import. Python 3.10+ is required; `STATELINK_PYTHON` may select its executable.
Actual IDE completion and Minecraft runtime injection are separate checks, not implied by CLI import.

## Hikari-Tweaks and Stonecutter

We inspected [Hikari-Tweaks](https://github.com/HikariServerDev/Hikari-Tweaks): a shared `src`, centralized
properties/resources, `compat` boundaries and version-driven builds are useful here too. Its local
reference uses Stonecutter 0.9.7 and one Loom 1.17.19 build, with 1.18.2 as its active/VCS target.

Stonecutter was considered first. Its [node/build model](https://stonecutter.kikugie.dev/wiki/v2/guides/start/build-setup)
uses one Gradle build to configure version nodes. StateLink currently needs three Gradle distributions
and Loom from 1.2.8 through 1.18-SNAPSHOT. Forcing these into the reference's single wrapper/plugin
would require toolchain upgrades and new Mixin qualification, beyond a behavior-preserving relocation.
Multiple separate Stonecutter builds could be investigated later; this refactor does **not** claim
Stonecutter can never support the targets. The chosen alternative preserves the existing toolchains
and centralizes sources without introducing a large conditional preprocessor or upgrading dependencies.

## Migration and equivalence gates

`tests/fixtures/migration/2.4.2-source-manifest.json` records the baseline's exact source/resource hashes
and class sets for all targets. Tests prove that the selected bytes and missing classes match the old
implementation. This deliberately guards this structural migration; a later behavioral change must
review/update the baseline rather than silently weakening the test.

`tools/compare_artifacts.py` compares all class bytes, resource/entry sets, nested dependency payloads
and refmap mappings against fixed pre-migration JARs. It reports ZIP layout, JSON formatting/nested-JAR
order and the known diagnostic Loom manifest field separately. Changed bytecode, resource payload,
mapping namespace or refmap semantics is a failure. These checks do not prove actual Minecraft gameplay.

Original projects and their local run/world/log material were archived intact under the local staging
migration evidence. They are removed from the maintained tree and Git tracking. No production data was
used. The original Git commit and fixed 2.4.2 artifact remain the comparison reference.

## 日本語での要点

全30版のソースを単一の`src/`へ実際に移行しています。全版共通28クラスは`src/main/java`、
異なる実装146種類と6種類のMixin設定だけを`src/compat`に保持します。版別のソース一式は
残していません。DB・同期処理の版固有修正もそのまま残し、1.18.2の実装で一律上書きしません。

`source-layout.json`はクラス単位の選択表です。APIやMixinの実差に基づく選択であり、
`mc-*`ディレクトリ名だけで適用範囲を推測しません。単体試験・MySQL・rehearsalは分離し、
配布JARへ混ぜません。既存のMod ID、コマンド、設定名、スキーマ、データ形式は変更しません。

Stonecutterは第一候補として検討しましたが、今回は既存の3種類のGradleと各Loom版を維持する
共通ルートビルドを採用しました。Javaコードを書き換えず、全30版の実効ソース・リソースと
JARのclass／依存payloadを比較します。既存2.4.2の確定JAR・タグ・draftの置換公開は行いません。
