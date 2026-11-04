# Development and release / 開発・検証・配布

## English

### Repository roles

```text
src/main/                 code/resources shared by every target
src/compat/               only distinct class/Mixin implementations
src/test/                 primary unit and port configuration suites
tests/integration/        explicitly invoked real-MySQL tests
tests/fixtures/           immutable legacy inputs and migration source hashes
source-layout.json        exact per-class selection for every target
minecraft-versions.json   all 30 dependencies/toolchains and baseline
build.gradle              shared target configuration
settings.gradle           root entrypoint and isolated project model
gradle/wrapper/           shared wrapper JAR and three preserved distributions
tools/                    common local/CI build, verify and publish entrypoints
build/targets/            generated per-target outputs
.gradle/statelink/        generated source projections/project caches
staging-results/          local-only evidence and pre-migration archives
```

Sources are centralized. See [SOURCE-ARCHITECTURE.md](SOURCE-ARCHITECTURE.md) for the class selection,
Stonecutter assessment, retained behavioral differences and exact migration equivalence gates.
The IDE/default/VCS regression baseline is 1.18.2; open the repository root and first run
`./gradlew prepareSources`. Use `-Pminecraft_version=<target>` to select another target. The selected
JDK/Gradle/Loom are preserved; no global mutable active-version file is used. Actual IDE completion
and full runtime/feature parity are not implied by CLI success.

### Local checks

Python 3.10+ and the Java versions in the inventory are required. Full-bundle inspection needs a Java 25 `javap` (set `JAVA_HOME` accordingly). Set `JAVA_17_HOME`, `JAVA_21_HOME`
and `JAVA_25_HOME` when they cannot be found automatically. No Java/Gradle dependency is upgraded by the tools.

```sh
python3 tools/project.py check
python3 -m unittest discover -s tests/tools -v
./gradlew -Pminecraft_version=1.18.2 test build
python3 tools/build.py --minecraft 1.18.2 --output .verification/primary
python3 tools/build.py --output .verification/all-ports
```

Default parallelism is one project, maximum two (`--jobs 2`); each Gradle run uses one worker,
768 MiB heap and two active processors. Test forks are bounded to one/512 MiB. Admission checks available
RAM/disk, subprocesses have deadlines, and no command removes an existing run/world directory.
Output directories must be new; old evidence is never silently overwritten. `--offline` is optional:
uncached mappings/dependencies are a failure, not a test pass.

`test` runs the primary unit suite or the other ports' shared configuration suite. Real-MySQL tests
are a separate `mysqlTest` task, so a unit-only run does not inflate its result with skipped DB tests.
The build tool rejects JUnit failures, errors, skips and absent reports. Minimum suite counts protect
against accidental omission, but do not replace reviewing the test bodies and reachability.

### Disposable MySQL only

Never point qualification at a production database, reuse production credentials, or mount production
worlds/configuration. Supply an independently created MySQL 8 service on loopback, a non-3306 port,
and a `statelink_test_*` database. Both the Python runner and Gradle task reject missing/unsafe settings.
The guard helps prevent mistakes; operators must still provide a genuinely disposable instance.

```sh
export STATELINK_TEST_MYSQL_HOST=127.0.0.1
export STATELINK_TEST_MYSQL_PORT=33306
export STATELINK_TEST_MYSQL_DATABASE=statelink_test_local
export STATELINK_TEST_MYSQL_USERNAME=root
export STATELINK_TEST_MYSQL_PASSWORD='test-instance-only'
python3 tools/build.py --minecraft 1.18.2 --mysql --output .verification/primary-full
```

The primary source tests include a **schema-only**, data-free 2.1.11 migration fixture.
Three exact historical JARs in `tests/fixtures/legacy` are immutable test **inputs**, not generated
build outputs or supported downloads. Their SHA-256/version/CRC are checked; they are never rebuilt,
relabeled, packaged in StateLink, or uploaded as release assets. They preserve old metadata and licenses. The explicit
`productionCopyRehearsal` helper in `tools/rehearsal/java` is not executed by normal tests, CI or release publication.

