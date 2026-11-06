# StateLink

**Minecraft 1.18 – 1.21.11, 26.1 – 26.3 / Fabric** — by AtsukiMC

**Downloads:** use the matching JAR from [GitHub Releases](https://github.com/HikariServerDev/StateLink/releases). From 2.4.2, generated JARs are no longer committed to `build/`; each release contains the exact target set, `SHA256SUMS` and `manifest.json`. The license is **LGPL-3.0-only**, with both license texts included in each JAR. Build/CI/archive checks are not Minecraft runtime or production-login approval.

**ダウンロード:** [GitHub Releases](https://github.com/HikariServerDev/StateLink/releases)から対象Minecraft版のJARを取得してください。2.4.2以降は生成JARをGitの`build/`に登録せず、Releaseに版別JAR・`SHA256SUMS`・`manifest.json`をまとめます。ライセンスは**LGPL-3.0-only**に統一し、必要なライセンス文書をJARへ同梱します。ビルド・CI・成果物検査の成功は、実Minecraftや本番でのログイン成功を意味しません。

**2.4.1 login fix:** scoreboard/team prefixes and suffixes are local presentation, not profile identity. A different stored display label no longer rejects a login. UUID, account-name and profile-property validation remain enforced; no scoreboard or login identity is renamed.

**2.4.1のログイン修正:** チームのprefix/suffixなどの表示名はサーバーごとの装飾として扱い、保存時と異なっていても接続を拒否しません。UUID・アカウント名・プロフィール内容の検証は維持します。ログインIDやscoreboardは変更しません。

**2.4.0 recovery default:** `last_checkpoint`, enabled, with a 5-second delay and `acknowledgeUnsafeRecovery=true`. This automatically adopts the last committed DB checkpoint without a per-player command or freshness requirement. Progress may roll back and externalized World/container items may be duplicated or lost. It is **not safe/exactly-once recovery**. Explicit existing modes and acknowledgement values are preserved, not rewritten. Choose `safe`/`manual` if you do not accept this trade-off. Live-owner, fencing and domain-provenance protections remain; missing player data is not invented.

**2.4.0のrecovery初期値:** `last_checkpoint`・有効・待機5秒・`acknowledgeUnsafeRecovery=true`。プレイヤーごとのコマンドや鮮度条件なしで最後のDB保存を自動採用します。進行の巻戻りや、World/containerとの不一致によるアイテム重複・消失が起こり得るため、安全なexactly-once復旧ではありません。既存configの明示的なmode/承諾値は書き換えません。リスクを避ける場合は`safe`/`manual`を明示してください。live owner・fencing・domain provenanceの保護は維持します。

**Port scope / バージョン別の範囲:** 2.4.0 updates the version and recovery defaults in all 30 Minecraft ports while retaining each port's existing Minecraft-specific implementation. The full 2.3.x regression suite is maintained on 1.18.2; other ports are built and configuration-tested, not claimed to have identical runtime qualification or every 1.18.2-specific improvement. 全30版でバージョンと復旧初期値を更新しますが、各版の既存実装を維持します。1.18.2以外へ2.3.xの全改善を移植したという意味ではなく、全版の実Minecraft動作確認済みという意味でもありません。

**Source structure / ソース構成:** All 30 targets now use one shared `src/`, with class-level compatibility variants, one root Gradle entrypoint and the existing per-target toolchains. See [Source architecture](docs/SOURCE-ARCHITECTURE.md) and [Development](docs/DEVELOPMENT.md). 全30版のソースを単一の`src/`へ集約しました。版固有の実装・データ形式・機能は維持し、ルートの`./gradlew -Pminecraft_version=<版>`からビルドします。構造変更版を既存の固定2.4.2 artifactへ上書き公開することはありません。

---

## English

StateLink is a **server-side** Fabric mod for Minecraft (see supported versions below). It keeps each player's data identical across several servers that share one MySQL database, so a player who moves from one server to another keeps their items, experience, health and progress.

### What is synchronized

- Inventory, armor, off-hand and ender chest
- Health, hunger, experience and potion effects
- Position, dimension and rotation (optional)
- Game mode
- Advancements, statistics, recipe book and player profile (skin)

Each item can be switched on or off in the config file. Anything you turn off is left to each server.

### Requirements

- Minecraft 1.18–1.21.11 with Fabric Loader 0.17.3 or newer, or 26.1–26.3 with Loader 0.19.5 or newer. Exact tested build dependencies are listed in [minecraft-versions.json](minecraft-versions.json).
- The Fabric API that matches your Minecraft version
- Java 17 for Minecraft up to 1.20.4, Java 21 for 1.20.5 – 1.21.11, Java 25 for 26.x
- A MySQL 8 database that all your servers can reach
- Install the mod on **every** server that shares the database. The mod is not needed on the players' side.

### Installation

1. Download the JAR for your Minecraft version (`StateLink-2.4.2-mc<version>.jar`, for example `-mc1.21.4.jar`), check its SHA-256 against the release manifest, and put it into the `mods` folder of every server.
2. Start the server once to create `config/statelink.json`. Initialization is intentionally refused until the database fields are configured; this prevents a default writer from starting accidentally.
3. Stop the server and fill in the database `host`, `name`, `username` and `password`.
4. Give each server its own `serverId` (for example `s1`, `s2`).
5. Start all servers. Players are now synchronized.

### Upgrading from PlayerDataConnector

StateLink is the new name of PlayerDataConnector. To upgrade a server:

1. Stop the server.
2. Remove the `PlayerDataConnector` jar from the `mods` folder and put the `StateLink` jar in. The two must not be installed together; the server refuses to start if both are present.
3. Start the server.

On that first start StateLink finds the old files and takes them over by itself:

- `config/playerdataconnector.json` is renamed to `config/statelink.json`. Its contents are not changed.
- Database/table names stay the same. Startup validates the schema and adds required coordination metadata; always back up the database before upgrading.
- No file is deleted or overwritten. If both an old and a new config file exist with different settings, the server stops and asks you to keep one, instead of guessing.

**Upgrade all backends together.** Stop every server that writes this database, take a database backup, replace the mod on every backend, and only then start the new servers. StateLink 2.3.0 added nullable coordination metadata that PlayerDataConnector 2.2.9 does not recognize; rolling upgrades and mixed old/new writers are unsupported. Do not roll back by replacing only the JAR. A rollback requires all writers stopped and a coordinated database restore to a compatible backup.

StateLink 2.3.1 binds each immutable checkpoint to the dirty-change version observed at capture. If an idle-state comparison reports changed data, the snapshot is recaptured on the Minecraft server thread rather than saving the older comparison snapshot. Verified-state freshness is dated conservatively from capture time; delayed database work cannot make an old capture appear newer.

This applies to PlayerDataConnector 2.2.9. If your servers still run an older version such as 2.1.11, stop all of them and also follow the "Upgrading from 2.1.11" note below.

The console command is now `/statelink`. The old `/pdc` still works.

### Configuration

The config file is `config/statelink.json`. Restart the server after changing it. A typical example:

```json
{
  "enable": true,
  "database": {
    "host": "127.0.0.1",
    "port": 3306,
    "name": "playerdatasync",
    "username": "minecraft",
    "password": "change-me",
    "tableName": "player_data"
  },
  "sync": {
    "serverId": "s1",
    "leaseDurationMs": 15000,
    "leaseRenewIntervalMs": 3000,
    "inventory": true,
    "enderchest": true,
    "armor": true,
    "offhand": true,
    "health": true,
    "food": true,
    "experience": true,
    "effects": true,
    "position": false,
    "dimension": false,
    "rotation": false,
    "gamemode": true,
    "advancements": true,
    "statistics": true,
    "playerProfile": true,
    "recipeBook": true
  },
  "save": {
    "saveOnDisconnect": true,
    "saveIntervalSeconds": 10
  },
  "recovery": {
    "mode": "last_checkpoint",
    "lastCheckpointDelaySeconds": 5,
    "acknowledgeUnsafeRecovery": true,
    "maxVerifiedStateAgeSeconds": 120
  }
}
```

**General and database**

| Setting | Default | Meaning |
|---|---|---|
| `enable` | `true` | `false` turns the mod completely off (no database connection). |
| `database.host` / `port` | – / `3306` | Address of the MySQL server. |
| `database.name` | – | Name of the database. |
| `database.username` / `password` | – | Login for the database. Required. |
| `database.tableName` | `player_data` | Name of the table. Letters, numbers and `_` only. |
| `database.sslEnabled` | `false` | Use an encrypted connection to MySQL. |
| `database.maxConnections` | `10` | Maximum number of simultaneous database connections (2 or more). |
| `database.leaseRenewalPoolSize` | `3` (1.18.2) | Primary-port feature: bounded connections reserved for lease renewals. Other API ports do not yet have full 2.3.x feature parity. |
| `database.connectionTimeoutMs` | `2500` | Maximum wait to acquire a DB connection. |
| `database.socketTimeoutMs` | `2500` | JDBC socket read timeout. |
| `database.queryTimeoutMs` | `2500` | Per-statement timeout. |
| `database.lockWaitTimeoutMs` | `2000` | InnoDB row-lock wait limit. |

The server rejects timing combinations that cannot fit a bounded renewal attempt plus a fresh-connection ownership recheck inside the lease window. Existing configurations with a short lease (for example `5000` ms / `1500` ms) may therefore need an intentional timing review before startup; values are not silently rewritten. The example defaults use a `15000` ms lease and `3000` ms renewal interval. These bounds reduce avoidable disconnects during brief delays; they do not make arbitrary JVM pauses or long database outages safe.

**What to synchronize** (`sync`) — all default to `true` unless noted

| Setting | Meaning |
|---|---|
| `serverId` | A unique name for this server (for example `s1`). **Must differ on every server.** |
| `leaseDurationMs` | Default `15000` on 1.18.2, `5000` on the other API ports. Database ownership lease length; keep the configured value identical across backends. |
| `leaseRenewIntervalMs` | Default `3000` on 1.18.2, `1500` on the other API ports. Review lease/timeout headroom; values are not silently rewritten. |
| `inventory` | Inventory contents. If you turn this off, `armor`, `offhand` and `enderchest` must be off too. |
| `armor`, `offhand`, `enderchest` | Armor, off-hand item and ender chest. |
| `health`, `food`, `experience`, `effects` | Health, hunger, experience level and potion effects. |
| `position` | Position and dimension. Rotation follows it unless set separately with `rotation`. |
| `dimension`, `rotation` | Follow `position` when omitted; otherwise independently enable dimension and yaw/pitch synchronization. |
| `syncOnDimensionChange` | `true` — Triggers synchronization on dimension change; it does not enable the `dimension` data domain. |
| `gamemode` | Game mode and flying state. |
| `advancements`, `statistics`, `recipeBook` | Advancements, statistics and the recipe book. |
| `playerProfile` | Skin and profile properties. Login UUID/name remain validated; backend-local team/display labels are not applied or compared. |

Use the **same** values on every server.

**Saving** (`save`)

| Setting | Default | Meaning |
|---|---|---|
| `saveOnDisconnect` | `true` | Save when a player leaves. |
| `saveOnDeath` | `true` | Save when a player dies. |
| `saveOnDimensionChange` | `true` | Save when a player changes dimension. |
| `periodicSaveEnabled` | `true` | Also save regularly while players are online. |
| `saveIntervalSeconds` | `10` | Seconds between regular saves. `0` turns regular saving off. |
| `saveOnlyWhenDirty` | `true` | Only save players whose data has actually changed. |

With dirty-only saves enabled, an unchanged player does not rewrite the PlayerData row. A separate bounded verified-state attestation may refresh recovery evidence after a full enabled-domain comparison; this does not advance the PlayerData revision or checkpoint timestamp. Passive elapsed statistics (`play_time`, `total_world_time`, `time_since_death`, `time_since_rest`) alone do not trigger a save. Their current values are retained on the next meaningful or final save, so a crash can lose the elapsed-time increment since the last data save.

**Crash recovery** (`recovery`)

| Setting | Default | Meaning |
|---|---|---|
| `mode` | `last_checkpoint` | `manual`: operator reconciliation. `safe`: committed-receipt proof only. `automatic`: acknowledged freshness-bounded adoption. `last_checkpoint`: adopts the last checkpoint without a freshness bound; rollback/duplication/loss risk is accepted by default. |
| `lastCheckpointDelaySeconds` | `5` | Wait from the later of lease expiry and recovery marking before adoption. |
| `acknowledgeUnsafeRecovery` | `true` | Availability-first default. Explicit `false` with `last_checkpoint` still refuses startup. |
| `acknowledgePotentialRollbackOrDuplication` | `false` | Must be `true` to use `automatic`. It confirms you accept possible item loss or duplication. |
| `maxCheckpointAgeSeconds` | `30` | In `automatic` mode, the checkpoint must be fresh now or at the earliest DB-recorded uncertainty boundary (recovery marker / lease expiry). Downtime alone does not age out a checkpoint that was fresh at that boundary. |
| `maxVerifiedStateAgeSeconds` | `120` | The same freshness rule applies to an attestation matching the exact checkpoint, owner/session/fence, revision, provenance and enabled domains. It does not make World side effects exactly-once. |

Since 2.3.3, the freshness-bounded `automatic` mode (not `last_checkpoint`) distinguishes time spent offline from data already stale before authority loss. A late scanner cannot move the boundary forward. Missing history does not manufacture evidence; the existing fresh-now policy remains the fallback. Checkpoints/attestations already stale at failure still require an explicit administrator decision in that mode. Revision-zero abandoned logins with no checkpoint are reported as `MISSING_CHECKPOINT`, not retried as generic conflicts. No empty inventory or replacement PlayerData is invented.

An unrecoverable login now states whether its checkpoint is stale, missing or its attempt budget exhausted, instead of waiting and suggesting that repeated reconnects will repair it. Real lease expiry/fencing still quarantines and disconnects the player. Check the `Lease lost / fencing detected` log for lease age, safety budget and renewal pool/queue state; do not disable fencing to avoid kicks. A configured 5-second lease is not automatically rewritten: modern validation requires sufficient DB-timeout/revalidation headroom (the example uses 15 seconds / 3-second renewals).

**Upgrading from 2.1.11** — the `migration` section is only needed once during the upgrade. Please follow `docs/TECHNICAL.md` for it.

If the file is missing or broken, the server refuses to start instead of guessing. Passwords are never written to the log.

### Good to know

- **One server at a time.** A player is only "live" on one server. If you try to join a second server while still online on the first, the join waits or is refused until the first server has saved.
- **Availability-first by default.** After unclean expiry the last checkpoint may be adopted automatically, accepting rollback/duplication/loss risk. Choose `safe`/`manual` for fail-closed recovery. Domain reconciliation and missing data can still prevent login.
- **Automatic recovery is optional and availability-first.** If you enable it, a recent verified-state attestation can establish that enabled PlayerData still matches the checkpoint, but it cannot atomically verify Minecraft World/container side effects. Recovery may roll back recent changes or duplicate/lose items when World and database state diverge. Explicit risk acknowledgement is required.
- **Settings must match.** Use the same synchronization settings on every server. If one server turns something off and later on again, affected players are held until an administrator confirms which data to keep.
- **Mods and rules must match.** Items must behave the same on every server. For example, if you use stackable shulker boxes, enable the same rule on all servers, otherwise players carrying stacked boxes cannot join the server without it.
- **Disabling the mod.** Setting `"enable": false` makes the mod do nothing. Re-enabling it later asks you to confirm first, because the database may be out of date.

---

### Development, verification and license

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the actual directory layout, common local/CI commands, isolated MySQL rules, release gates and verification limits. StateLink is licensed under [LGPL-3.0-only](LICENSE); the GPLv3 reference text is kept in [docs/GPL-3.0-LGPLv3-reference.txt](docs/GPL-3.0-LGPLv3-reference.txt) and packaged in JARs as `COPYING`. Bundled libraries retain their original terms.

## 日本語

StateLink は、Minecraft 用(対応バージョンは下記)の **サーバー側** Fabric Mod です。1つの MySQL データベースを共有する複数のサーバー間で、プレイヤーのデータを同じ状態に保ちます。サーバーを移動しても、アイテム・経験値・体力・進捗がそのまま引き継がれます。

### 同期される内容

- インベントリ、防具、オフハンド、エンダーチェスト
- 体力、満腹度、経験値、ポーション効果
- 位置・ディメンション・向き(任意)
- ゲームモード
- 進捗、統計、レシピブック、プレイヤープロフィール(スキン)

項目ごとに設定ファイルでオン・オフを切り替えられます。オフにした項目は、各サーバーのデータがそのまま使われます。

### 必要なもの

- Minecraft 1.18〜1.21.11はFabric Loader 0.17.3以上、26.1〜26.3は0.19.5以上。版別の依存関係は[minecraft-versions.json](minecraft-versions.json)を参照してください。
- お使いの Minecraft バージョンに合った Fabric API
- Java:1.20.4 以下は Java 17、1.20.5〜1.21.11 は Java 21、26.x は Java 25
- すべてのサーバーから接続できる MySQL 8
- データベースを共有する**すべてのサーバー**に導入してください。プレイヤー側への導入は不要です。

### 導入方法

1. お使いのMinecraft版のJAR（`StateLink-2.4.2-mc<バージョン>.jar`）を取得し、ReleaseのSHA-256と照合してから、すべてのサーバーの`mods`へ配置します。
2. サーバーを一度起動し、`config/statelink.json`を生成します。未設定のwriterが動かないよう、DB情報を入力するまでは意図的に初期化を拒否します。
3. サーバーを停止し、データベースの `host`・`name`・`username`・`password` を入力します。
4. サーバーごとに別の `serverId`(例: `s1`、`s2`)を設定します。
5. すべてのサーバーを起動すると、同期が始まります。

### PlayerDataConnector からの移行

StateLink は PlayerDataConnector の新しい名前です。移行の手順は次のとおりです。

1. サーバーを停止します。
2. `mods` フォルダから `PlayerDataConnector` の jar を取り除き、`StateLink` の jar を入れます。両方を同時に入れることはできません。両方ある場合、サーバーは起動しません。
3. サーバーを起動します。

最初の起動時に、StateLink が古いファイルを見つけて自動で引き継ぎます。

- `config/playerdataconnector.json` は `config/statelink.json` に名前が変わります。内容は変更されません。
- DB名・テーブル名は維持します。起動時にスキーマを検証し、必要なcoordination metadataを追加するため、更新前にDBバックアップを取得してください。
- ファイルの削除や上書きは行いません。古い設定ファイルと新しい設定ファイルの両方があり、内容が異なる場合は、どちらかを推測して使うことはせず、サーバーを止めて確認を求めます。

**全backendをまとめて更新してください。** データベースへ書き込むすべてのサーバーを停止し、DBバックアップを取得してから、全台のModを交換して起動します。StateLink 2.3.0はnullableなcoordination metadataを追加し、PlayerDataConnector 2.2.9はその列を認識しません。旧版と新版の混在writer・段階更新は非対応です。JARだけを戻すrollbackも安全ではありません。rollback時は全writer停止のうえ、互換性のある更新前DBバックアップへ一括で戻してください。

StateLink 2.3.1では、不変snapshotとcapture時点のdirty versionを一体として扱います。idle-state比較で差分が見つかった場合は、古い比較用snapshotを流用せずMinecraft server threadで再captureします。verified-stateの鮮度もcapture時刻を基準に保守的に記録し、DB待ちによって古いsnapshotが新しく見えることを防ぎます。

これは PlayerDataConnector 2.2.9 からの移行の場合です。2.1.11 などそれより古いバージョンから移行する場合は、すべてのサーバーを停止したうえで、下の「2.1.11 からの更新」にも従ってください。

コンソールコマンドは `/statelink` になりました。以前の `/pdc` も引き続き使えます。

### 設定

設定ファイルは `config/statelink.json` です。変更後はサーバーを再起動してください。設定例:

```json
{
  "enable": true,
  "database": {
    "host": "127.0.0.1",
    "port": 3306,
    "name": "playerdatasync",
    "username": "minecraft",
    "password": "change-me",
    "tableName": "player_data"
  },
  "sync": {
    "serverId": "s1",
    "leaseDurationMs": 15000,
    "leaseRenewIntervalMs": 3000,
    "inventory": true,
    "enderchest": true,
    "armor": true,
    "offhand": true,
    "health": true,
    "food": true,
    "experience": true,
    "effects": true,
    "position": false,
    "dimension": false,
    "rotation": false,
    "gamemode": true,
    "advancements": true,
    "statistics": true,
    "playerProfile": true,
    "recipeBook": true
  },
  "save": {
    "saveOnDisconnect": true,
    "saveIntervalSeconds": 10
  },
  "recovery": {
    "mode": "last_checkpoint",
    "lastCheckpointDelaySeconds": 5,
    "acknowledgeUnsafeRecovery": true,
    "maxVerifiedStateAgeSeconds": 120
  }
}
```

**全般・データベース**

| 設定 | 初期値 | 内容 |
|---|---|---|
| `enable` | `true` | `false` で Mod を完全に停止します(データベースにも接続しません)。 |
| `database.host` / `port` | なし / `3306` | MySQL サーバーのアドレス。 |
| `database.name` | なし | データベース名。 |
| `database.username` / `password` | なし | データベースのログイン情報。必須です。 |
| `database.tableName` | `player_data` | テーブル名。英数字と `_` のみ。 |
| `database.sslEnabled` | `false` | MySQL への接続を暗号化します。 |
| `database.maxConnections` | `10` | 同時に使うデータベース接続の上限(2以上)。 |
| `database.leaseRenewalPoolSize` | `3`（1.18.2） | 1.18.2の機能。lease更新専用の接続数。他版には2.3.xの全改善を移植したわけではありません。 |
| `database.connectionTimeoutMs` | `2500` | DB接続取得の最大待機時間。 |
| `database.socketTimeoutMs` | `2500` | JDBC socket読み取りtimeout。 |
| `database.queryTimeoutMs` | `2500` | SQL statementごとのtimeout。 |
| `database.lockWaitTimeoutMs` | `2000` | InnoDB row lockの待機上限。 |

serverは、boundedなlease更新と新規接続によるownership再確認がlease期間内に収まらないtimeout組み合わせを拒否します。短いlease(例:`5000` ms / `1500` ms)を使う既存configは、起動前にtimeoutを含めて見直してください。値を黙って書き換えることはありません。例の初期値はlease `15000` ms、更新間隔 `3000` msです。これらの制約は短い遅延による不要な切断を減らしますが、任意長のJVM停止や長時間のDB障害を安全にするものではありません。

**同期する内容**(`sync`)— 記載のないものは初期値 `true`

| 設定 | 内容 |
|---|---|
| `serverId` | このサーバーの固有名(例: `s1`)。**サーバーごとに必ず別の値にしてください。** |
| `leaseDurationMs` | 初期値は1.18.2で`15000`、他のAPI版で`5000`。DB所有権leaseの長さ。実際の設定値は全backendで統一してください。 |
| `leaseRenewIntervalMs` | 初期値は1.18.2で`3000`、他のAPI版で`1500`。lease期間とDB timeoutの余裕を確認してください。値は自動変更しません。 |
| `inventory` | インベントリ。オフにする場合は `armor`・`offhand`・`enderchest` もオフにする必要があります。 |
| `armor`、`offhand`、`enderchest` | 防具、オフハンド、エンダーチェスト。 |
| `health`、`food`、`experience`、`effects` | 体力、満腹度、経験値レベル、ポーション効果。 |
| `position` | 位置とディメンション。向きは、`rotation` で別に指定しない限りこれに従います。 |
| `dimension`、`rotation` | 省略時は `position` に追従。それぞれdimensionとyaw/pitch同期を独立して設定します。 |
| `syncOnDimensionChange` | `true` — dimension変更時に同期処理を開始します。`dimension` domain自体を有効にする設定ではありません。 |
| `gamemode` | ゲームモードと飛行状態。 |
| `advancements`、`statistics`、`recipeBook` | 進捗、統計、レシピブック。 |
| `playerProfile` | スキンなどのプロフィールproperties。UUID・アカウント名を検証し、サーバーごとのチーム装飾・表示名は適用や一致判定の対象にしません。 |

すべてのサーバーで**同じ値**にしてください。

**保存**(`save`)

| 設定 | 初期値 | 内容 |
|---|---|---|
| `saveOnDisconnect` | `true` | プレイヤーが退出するときに保存します。 |
| `saveOnDeath` | `true` | 死亡したときに保存します。 |
| `saveOnDimensionChange` | `true` | ディメンションを移動したときに保存します。 |
| `periodicSaveEnabled` | `true` | オンライン中も定期的に保存します。 |
| `saveIntervalSeconds` | `10` | 定期保存の間隔(秒)。`0` で定期保存を止めます。 |
| `saveOnlyWhenDirty` | `true` | データが実際に変わったプレイヤーだけを保存します。 |

dirty-only保存では、変更のないプレイヤーのPlayerData行を書き換えません。別のboundedなverified-state attestationが有効domain全体を比較して復旧証拠を更新する場合がありますが、PlayerData revisionやcheckpoint時刻は進めません。`play_time`、`total_world_time`、`time_since_death`、`time_since_rest`だけの変化はSAVE triggerになりません。これらの最新値は次の実変更時またはfinal SAVE時に保存されるため、crashでは前回SAVE以降の時間増分が失われる可能性があります。

**クラッシュ時の復旧**(`recovery`)

| 設定 | 初期値 | 内容 |
|---|---|---|
| `mode` | `last_checkpoint` | `manual`: 管理者照合。`safe`: commit済みreceiptの証明がある場合のみ。`automatic`: 承諾付き・鮮度制限付き自動採用。`last_checkpoint`: 鮮度制限なしで採用し、巻戻り・複製・消失リスクを初期状態で許容します。 |
| `lastCheckpointDelaySeconds` | `5` | lease失効とrecovery記録の遅い方から数える自動採用の最短待機。 |
| `acknowledgeUnsafeRecovery` | `true` | 可用性優先の初期値。明示`false`で`last_checkpoint`なら起動を拒否します。 |
| `acknowledgePotentialRollbackOrDuplication` | `false` | `automatic` を使うには `true` が必要です。アイテムの消失や複製の可能性を了承する意味です。 |
| `maxCheckpointAgeSeconds` | `30` | `automatic`では現在、またはDBが記録した最初の不確定時点（recovery記録・lease期限の早い方）で保存が新しいことを要求します。その時点で新しければ停止時間だけで失効させません。 |
| `maxVerifiedStateAgeSeconds` | `120` | checkpoint・owner/session/fence・revision・provenance・enabled domainsが一致するattestationにも同じ鮮度判定を使います。World副作用のexactly-onceを保証するものではありません。 |

2.3.3から、鮮度制限付きの`automatic`モード(`last_checkpoint`とは別)では、停止時間と所有権喪失前からのデータの古さを区別します。遅れてscanしても不確定時点を先送りしません。履歴がない場合は証拠を捏造せず従来の現在時刻での判定へ戻します。同モードで喪失時から古い保存・attestationを採用するには引き続き管理者の明示判断が必要です。保存が一度もないrevision=0の放棄sessionは`MISSING_CHECKPOINT`として知らせ、空のinventoryやPlayerDataを生成しません。

復旧不能なログインは、保存の古さ・欠落・試行上限を明示し、待機後に曖昧な理由で再接続を勧めないようにしました。本当のlease失効・fencingでは引き続き隔離して切断します。`Lease lost / fencing detected`ログのlease経過時間・安全期限・renewal pool/queueを確認してください。kick回避のためfencingを解除してはいけません。既存の5秒lease設定も勝手に書き換えず、現行validationはDB timeoutと再確認に足りる余裕を要求します（設定例は15秒lease・3秒renewal）。

**2.1.11 からの更新** — `migration` セクションは更新時に一度だけ使います。`docs/TECHNICAL.md` に従ってください。

ファイルが無い、または壊れている場合、サーバーは推測せずに起動を拒否します。パスワードがログに出力されることはありません。

### 知っておいてほしいこと

- **同時にオンラインになれるのは1台だけです。** 1台目にいるまま2台目へ入ろうとすると、1台目の保存が終わるまで待たされるか、入れません。
- **初期状態は可用性優先です。** 不正終了後は最後のDB保存を自動採用する場合があり、巻戻り・複製・消失リスクを許容します。fail-closedを優先するなら`safe`/`manual`を指定してください。domain照合やデータ欠落では接続を拒否する場合があります。
- **自動復旧は可用性優先です。** 新しいverified-state attestationは、同期対象のPlayerDataがcheckpointと一致していることを確認しますが、ワールド・コンテナ側の副作用をDBと原子的に確認することはできません。DBとWorldがずれている場合、直近変更のrollbackやアイテムの複製・消失があり得ます。明示的なrisk acknowledgementが必要です。
- **設定は揃えてください。** 同期の設定はすべてのサーバーで同じにしてください。あるサーバーで項目をオフにして後でオンに戻すと、該当プレイヤーは管理者がどのデータを残すか確認するまで入れません。
- **Mod やルールも揃えてください。** アイテムの扱いがすべてのサーバーで同じである必要があります。たとえばスタック可能なシュルカーボックスを使う場合は、全サーバーで同じルールを有効にしてください。そうでないと、重ねたボックスを持つプレイヤーは、ルールのないサーバーに入れません。
- **Mod を無効にするとき。** `"enable": false` にすると何もしなくなります。後で有効に戻す際は、データベースが古い可能性があるため、確認が求められます。

### 開発・検証・ライセンス

実際のディレクトリ構成、ローカル／CI共通コマンド、隔離MySQL、公開条件、未検証範囲は[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)にまとめています。StateLinkのライセンスは[LGPL-3.0-only](LICENSE)です。参照用のGPLv3本文は[docs/GPL-3.0-LGPLv3-reference.txt](docs/GPL-3.0-LGPLv3-reference.txt)に保管し、JAR内では従来どおり`COPYING`として同梱します。別途同梱するライブラリのライセンスは変更しません。
