# StateLink

**Minecraft 1.18 – 1.21.11, 26.1 – 26.3 / Fabric** — by AtsukiMC

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

- Minecraft 1.18, 1.18.1, 1.18.2, 1.19–1.19.4, 1.20–1.20.6, 1.21–1.21.11, 26.1, 26.2 or 26.3 with a recent Fabric Loader (0.17.3 or newer)
- The Fabric API that matches your Minecraft version
- Java 17 for Minecraft up to 1.20.4, Java 21 for 1.20.5 – 1.21.11, Java 25 for 26.x
- A MySQL 8 database that all your servers can reach
- Install the mod on **every** server that shares the database. The mod is not needed on the players' side.

### Installation

1. Pick the jar for your Minecraft version (`StateLink-2.2.9-mc<version>.jar`, for example `-mc1.21.4.jar`) and put it into the `mods` folder of every server.
2. Start the server once. A config file is created at `config/playerdataconnector.json` and the mod stays inactive.
3. Stop the server and fill in the database `host`, `name`, `username` and `password`.
4. Give each server its own `serverId` (for example `s1`, `s2`).
5. Start all servers. Players are now synchronized.

### Configuration

The config file is `config/playerdataconnector.json`. Restart the server after changing it. A typical example:

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
    "inventory": true,
    "enderchest": true,
    "armor": true,
    "offhand": true,
    "health": true,
    "food": true,
    "experience": true,
    "effects": true,
    "position": false,
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
    "mode": "safe"
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

**What to synchronize** (`sync`) — all default to `true` unless noted

| Setting | Meaning |
|---|---|
| `serverId` | A unique name for this server (for example `s1`). **Must differ on every server.** |
| `inventory` | Inventory contents. If you turn this off, `armor`, `offhand` and `enderchest` must be off too. |
| `armor`, `offhand`, `enderchest` | Armor, off-hand item and ender chest. |
| `health`, `food`, `experience`, `effects` | Health, hunger, experience level and potion effects. |
| `position` | Position and dimension. Rotation follows it unless set separately with `rotation`. |
| `gamemode` | Game mode and flying state. |
| `advancements`, `statistics`, `recipeBook` | Advancements, statistics and the recipe book. |
| `playerProfile` | Skin and profile information. |

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

**Crash recovery** (`recovery`)

| Setting | Default | Meaning |
|---|---|---|
| `mode` | `safe` | `manual`: an administrator decides everything. `safe`: recovers only when it is certain nothing is lost. `automatic`: recovers on its own from the last saved state. |
| `acknowledgePotentialRollbackOrDuplication` | `false` | Must be `true` to use `automatic`. It confirms you accept possible item loss or duplication. |
| `maxCheckpointAgeSeconds` | `30` | In `automatic` mode, saves older than this are not restored automatically. |

**Upgrading from 2.1.11** — the `migration` section is only needed once during the upgrade. Please follow `docs/TECHNICAL.md` for it.

If the file is missing or broken, the server refuses to start instead of guessing. Passwords are never written to the log.

### Good to know

- **One server at a time.** A player is only "live" on one server. If you try to join a second server while still online on the first, the join waits or is refused until the first server has saved.
- **Safe by default.** If something goes wrong (for example a server crash), the player is kept out and an administrator is asked to look, instead of risking lost or duplicated items. You will see a message with the reason when this happens.
- **Automatic recovery is optional.** If you prefer that crashed players are recovered on their own, you can enable it in the config. It restores the last saved state, so items gained in the last few seconds before a crash can be lost, or can be duplicated if the world kept them. You must explicitly accept this in the config.
- **Settings must match.** Use the same synchronization settings on every server. If one server turns something off and later on again, affected players are held until an administrator confirms which data to keep.
- **Mods and rules must match.** Items must behave the same on every server. For example, if you use stackable shulker boxes, enable the same rule on all servers, otherwise players carrying stacked boxes cannot join the server without it.
- **Disabling the mod.** Setting `"enable": false` makes the mod do nothing. Re-enabling it later asks you to confirm first, because the database may be out of date.

---

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

- Minecraft 1.18、1.18.1、1.18.2、1.19〜1.19.4、1.20〜1.20.6、1.21〜1.21.11、26.1、26.2、26.3 と、新しい Fabric Loader(0.17.3 以上)
- お使いの Minecraft バージョンに合った Fabric API
- Java:1.20.4 以下は Java 17、1.20.5〜1.21.11 は Java 21、26.x は Java 25
- すべてのサーバーから接続できる MySQL 8
- データベースを共有する**すべてのサーバー**に導入してください。プレイヤー側への導入は不要です。