### Distribution and GitHub Actions

```sh
python3 tools/release.py assemble --input .verification/all-ports --output .verification/distribution
python3 tools/release.py verify --input .verification/distribution
```

For a release, all target qualifications must come from one **clean commit**, the real-MySQL suite
must pass, and `--source-commit`, `--require-clean`, and `--require-mysql` gates are required. A local
dirty-tree/unit-only distribution is only a development candidate and cannot pass the publication guard.
`tools/collect.sh` is a compatibility wrapper for `release.py assemble`, not an unverified JAR glob copier.

Verification runs on `main` push, PR and explicit dispatch. Feature-branch push does not duplicate PR
builds. Independent Minecraft jobs run in parallel (maximum four); MySQL runs separately and does not
serialize all ports. Fork approval settings are left unchanged. Build logs/JUnit remain separate CI
evidence, not release files.

Publication is a **separate manual StateLink Release workflow**: supply a new `vMAJOR.MINOR.PATCH` tag
and one successful StateLink Verify run ID on this repository's `main`. It checks out that run's exact
commit, reuses the verified artifact without rebuilding, checks the version/commit/test/target-set gates,
refuses existing tags/releases, and publishes a new draft only after re-downloading and verifying its bytes.
If upload or verification fails, the draft/tag remain for investigation; the tool does not delete or
overwrite history automatically.

The release contains only 30 target JARs, `SHA256SUMS`, and `manifest.json`. GitHub's tag source archives
provide the matching build/test source. Generated distribution JARs, worlds, credentials, caches, logs and local reports
are not source-controlled. Old root-build downloads remain available in historical commits; new downloads
use GitHub Releases.

### Inspection boundaries and remaining risks

The archive gate reads all ZIP entries and checks CRC, including nested runtime JARs. It checks exact
target/version/Java/Loader metadata, both license texts, declared libraries, Mixin configuration/class/refmap
presence and shape, own-class Java headers, the compiled main VERSION constant, production-source correspondence, and test/private payload exclusion.
Adversarial tests cover corrupt/truncated/duplicate/unsafe-path archives, wrong targets, stale qualification,
invented runtime PASS, bad provenance, existing releases, and failed post-upload verification.

These checks are **not** full JVM bytecode verification, semantic Mixin injection proof, actual gameplay,
database/World atomicity or production-account acceptance. The manifest explicitly records Minecraft runtime
as `NOT_RUN`; this pipeline cannot turn that into PASS. Full 2.3.x regression is on 1.18.2; other ports have
compile/configuration coverage only. Their existing Minecraft API-specific implementations remain separate.

Recovery defaults remain `last_checkpoint`, five seconds, with acknowledged rollback/duplication/loss risk.
No expiry/World-transaction guarantee is added. The 2.4.1 display-label fix remains; missing revision-zero data
is not fabricated. LGPL-3.0-only applies to StateLink; separately bundled libraries retain their own terms.

## 日本語

### 構成と対応版

ソースは`src/main`と必要なクラスだけの`src/compat`へ集約しました。Loom・Gradle・Java・マッピングと実装の選択は版ごとに維持します。回帰試験の基準は1.18.2、最新対応版は26.3です。対応版を増やす場合は、対象API・bytecode・Mixin契約を確認し、実装と`minecraft-versions.json`を同時に更新してください。

IDEではルートをGradleプロジェクトとして開き、`minecraft_version`で対象版を選びます。今回、実際のIDE補完や全版のMinecraft動作確認を実施したという意味ではありません。API差を無視したStonecutter移行は行いません。

`tests/fixtures/legacy`の過去版3 JARは移行試験に不可欠な固定入力です。現在のビルド出力とは区別し、元のハッシュ・版情報・ライセンスを変更せず保存します。Mod本体／Releaseには含めず、配布用にも使いません。