### 導入方法

1. すべてのサーバーの `mods` フォルダに、お使いの Minecraft バージョン用の jar(`StateLink-2.2.9-mc<バージョン>.jar`、例:`-mc1.21.4.jar`)を入れます。
2. サーバーを一度起動します。`config/playerdataconnector.json` が作成され、この時点では Mod は動作しません。
3. サーバーを停止し、データベースの `host`・`name`・`username`・`password` を入力します。
4. サーバーごとに別の `serverId`(例: `s1`、`s2`)を設定します。
5. すべてのサーバーを起動すると、同期が始まります。

### 設定

設定ファイルは `config/playerdataconnector.json` です。変更後はサーバーを再起動してください。設定例:

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
    "inventory": true,
    "enderchest": true,
    "armor": true,
    "offhand": true,
    "health": true,
    "food": true,
    "experience": true,
    "effects": true,
    "position": false,
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
    "mode": "safe"
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

**同期する内容**(`sync`)— 記載のないものは初期値 `true`

| 設定 | 内容 |
|---|---|
| `serverId` | このサーバーの固有名(例: `s1`)。**サーバーごとに必ず別の値にしてください。** |
| `inventory` | インベントリ。オフにする場合は `armor`・`offhand`・`enderchest` もオフにする必要があります。 |
| `armor`、`offhand`、`enderchest` | 防具、オフハンド、エンダーチェスト。 |
| `health`、`food`、`experience`、`effects` | 体力、満腹度、経験値レベル、ポーション効果。 |
| `position` | 位置とディメンション。向きは、`rotation` で別に指定しない限りこれに従います。 |
| `gamemode` | ゲームモードと飛行状態。 |
| `advancements`、`statistics`、`recipeBook` | 進捗、統計、レシピブック。 |
| `playerProfile` | スキンなどのプロフィール情報。 |

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

**クラッシュ時の復旧**(`recovery`)

| 設定 | 初期値 | 内容 |
|---|---|---|
| `mode` | `safe` | `manual`: すべて管理者が判断します。`safe`: 何も失われないと確実な場合だけ復旧します。`automatic`: 最後に保存された状態から自動で復旧します。 |
| `acknowledgePotentialRollbackOrDuplication` | `false` | `automatic` を使うには `true` が必要です。アイテムの消失や複製の可能性を了承する意味です。 |
| `maxCheckpointAgeSeconds` | `30` | `automatic` で、これより古い保存は自動では復元しません。 |

**2.1.11 からの更新** — `migration` セクションは更新時に一度だけ使います。`docs/TECHNICAL.md` に従ってください。

ファイルが無い、または壊れている場合、サーバーは推測せずに起動を拒否します。パスワードがログに出力されることはありません。

### 知っておいてほしいこと

- **同時にオンラインになれるのは1台だけです。** 1台目にいるまま2台目へ入ろうとすると、1台目の保存が終わるまで待たされるか、入れません。
- **安全側に動作します。** サーバーのクラッシュなど問題が起きた場合、アイテムの消失や複製を避けるため、そのプレイヤーは入れなくなり、管理者の確認が必要になります。その際は理由を表示します。
- **自動復旧は任意です。** クラッシュしたプレイヤーを自動で復旧させたい場合は、設定で有効にできます。最後に保存された状態に戻すため、クラッシュ直前の数秒間に得たアイテムが失われたり、ワールド側に残っていた場合は複製されたりすることがあります。設定でこの点を明示的に了承する必要があります。
- **設定は揃えてください。** 同期の設定はすべてのサーバーで同じにしてください。あるサーバーで項目をオフにして後でオンに戻すと、該当プレイヤーは管理者がどのデータを残すか確認するまで入れません。
- **Mod やルールも揃えてください。** アイテムの扱いがすべてのサーバーで同じである必要があります。たとえばスタック可能なシュルカーボックスを使う場合は、全サーバーで同じルールを有効にしてください。そうでないと、重ねたボックスを持つプレイヤーは、ルールのないサーバーに入れません。
- **Mod を無効にするとき。** `"enable": false` にすると何もしなくなります。後で有効に戻す際は、データベースが古い可能性があるため、確認が求められます。