`src/test/java`は既存の単体試験、`tests/integration/java`はJDBC・migration試験、`src/test/compat/ports/java`は他29版の設定テスト、`tests/tools`はビルド・成果物・公開処理の異常系テストです。過去にコピーされていた未検証テストと旧スクリプトは、ローカルの`staging-results/2026-11-04/2.4.2/migration`へハッシュ付きで退避しました。削除して失ったわけではありません。

### 実行と安全条件

上記コマンドをローカルとCIで共通に使います。`test`は単体試験、`mysqlTest`は明示的に指定した隔離MySQL試験です。DB未起動によるskipを単体試験のPASSに混ぜません。MySQL実行にはloopback・3306以外のポート・`statelink_test_*`名の独立DBを必要とし、本番の接続情報は使いません。

既定は1プロジェクトずつ、最大2並列です。Gradle worker・heap・test forkを制限し、RAM／ディスク残量と実行期限も確認します。出力先は新規ディレクトリに限定し、既存のWorldやrunディレクトリを無条件削除する処理はありません。

### 配布と確認範囲

GitHub Actionsは対応表から版別ジョブを生成します。PRとfeature branch pushの重複を避け、fork由来の承認設定は変更しません。MySQLジョブは版別ビルドと独立して実行します。

Release公開は別の手動workflowです。同じrepoのmain上で成功した検証run、cleanなソースcommit、新しいtag、全対象版、MySQLを含むtest結果、実JARのhashを照合します。既存tag／Releaseは上書きしません。アップロード後の再ダウンロード検証に失敗した場合はdraftのまま止まり、自動削除や強行公開をしません。

配布物は版別JAR・`SHA256SUMS`・`manifest.json`のみです。検証ログ・JUnit・World・設定・キャッシュ・秘密情報は混ぜません。ソースコードと生成物は分離し、新しいJARはGitHub Releasesから取得します。

ZIP/CRC・同梱ライセンス・メタデータ・Mixin設定/refmap・class存在などの構造検査は、実MinecraftでのMixin注入やログイン成功の代わりにはなりません。manifestのruntimeは`NOT_RUN`を明示します。1.18.2以外の機能同等性／runtimeは未確認です。本番の配置・再起動・DB変更も行いません。

## Adding a target / 対応版の追加

1. Inspect the target's actual Java/API/mapping/Mixin contracts. Add its exact dependencies and
   metadata constraints to `minecraft-versions.json`; retain the 1.18.2 baseline/default unless a
   separate reviewed baseline migration is intended.
2. Extend class/Mixin membership in `source-layout.json` only where the implementation is actually
   compatible. Add a distinct class under `src/compat` for genuine API/behavior differences. Never
   copy a complete version tree or assume adjacent releases share an API.
3. If a new Gradle family is needed, add its official distribution configuration/checksum under
   `gradle/wrapper`. Do not silently change existing targets' toolchains.
4. Run `python3 tools/project.py check`, the tool tests, then the selected build and full matrix.
   Review existing-target source/JAR equivalence. A new target requires its own bytecode/Mixin and
   runtime qualification; successful compilation alone is not full support proof.
5. Update README's supported ranges and review the generated CI matrix, IDE/default target and
   release target set. Existing full 1.18.2 tests and every port's configuration tests remain active.

For this structural migration, compare preserved fixed artifacts without modifying them:

```sh
python3 tools/compare_artifacts.py --baseline /path/to/fixed-2.4.2 \
  --candidate .verification/all-ports --output .verification/equivalence.json
```

版を追加する場合も、実際のAPI差を確認し、選択表・依存関係・README・CI対象を同時に更新します。
既存版の実装を上書きせず、未知の版を既定実装へfallbackさせません。構造移行の候補JARは
確定2.4.2と別に保存し、既存タグ／Releaseを上書きしません。
