> **資料の範囲:** 本文には過去版の設計・migration手順を含みます。現在の同期設定は[README](../README.md)、ビルド・CI・配布手順と検証範囲は[DEVELOPMENT.md](DEVELOPMENT.md)を参照してください。同じDB名を維持することは、旧版と新版の混在writerを許可する意味ではありません。

> **名称変更について:** この Mod は PlayerDataConnector から **StateLink** に改名されました。この文書は改名前に書かれたもので、
> 文中の名前は次のように読み替えてください。動作・スキーマ・手順は同じです。
>
> | 改名前 | 現在 |
> | --- | --- |
> | `config/playerdataconnector.json` | `config/statelink.json`(初回起動時に自動で改名) |
> | `config/playerdataconnector-disabled.marker` | `config/statelink-disabled.marker`(同上) |
> | `/pdc ...` | `/statelink ...`(`/pdc` も引き続き使用可) |
> | `PDC_...` で始まるエラーコード・ログ | `STATELINK_...` |
> | Mod ID `playerdataconnector` | `statelink` |
>
> データベース内の名前(テーブル名、`pdc_` で始まる補助テーブル名、ロック名)は改名していません。
> 改名前後のサーバーが同じデータベースを共有できるようにするためです。

# PlayerDataConnector

Minecraft Fabric 1.18.2用のサーバーサイドModです。MySQLを共有する複数のbackend間で、プレイヤーの状態を安全に同期します。

## 対応環境

- Minecraft 1.18.2
- Fabric Loader 0.17.3以上
- Fabric API 0.77.0以上（`0.77.0+1.18.2`）
- MySQL 8.0系以上（`InnoDB`、`CURRENT_TIMESTAMP(6)`、`SELECT ... FOR UPDATE`を使用）
- Java 17以上

本ソースはMinecraft 1.18.2 / Fabric向けの`2.2.9`検証候補です。2.2.6までのdomain provenance（ownership fenceから独立し、PlayerData revisionに紐付く。履歴なし・不明・revision不一致は通常LOADしない）を維持したまま、**本物の2.1.11 DBからの一回限りのprovenance bootstrap**を行います。2.2.8／2.2.9では、移行前から残るbacklog専用のrecovery age、保留では消費されない試行回数、全backendに対するcluster fingerprint照合、厳密化したlegacy schema判定、bootstrap前の全row preflight、coordination rowの無いPlayerへのsynthetic revision、改名Playerのlegacy profile扱いを追加しました。これは検証候補でありproduction投入の承認ではありません。

### Spatial domain isolation

`position`は座標、`dimension`はWorld、`rotation`はyaw/pitchです。`dimension`/`rotation`を省略した旧設定は従来どおり`position`に追従します。明示した場合、rotationのみ独立して同期できます。異なるWorldの座標を流用しないため、positionとdimensionは同時ON/OFFを必須とします。

| position | rotation | dimension | 動作 |
|---|---|---|---|
| false | false | false | vanillaローカルWorld・座標・角度を維持 |
| true | false | false | 起動拒否 |
| false | true | false | ローカルWorld・座標を維持、角度のみ適用 |
| true | true | false | 起動拒否 |
| false | false | true | 起動拒否 |
| true | true | true | DB World・座標・角度をteleport APIで適用 |

position/dimension=true、rotation=falseも合法です（World/座標のみ、角度はlocal）。`saveOnDimensionChange`と`syncOnDimensionChange`はイベントtriggerであり、同期domainをONにしません。Profile/Recipe/Advancement applyも空間情報を操作しません。

2.4.1以降、ProfileのLOAD検証はUUID・アカウント名・propertiesを対象とします。`display_name`は保存時の表示情報であり、backendごとのチームprefix/suffixを含むlocal表示名との一致を要求しません。scoreboardやアカウント名は復元・変更しません。これは検証の無効化ではなく、表示情報をログインidentityの判定から分離する修正です。

同期OFFの間は古いDB値が保持されます。2.2.6は各domainのprovenanceをownership fenceとは別に記録し、PlayerData revisionとdomain generationに紐付けます。OFFへの切替は次のownership取得transactionで永続記録し、OFF中のDB値はapplyもSAVE上書きもしません。OFFからONへ戻す場合、provenanceが`DISABLED`なら`DOMAIN_REENABLE_REQUIRES_RECONCILIATION`、receiptなし・manifest欠落・破損・revision不一致なら`DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION`で通常LOADを拒否します。fenceだけ進んでもprovenanceは失効しません。manual/automatic last-checkpoint recoveryのacknowledgementは、このdomain gateを解除しません。

管理者はconsole-onlyの`/pdc domain status <uuid>`でmanifest/revision/generationを確認できます。`/pdc domain adopt-db <uuid> <domain> <fence> <revision> <operation-uuid> confirm-db-domain-is-authoritative`は、現在のDB値をそのdomainのauthorityとして明示採用する危険操作です。World/local stateとの一致を検証しないため、実行前に必ず別途照合してください。操作はcoordination row lock下でPlayerData revisionとfenceをCAS確認し、provenance更新・recovery解除・監査記録を同一transactionで行います。domainは1操作につき1つで、未解決domainが残る間はRECOVERY_REQUIREDを維持します。source/local snapshotを確認できる場合は、既存のsource-reviewed recovery APIで現在のenabled-domain snapshotを明示的に採用できます。

Fabric Carpet 1.18.2の`carpet.patches.EntityPlayerMPFake`はbackend-local automation entityとしてPDCの管理対象外です。PDC session、quarantine、ownership、load/save、lease、final flushを作成しません。通常の`ServerPlayerEntity`は従来どおり、sessionが存在しない場合もfail-closedされます。`sync.serverId`は推測されないため、各backendで`s1`、`s2`など一意の値を明示してください。

## 同期の安全モデル

JOIN時に固定時間sleepは行いません。UUIDごとに次の順で処理します。

1. ローカルの`UUID + localSessionId + generation`を作成し、`LOADING`にする。
2. DBの`<player_table>_sync_state`をtransactionで`SELECT ... FOR UPDATE`する。
3. 他sessionのleaseが生きていれば`BUSY`としてtransactionを閉じ、bounded backoffで再確認する。期限切れownerやunclean終了は`RECOVERY_REQUIRED`として拒否し、PlayerDataをLOAD/applyしない。
4. clean/free rowだけ取得できる。取得時に`fencing_token`を増加させ、PlayerDataを同一transaction内で読む。
5. 読み取り結果はMinecraft server threadで全体検証してから適用する。
6. 成功したsessionだけを`READY`にし、leaseを定期更新する。

同じUUIDのDB処理はper-player serial queueを通り、Player AとPlayer Bは別々に並列実行できます。古いsessionのasync結果は、現在のsession・generation・Player参照が一致しない限り適用されません。

## 設定

設定ファイルは`config/playerdataconnector.json`です。初回起動時に空の接続先を持つテンプレートが生成されますが、その起動は失敗します。`database.host`、`database.name`、`database.username`、`database.password`を設定して再起動してください。既存ファイルの破損や読取失敗時に既定値へフォールバックしたり、元ファイルを置き換えたりはしません。新しい任意項目が省略された安全な旧設定にはデフォルトが補われます。

```json
{
  "enable": true,
  "database": {
    "host": "127.0.0.1",
    "port": 3306,
    "name": "playerdatasync",
    "username": "minecraft",
    "password": "change-me",
    "tableName": "player_data",
    "sslEnabled": false,
    "maxConnections": 10,
    "connectionTimeoutMs": 2500,
    "socketTimeoutMs": 2500,
    "queryTimeoutMs": 2500,
    "lockWaitTimeoutMs": 2000,
    "diagnosticLogging": false
  },
  "sync": {
    "inventory": true,
    "enderchest": true,
    "armor": true,
    "offhand": true,
    "health": true,
    "food": true,
    "experience": true,
    "effects": true,
    "position": true,
    "gamemode": true,
    "advancements": true,
    "statistics": true,
    "playerProfile": true,
    "recipeBook": true,
    "serverId": "s1",
    "coordinationTableName": "",
    "leaseDurationMs": 5000,
    "leaseRenewIntervalMs": 1500,
    "handoffRetryInitialMs": 10,
    "handoffRetryMaxMs": 250,
    "joinSyncTimeoutMs": 15000,
    "loginDelayMs": 0,
    "syncOnDimensionChange": true
  },
  "save": {
    "saveOnDisconnect": true,
    "saveOnDeath": true,
    "saveOnDimensionChange": true,
    "periodicSaveEnabled": true,
    "saveIntervalSeconds": 10,
    "saveOnlyWhenDirty": true,
    "backupOldData": true,
    "maxBackupsPerPlayer": 3,
    "finalFlushTimeoutMs": 10000,
    "shutdownFlushTimeoutMs": 10000
  },
  "recovery": {
    "enabled": true,
    "mode": "last_checkpoint",
    "retryIntervalSeconds": 5,
    "maxWaitSeconds": 120,
    "lastCheckpointDelaySeconds": 5,
    "acknowledgeUnsafeRecovery": true,
    "autoRecoverDelaySeconds": 5,
    "maxCheckpointAgeSeconds": 30,
    "maxAutoRecoveryAttempts": 12,
    "acknowledgePotentialRollbackOrDuplication": false
  },
  "migration": {
    "legacy211Bootstrap": false,
    "acknowledgeLegacy211DatabaseAsAuthority": false,
    "acknowledgedLegacy211DomainFingerprint": "",
    "legacy211NewDomainPolicy": "disabled",
    "legacy211RecoveryMaxCheckpointAgeSeconds": 2592000,
    "legacy211QuarantineUndecodableRows": false,
    "acknowledgeClusterDomainFingerprint": "",
    "acknowledgeReactivationMarker": ""
  },
  "security": {
    "enableWhitelist": false,
    "requireSameIP": false,
    "maxLoginAttemptsPerMinute": 5,
    "logDataAccess": true,
    "encryptSensitiveData": false
  }
}
```

### 設定一覧

| 名前 | 型 | 初期値 | 説明 |
|---|---|---|---|
| enable | boolean | true | falseならDB、schema、session、同期、quarantine、復旧timer／DB commandを一切初期化しない |
| migration.legacy211Bootstrap / acknowledgeLegacy211DatabaseAsAuthority | boolean | false | 2.1.11 DBの一回限りのprovenance bootstrap承認。必ず2つ同時に設定。「2.1.11からの移行」参照 |
| migration.acknowledgedLegacy211DomainFingerprint | string | 空 | 任意。設定した場合、このbackendのbootstrap fingerprintと一致しなければ起動拒否 |
| migration.legacy211NewDomainPolicy | string | disabled | 2.1.11がplaceholderしか保存しなかったdomain（playerProfile／advancements／statistics／recipeBook）の扱い。`disabled`または`adopt-first-login-local` |
| migration.legacy211RecoveryMaxCheckpointAgeSeconds | int | 2592000（30日） | 2.1.11時代から残るbacklog専用のautomatic recovery age上限。`recovery.maxCheckpointAgeSeconds`とは別。bootstrapが採用したcheckpointのままのrowにだけ適用 |
| migration.legacy211QuarantineUndecodableRows | boolean | false | preflightでdecode不能なrowがあるとき、falseなら起動拒否、trueならそのrowだけprovenanceを付けず（login時にfail closed）他を移行 |
| migration.acknowledgeClusterDomainFingerprint | string | 空 | bootstrap後にcluster全体のdomain設定を意図的に変える場合、起動拒否messageに表示された新fingerprintを指定 |
| migration.acknowledgeReactivationMarker | string | 空 | `enable=false`で動かしたbackendを再有効化する際、local markerのIDを指定 |
| database.host / name / username / password | string | 空文字 | enabled時のみ必須。初回テンプレートは非稼働。secretは表示しない |
| database.port | int | 3306 | MySQL接続ポート |
| database.tableName | string | player_data | `[A-Za-z0-9_]+`。不正値を別名へ補正しない |
| database.sslEnabled | boolean | false | JDBC TLS |
| database.maxConnections | int | 10 | pool上限（2以上） |
| database.connectionTimeoutMs | int | 2500 | poolから接続を取得する待機期限 |
| database.socketTimeoutMs / queryTimeoutMs | int | 各2500 | JDBC通信／statement期限 |
| database.lockWaitTimeoutMs | int | 2000 | InnoDB row-lock待機期限 |
| database.diagnosticLogging | boolean | false | 詳細なDB待機診断 |
| sync.serverId | string | server-unknown | 各backend固有の識別子。変更を推奨 |
| sync.coordinationTableName | string | 空文字 | 空なら `<tableName>_sync_state` |
| sync.inventory | boolean | true | main inventoryとcursor／一時GUI入力／pendingを含むitem ownership同期 |
| sync.armor / offhand / enderchest | boolean | 各true | item subdomain。inventory=falseなら全てfalseが必須 |
| sync.health / food / experience / effects | boolean | 各true | health・air／空腹／整数XP／effect同期 |
| sync.position / gamemode / playerProfile | boolean | 各true | 位置・向き／gamemode・飛行／profile情報 |
| sync.advancements | boolean | true | criterion progressと取得時刻のreplace同期。復元で報酬は再実行しない |
| sync.statistics | boolean | true | stat type/value IDと整数のreplace同期。加算しない |
| sync.recipeBook | boolean | true | known/display recipe IDとoptionsのreplace同期 |
| sync.leaseDurationMs / leaseRenewIntervalMs | long | 5000 / 1500 | leaseとrenew周期。timeoutとの安全余裕も検証 |
| sync.handoffRetryInitialMs / handoffRetryMaxMs | long | 10 / 250 | BUSY時のbounded backoff |
| sync.joinSyncTimeoutMs | long | 15000 | JOIN同期期限 |
| sync.loginDelayMs | int | 0 | deprecated、使用しない |
| sync.syncOnDimensionChange | boolean | true | respawn/dimension callback同期条件 |
| save.saveOnDisconnect | boolean | true | false時は追加final data SAVEなし。最新durable rowとの一致証明だけがclean releaseを許可 |
| save.saveOnDeath / saveOnDimensionChange | boolean | 各true | 該当event checkpoint |
| save.periodicSaveEnabled | boolean | true | 定期checkpoint |
| save.saveIntervalSeconds | int | 10 | checkpoint間隔。0なら停止。旧minutesより明示secondsを優先 |
| save.saveOnlyWhenDirty | boolean | true | dirty Playerのみsnapshot/SAVE |
| save.saveIntervalMinutes | int | deprecated | secondsなしの旧JSONだけminutes×60へ換算。両方なしは10秒 |
| save.finalFlushTimeoutMs / shutdownFlushTimeoutMs | long | 各10000 | finalization／shutdownのbounded wait |
| save.backupOldData / maxBackupsPerPlayer | boolean / int | true / 3 | 旧互換項目。自動backup管理は未実装 |
| recovery.enabled | boolean | true | 自動復旧engineのみのON/OFF。recovery gateと手動復旧は残る |
| recovery.mode | string | last_checkpoint | 2.4.0から可用性優先。manual / safe / automatic / last_checkpoint |
| recovery.retryIntervalSeconds | int | 5 | bounded scan/login retry周期（正数） |
| recovery.autoRecoverDelaySeconds | int | 5 | automatic最短待機。flag時刻とlease expiryの遅い方から数える |
| recovery.maxCheckpointAgeSeconds | int | 30 | 最大checkpoint age。超過はWARN・保留 |
| recovery.maxAutoRecoveryAttempts | int | 12 | automaticのDB durable試行上限。restart/loginでresetしない |
| recovery.acknowledgePotentialRollbackOrDuplication | boolean | false | automatic危険承認。未承認はstartup拒否 |
| recovery.maxWaitSeconds | int | 120 | safe proof待機上限。超過してもunsafeへ切り替えない |
| recovery.lastCheckpointDelaySeconds | int | 5 | unsafe modeの最短待機時間。安全の証明ではない |
| recovery.acknowledgeUnsafeRecovery | boolean | true | 2.4.0から危険を許容する初期値。明示falseなら同modeのstartup拒否 |
| security.enableWhitelist / requireSameIP / encryptSensitiveData | boolean | 各false | 旧互換設定。現同期基盤での追加強制処理は未実装 |
| security.maxLoginAttemptsPerMinute / logDataAccess | int / boolean | 5 / true | 旧互換設定 |

`enable=false`ならDB未設定でも起動できます。JSON破損は無効化状態でも拒否します。無効backendの変更は同期されません。全backend有効を推奨します。OFF中も各backendのMinecraft保存は続き、PDCはDBへ何も記録しないため、domain単位のprovenance gateでは無効期間を検出できません。その代わりlocal markerによる再有効化guardがあります（「`enable=false`からの再有効化」参照）。再有効化後はDB値がlocal変更を置換します。

`serverId`はbackendごとに一意にしてください。ownershipはsession/fenceで判定します。`connectionTimeoutMs`はログイン待機時間全体ではなくpool待機時間です。socket/query/lock timeoutとlease budgetの制約も満たす必要があります。

`inventory=false`ではarmor/offhand/enderchestもfalseにし、PDCはitemのcapture、clear、pending replay、item列SAVEを行いません。local vanillaが所有します。既存DB item/pendingは保存したままで、OFF中のローカルitem変更とは照合されません。**再ON時はprovenance gateでloginを停止し、管理者の明示reconciliationが必要です。** 通常LOAD、safe/automatic recovery、ownership fence更新だけで古いitem representationを復活させることはありません。

`saveOnDisconnect=false`ではenabled domainの最新snapshotを、coordination lock下のdurable rowと比較します。同値のみdata UPDATE／revision加算なしでclean releaseします。変更、比較不能、active cursor/一時入力があればRECOVERY_REQUIREDです。periodic保存を推奨します。statisticsのplay_timeやeffectsの残時間等も実際の変更なのでclean条件は厳密です。periodic OFFでも古いcheckpointを無条件cleanにはしません。

進捗はadvancement ID→criterion→取得epoch milliseconds、統計はstat type ID→value ID→整数で保存します。どちらもDB replace authorityで、OFFならtrackerに触れません。dirty-only周期保存では`play_time`、`total_world_time`、`time_since_death`、`time_since_rest`の受動的時間カウンタだけをdirty判定から除外します。統計payload自体から除くわけではなく、他の変更でcheckpointされるときや明示的なfinal saveでは現在値を含めます。受動的時間統計しか変化しないidle期間は周期保存されないため、DB値が次の実変更またはfinal saveまで遅れることがあります。旧版placeholderには実履歴がないため、そのLOADだけlocal stateを保持し、次checkpointで実形式へ移行します。未知identifier／criterionはsilentに捨てずfail closedです。

profileはauthlib properties（複数値・signature含む）を保存します。認証済みUUID/nameやglobal scoreboardをDBから改名しません。保存済みdisplay labelとの不一致はfail closedです。recipeはknown/display ID・optionsを保存し、復元でunlock rewardを実行しません。旧profile identity-only／recipe placeholderはlocal stateを保持し、次checkpointで実形式へ移行します。

dirty検知はserver tick末尾のscalar/item値比較とadvancement/stat/recipe変更カウンタです。idle時は全NBT serializeや全進捗・統計scanをしません。itemコピーは値が変わった場合のみ行い、in-place NBT変更も検知します。disabled domainは読みません。統計のdirty revisionでは上記4つの受動的時間カウンタのみを無視し、ジャンプ・採掘等の実績統計は引き続きdirtyになります。成功SAVEはcaptured change epochのみcleanにし、遅れたcallbackは後の変更を消しません。初回READY後は一度保守的にcheckpointします。`saveOnDisconnect=false`のclean release判定はdurable dataとの比較ですが、統計については同じ4つの受動的時間カウンタを除外して比較します。したがって`statistics=true`（default）でも、checkpoint後に何もせず切断したPlayerはclean releaseされ、採掘・ジャンプ等の実統計が変わっていればRECOVERY_REQUIREDです。clean releaseではDBへ書かないため、次回LOADでは受動カウンタが最後のcheckpoint時点の値に戻ります。

### LOADING quarantine

PlayerDataのLOAD・検証・applyが終わるまで、sessionは`LOADING`です。Fabric callbackだけでなく1.18.2のserver packet handlerをMixinでゲートし、drop、pickup、slot/container click、craft、creative inventory、item/block/entity interaction、equipment slot変更、movement、vehicle movement、client command、spectator teleport、ability変更、sign/book/beacon/structure/command-block更新を処理させません。ItemEntity/ExperienceOrbEntityの拾得、ServerPlayerEntityのdrop・damage・teleport・dimension change、Entityの移動・requested teleportもサーバー側入口で拒否します。

`READY`になった瞬間にだけ通常操作を許可します。quarantineはgame modeを一時変更しないため、authoritative data applyの`changeGameMode`と衝突しません。サーバーコンソール、他Mod、管理プラグインが直接Playerへ変更を行う経路は、このpacket境界だけでは遮断できないため、stagingで併せて確認してください。

`connectionTimeoutMs`、JDBC `socketTimeoutMs`、query timeout、InnoDB `innodb_lock_wait_timeout`は設定値として検証されます。初期化後にplayer-data、coordination、recovery-audit tableの実storage engine、required primary key/index、authoritative payload型、coordination/recovery列の型・NULL可否・重要既定値を照会します。不適合schemaでは起動を拒否し、既存tableを暗黙に変換・修復しません。lock wait timeoutやdeadlockで例外が出た場合は、repositoryが必ずrollbackしてconnectionをpoolへ返してから、transaction全体をbounded retryします。

### XP互換性

`experience_points`は従来どおり累積XPの列として保持します。`experience_points_into_level`にはlevel内の整数XP点数をnullable `BIGINT`で保存し、LOAD時にlevelと次levelまでの必要点数から`experienceProgress`を再構成します。MySQL `FLOAT`のdecimal変換にlevel-up判定を依存させません。既存rowで整数列がNULLなら、旧`experience_progress`をlevel内整数点数へ量子化し、それもNULLなら保存済みlevelと累積XPから1.18.2のlevel境界式で導出します。既存列の意味は変更せず、旧版ではlevel/progressが列に存在しなかったため、`/xp add levels`等で累積XPとlevelが独立していた過去の状態を完全復元できない場合があります。

### Disconnect時の一時入力item

DISCONNECTではserver thread上でcursor、vanilla crafting input、および金床／鍛冶台／エンチャント台／石切台／製図台／機織り機／砥石／村人取引／ビーコン画面の一時入力をコピーし、完全なPlayerData snapshotへ組み込んでから元slotを空にしてscreen closeを実行します。ビーコンではpayment slotだけを扱い、選択effect、block state、property delegate、UI値はPlayerDataへ保存しません。computed result slotは入力から再生成できるため保存せず、永続block-entity inventory（かまど、チェスト、ホッパー等）は回収・消去しません。disconnect cleanup中に`offerOrDrop`等でitemをWorldへ生成しないため、final DB transactionのcommit前にDBとWorldの両方へ同じitemが存在する窓を作りません。transient stackは必要に応じて`pending_disconnect_items`列へ保存し、次回LOAD時にmain inventoryへ空きがある分だけ先にDBへ正規化保存してからPlayerへ適用します。入り切らない残りはpendingのまま維持され、Worldへdropしません。

PERIODIC、DEATH、DIMENSIONのlive checkpointもserver threadでcursorと対応するPlayer-owned temporary inputをread-only captureします。各snapshotのpendingは、sessionのdurable pendingとその時点のlive transientから毎回再構成し、session自体へappendしません。従って、GUIに同じstackが残ったまま複数回checkpointしてもDB内のpendingは増えず、後のcheckpointでstackがPlayerInventoryへ戻れば古いtransient表現も置き換わります。

#### 1.18.2 vanilla screen inventory分類

| ScreenHandler | Player-owned temporary input | persistent/shared inventory | result/derived state |
|---|---|---|---|
| Player / Crafting | 2×2 / 3×3 `CraftingInventory` | PlayerInventoryは通常のPlayerData列 | crafting resultは保存しない |
| Generic Container / Generic 3×3 / Hopper / Shulker | なし | container inventory | なし |
| Abstract Furnace / Furnace / Blast Furnace / Smoker | なし | furnace block inventoryのinput・fuel・output | なし |
| Brewing Stand | なし | brewing block inventory | なし |
| Anvil / Smithing | slot 0・1 | PlayerInventory | output slotは保存しない |
| Enchantment | slot 0・1（item・lapis） | PlayerInventory | enchant選択状態は保存しない |
| Stonecutter | slot 0 | PlayerInventory | recipe resultは保存しない |
| Cartography | slot 0・1 | PlayerInventory | map resultは保存しない |
| Loom | slot 0・1・2 | PlayerInventory | pattern previewは保存しない |
| Grindstone | slot 0・1 | PlayerInventory | outputは保存しない |
| Merchant | slot 0・1 | villagerのtrade state | trade resultは保存しない |
| Beacon | payment slot 0のみ | Beacon block state | effect選択・property/UI stateは保存しない |
| Horse | なし | horse/entity inventory | なし |
| Lectern | なし | lectern blockのbook inventory | page/property stateは保存しない |

この分類は1.18.2 vanillaの各handlerが参照するbacking inventoryとslot構成に基づきます。`AbstractRecipeScreenHandler`等の共通親classだけではtemporaryかpersistentかを判断しません。modded handlerは別途個別監査が必要です。

cursor／crafting overflowは切断時に地面へ落ちず、正常final commit後のloginでPlayer inventoryへ戻ります。final SAVE失敗時はclean releaseせず`RECOVERY_REQUIRED`を記録します。記録できないDB障害でも、残存ownerのlease expiryをuncleanとして拒否します。manual/safeはDBとWorldの一致を証明できない古いcheckpointを自動復元しません。automaticは別の危険承認付きpolicyによりlast checkpointを採用しますが、未commitのlocal変更の完全復元は保証しません。World整合性が必要な復旧はoperatorによるsource/World調査と明示的resolutionを使用してください。

### Strict Safety / Fail-Closed Recovery

`lease expired`は「空き」ではありません。final SAVE＋release COMMIT、またはno-save時のdurable equality証明だけがcleanです。unclean expiry等は`RECOVERY_REQUIRED`です。manual/safeでは時間経過・restart・SIGKILLだけで解除しません。automaticのみ承認付きpolicy解決後にfresh acquireを許可します。[元のstrict契約](docs/unclean-recovery-design.md)と[2.2.x変更点](docs/2.2.0-design.md)を区別してください。

復旧の第一選択は、sourceをfenceし、World/containerとlocal Player状態を調査・照合して完全なfresh snapshotを作ることです。`resolveRecoveryFromSource()` repository APIは、明示的な検証済みsnapshot、期待fence/revision、operation UUID、operatorのacknowledgementを要求し、snapshot保存・fence/revision更新・clean resolution・auditを同じtransactionでcommitします。単にsourceを再起動したりvanilla player fileを読むだけでは調査済みになりません。自動source復旧は実装しません。

sourceを復元できない場合の危険な代替操作（server console/RCON専用）:

```text
pdc recovery status <player-uuid>
pdc recovery force-last-checkpoint <player-uuid> <expected-fence> <expected-revision> <operation-uuid> confirm-source-fenced-and-world-reviewed
```

**force操作は外部化されたitemを複製・破棄する可能性があります。** 実行前に旧sourceを停止/fenceし、World状態を調査・reconcileしてください。この文言は単なる確認tokenであり、PDCがOS停止やWorld一致を検証したという意味ではありません。statusで確認した期待値を使い、通信結果が不明なretryは同じoperation UUIDで行います。audit済みoperationのretryは二重更新せず、期待値不一致はCONFLICTです。Playerのlocal sessionが残るbackendではforceを拒否します。

追加診断: `pdc recovery list`（最大20件）、`pdc recovery inspect <player-name-or-uuid>`。console/RCON専用でowner/session/fence/revision/reason/時刻/pending件数を表示します。

| reason / evidence | safe mode | 判断 |
|---|---|---|
| FINAL_COMMIT_ACK_UNKNOWN + 同transactionのfinal receipt + owner全NULL + fence/revision/digest一致 | SAFE_AUTO_RECOVERABLE | committed final payloadが実DBと一致する場合のみaudit付きresolution |
| final retryで同session/fence/payloadのcommitted receiptを確認 | commit済みとして完了 | 同じSAVEを再実行しない。既に取得した新ownerも変更しない |
| LEASE_EXPIRED_UNCLEAN / LOCAL_AUTHORITY_LOST / FINAL_SAVE_FAILED / SHUTDOWN_TIMEOUT | MANUAL_REQUIRED | WorldとDBの一致を証明できない |
| SESSION_ABANDONED / INVALID_OWNERSHIP / DIRTY_DISCONNECT_WITHOUT_SAVE | MANUAL_REQUIRED | incomplete/uncertain state |
| 名前だけACK_UNKNOWN、receiptなし／payload不一致 | MANUAL_REQUIRED | reasonは証明ではない |

manualでは自動解決なし。safeは最大16件の回転scanでdurable receiptを検証し、上限時間後もRECOVERY_REQUIREDを保持します。source停止やlease expiryだけでは解除しません。

**Automatic is availability-first, NOT safe recovery. 自動復旧はWorldとのitem重複・消失・rollbackを許容します。** row lock→fresh DB time→expired/unowned確認→fence/revision CAS→audit付き解除→fresh acquireを行います。live owner/未失効leaseは解除せず、旧fence writeは拒否します。2.4.0は利用者の指定により`last_checkpoint`を初期値にしました。これはage/attempt上限なしで最後のDB保存を採用する危険なpolicyです。age/attempt上限が必要なら`automatic`、不確定な復旧を止めるなら`safe`/`manual`を明示してください。既存の明示configを自動変換しません。

| reason | automatic（ack必須） | safe |
|---|---|---|
| LOCAL_AUTHORITY_LOST / LEASE_EXPIRED_UNCLEAN / SERVER_CRASH / SHUTDOWN_TIMEOUT / FINAL_SAVE_FAILED | expired/unowned・delay・age・CASを満たす場合のみlast checkpoint | 自動採用不可 |
| WORLD_MUTATION_UNCERTAIN / DIRTY_DISCONNECT_WITHOUT_SAVE / SESSION_ABANDONED | 同上。World不整合を承認 | 自動採用不可 |
| FINAL_COMMIT_ACK_UNKNOWN | 同上 | 完全receipt・owner NULL・payload一致の証明時のみ |
| INVALID_OWNERSHIP / unknown reason | 拒否 | reasonだけでは解除不可 |

既存2.1.11 RECOVERY_REQUIREDも同じengineへ投入し、migrationで直接NULL化しません。producer列NULLをauditへ`2.1.11-legacy`と記録します（DBだけから特定旧JARのSHAを証明するものではありません）。previous owner/session/fence/revision、reason、checkpoint timestamp、mode、operation UUIDを保持します。古すぎるcheckpointはWARN・保留するため、legacy復旧が必要ならWorldリスクを理解してage上限を明示調整してください。全legacy rowが無条件で解決されるわけではありません。

backgroundは最大16件・1scanのみin-flightで起動threadをblockしません。loginも同policyを試し、delay待ちはLOADING quarantine／復旧メッセージ、解決後は同一login内でfresh acquire→LOAD→READYです。JOIN timeout超過時は再接続してください。policy評価をDBでreserve後、全guardを再確認する別transactionが解決を行います。SQL rollbackでも試行予算は消費されます。上限到達はRECOVERY_REQUIREDを維持し、inspect/statusでattempt数を確認できます。自動timerによる予算resetやforceはありません。

同じDBを使う全backendでrecovery policyと同期domainの設定を統一してください。item-domain OFFのbackendとONのbackendを同時運用して、残存DB itemを自動的に再採用させないでください。automaticがrowを解決すると共有DB全体でclean/freeになるため、safe設定の別backendもその後通常acquireできます。safeは他backendが既に承認して解決したcheckpointを再判定する境界ではありません。10秒checkpointのrollback幅は通常約10秒ですが、server stall・DB障害・queue遅延では延びます。age上限30秒は復旧評価時のguardであり、全障害で10秒以内のrollbackを保証する値ではありません。

周期checkpointはPlayerごとにcoalesceします。前回の周期SAVEがまだDB workerで開始されていない間は、新しい周期snapshotを積みません（dirty epochは残るため次の周期で最新stateを保存します）。周期SAVEによるper-player queueは「実行中1＋待機1」が上限です。death／dimension change／final／shutdownのSAVEはcoalesce対象外です。

login拒否時のmessageにはrecovery reason名（例: `DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION`）を表示します。domain reconciliationが必要な場合はautomatic modeでも再試行せず、再接続では解決しない旨を表示します。

dirty-onlyで長時間変更のないPlayerはcheckpoint時刻も古くなります。lease renewはcheckpointの新鮮さの証明ではないため、その時刻を更新しません。このPlayerのcrash復旧もage上限を超えれば保留します。idleにも無条件SAVEしてageを若返らせる設計ではありません。復旧可用性を優先して上限を広げる場合は、より古いstateの採用リスクを明示的に受け入れて設定してください。また、旧設定の`saveIntervalMinutes=5`だけがある場合は300秒を維持します。10秒運用へ移行するには`saveIntervalSeconds=10`を明示してください。

availability優先の運用例（software defaultはsafe）:

```json
{
  "save": {"periodicSaveEnabled": true, "saveIntervalSeconds": 10, "saveOnlyWhenDirty": true, "saveOnDisconnect": true},
  "recovery": {"enabled": true, "mode": "automatic", "autoRecoverDelaySeconds": 5,
    "maxCheckpointAgeSeconds": 30, "retryIntervalSeconds": 5, "maxAutoRecoveryAttempts": 12,
    "acknowledgePotentialRollbackOrDuplication": true}
}
```

正常tick/DB処理とcheckpoint成功が続く場合のrollback幅は通常約10秒です。stall、DB障害、queue遅延、age上限変更時はこの幅を保証しません。10秒保存でもfinal SAVEは必要です。

全writerを2.1.11以上の同じ承認済みSHAへ揃えることが必須です。2.0.0だけでなく2.1.10以前もunclean recoveryを理解しません。両旧backend停止→旧writerが0を確認→DB/world backup→両方更新→起動の順にしてください。schema追加は非破壊・idempotentですが、旧writerへonline rollbackすると安全契約を失うため、rollbackにはoffline調査/reconciliationが必要です。

### 2.1.11からの移行（一回限りのprovenance bootstrap）

2.1.11のDBにはdomain provenanceもclean-release receiptもありません。2.2.7は起動時、DDLを一切適用する前に既存schemaの世代をtable／column／型／key／engineから判定します（provenance列がNULLであることは判定材料にしません）。

| 世代 | 判定 | 動作 |
|---|---|---|
| FRESH | PDC tableなし | 新規作成 |
| LEGACY_2_1_11 | player_data 41列・coordination 11列・recovery audit 12列が2.1.11の定義と完全一致 | 下記bootstrap。data rowが0件ならackなしでschema追加のみ |
| PRE_PROVENANCE_2_2 | 2.2.0〜2.2.4の列あり・provenance列なし | 列追加。clean receiptがrevision一致するrowのみbackfill（2.2.6と同じ） |
| CURRENT_PROVENANCE | provenance列あり | 通常起動。NULL provenanceのrowはこれまで通りfail closed |
| UNKNOWN_SCHEMA | 上記以外（列の過不足・型違い・audit欠落等） | **起動拒否**。2.1.11として扱うことはしない（tableが全て空の場合のみ通常の厳密検証へ進む） |

LEGACY_2_1_11でPlayerDataが1件でもあり、承認が無ければ `LEGACY_2_1_11_PROVENANCE_BOOTSTRAP_REQUIRED` で**起動時に**停止します（schemaは変更しません）。Playerが個別に隔離されて初めて気付く設計ではありません。

**2.1.11で実際に同期されていたdomain**（2.1.11 artifactの`extractCompletePlayerData`／`upsertPlayerData`／`prepare`／`applyPrepared`から確認）:

| 2.2.7 domain | 2.1.11の実態 | bootstrap後 |
|---|---|---|
| inventory / enderchest / armor / offhand / health / food / experience / effects / gamemode | 同名switchでSAVE・LOAD/applyとも実装 | 現configでtrueなら`ENABLED_AUTHORITATIVE`、falseなら`DISABLED` |
| position / dimension / rotation | 単一の`position` switchで座標・dimension・yaw/pitchをまとめてSAVE・apply | `position`がtrueの場合のみ。`position=false`で`rotation=true`は起動拒否 |
| advancements / statistics | 定数placeholder（`advancement_data_simplified`／`statistics_data_simplified`）を書くだけ。applyなし | policy次第（下記） |
| recipeBook | 定数`{"known_recipes":[]}`のみ。applyなし | 同上 |
| playerProfile | `{"name","uuid"}`のみ。applyなし | 同上 |

bootstrapは現在のconfigを盲信しません。operatorが「現在の`sync.*` domain設定は2.1.11運用時と同じ」と明示承認した上で、DBの実値とも照合します（有効扱いにするitem／effect domainにNULLのrowがあれば`LEGACY_2_1_11_DOMAIN_EVIDENCE_MISMATCH`、live leaseが残っていれば`LEGACY_2_1_11_BOOTSTRAP_LIVE_OWNER`で起動拒否）。

改名したPlayer: 2.1.11のplayerProfile placeholderに入っているnameは保存当時の名前で、LOADに使われたことはありません。2.2.8はplaceholderをname比較より先に識別し、UUIDが一致すれば現在の名前でloginできます（古い名前を適用することはありません）。2.2.xが保存した本物のprofile dataに対するname不一致の拒否は従来どおりです。

placeholderのみだった4 domainは、2.1.11時代は各backendのlocal stateが実体でした。DBに同期すべき値は存在しないため、cluster単位でpolicyを選びます。

- `disabled`（default）: 4 domainを`sync.*=false`にして移行する。trueのままなら`LEGACY_2_1_11_NEW_DOMAIN_POLICY_REQUIRED`で起動拒否。provenanceは`DISABLED`となり、後から有効化するには通常のdomain reconciliationが必要。
- `adopt-first-login-local`: 全rowの保存値が2.1.11 placeholderそのものであることを検証した上で有効化する。各Playerは**最初にloginしたbackendのlocal state**を保持し、その最初のcheckpointでDBへ実データが入る。他backendに残っている別のlocal進捗・統計・recipe・profileは、そのPlayerが移動した時点でDB値に置換される。placeholder以外の値を持つrowがあれば`LEGACY_2_1_11_NEW_DOMAIN_VALUES_NOT_PLACEHOLDER`で起動拒否。 また、最初にloginしたbackendのlocal進捗にまだ無いchallenge進捗の条件を、DBから適用されたinventoryが満たす場合（例: netherite hoe所持→`husbandry/obtain_netherite_hoe`）、vanillaがその進捗を新規達成として扱い報酬XPを1回付与します。これはPDCによる報酬replayではなくvanillaの判定で、進捗がbackendごとのlocalだった2.1.11でも同じことが起きていました。移行後は進捗がDBで同期されるため、Playerごとに最初の1回だけです。

手順:

1. 全backend（2.1.11）を停止し、leaseが失効するまで待つ。DBとWorldをbackup。
2. 全backendのJARを2.2.7へ置換。全backendの`sync.*` domain設定を2.1.11運用時と同一にする。
3. 全backendのconfigへ `"migration": {"legacy211Bootstrap": true, "acknowledgeLegacy211DatabaseAsAuthority": true, "legacy211NewDomainPolicy": "..."}` を追加。
4. 1台目を起動。logに `Legacy 2.1.11 provenance bootstrap COMPLETED rows=... fingerprint=...` が出る。
5. 2台目以降を起動。同時起動してもMySQL advisory lockで直列化され、bootstrapは1回だけ実行される。
6. 全台が起動したら`migration.legacy211Bootstrap`と`acknowledgeLegacy211DatabaseAsAuthority`を削除してよい（`legacy211RecoveryMaxCheckpointAgeSeconds`はbacklogが解消するまで残す）。以後のdomain設定変更は「cluster fingerprint」の手順に従う。

bootstrapは各PlayerData rowへ、その**data revision**に対するprovenance（generation 1・cluster共通manifest）を設定するだけです。ownership fence、lease、owner、`recovery_required`／reasonには触れません。coordination rowを持たない古いPlayerData（2.1.11は`data_revision=0`としてLOADしていた）にはrowを作成し、synthetic revision 1で確立します（後述）。200 row単位のtransactionで実行し、完了までgameplay authorityは開始しません。途中でprocessが落ちても`<coordination>_migration` tableの`STARTED` recordで検出し、同じ承認configでのみ再開できます（処理済みrowは再更新しません）。fingerprintはDBへ記録され、以後は承認flagの有無に関係なく全backendが照合されます（下記「cluster fingerprint」）。

bootstrap完了後は通常のprovenance ruleだけが適用されます。current schemaでprovenanceがNULLのrow、revision不一致、OFF→ONは、承認configが残っていても引き続きfail closedです。

既存のRECOVERY_REQUIRED／lease切れownerはbootstrapでは解除されず、設定されたrecovery policyへそのまま渡ります。automaticでは復旧→fresh acquire→provenance PASS→LOADまで同一login内（またはbackground scan）で完了します。age上限は下記のlegacy専用policyが適用されます。

#### 承認の意味（必ず読んでください）

`acknowledgeLegacy211DatabaseAsAuthority=true` は、**2.1.11 DBに現在入っている同期domainの値を、全Playerについてclusterのauthoritativeなstateとして採用する**という宣言です。

- 対象は最近loginしたPlayerだけではありません。何か月もloginしていないPlayerのrowも、その古いDB値のまま権威になります。次にloginしたとき、そのPlayerのbackend local state（inventory等）はDB値で置換されます。これは2.1.11が今行っている動作と同じですが、移行はそれを明示的に承認する操作です。
- 承認後に個別のPlayerだけDBではなくlocalを採用したい場合は、そのPlayerがloginする前にsource-reviewed recoveryで処理してください。
- placeholderのみだった4 domain（playerProfile／advancements／statistics／recipeBook）はこの承認の対象外で、`legacy211NewDomainPolicy`で別に決めます。

#### 初回起動より前に決める設定

以下は**2.2.9を最初に起動する前**に全backendへ入れてください。

1. `sync.*` のdomain switch（2.1.11運用時と同一に）。
2. `migration.legacy211Bootstrap` / `acknowledgeLegacy211DatabaseAsAuthority` / `legacy211NewDomainPolicy`。
3. backlogを自動復旧させるなら `recovery.mode=automatic` と `acknowledgePotentialRollbackOrDuplication=true`。
4. `migration.legacy211RecoveryMaxCheckpointAgeSeconds`（default 30日）。移行前から残るRECOVERY_REQUIRED／lease切れownerのcheckpointがこれより古い場合だけ広げます。

**2.2.0〜2.2.6を、production DBに対して一度も起動しないでください。** それらは2.1.11 DBへ列を追加してしまい、DBは`CURRENT_PROVENANCE`と判定されてbootstrap対象外になります（全Playerが隔離されたままになります）。2.2.7もrevision 0のまま移行するため使用しないでください。2.1.11から直接2.2.9へ上げます（2.2.8は保留中のrecoveryを数秒ごとにWARN出力し続けるため使用しないでください）。

#### bootstrap前のpreflightとsynthetic revision

- bootstrapは何かを記録・変更する前に、採用する全rowを検査します（JSON形状、slot範囲、必須field、scalar範囲。LOAD時のcodecと同じ規則のうちregistryに依存しない部分）。1件でも不合格なら `LEGACY_2_1_11_PREFLIGHT_FAILED` で起動を拒否し、該当UUIDと理由をlogへ出します（row内容は出しません）。DBは変更されません。
- `legacy211QuarantineUndecodableRows=true` の場合、不合格rowにはprovenanceを付けず、そのPlayerだけlogin時に `DOMAIN_PROVENANCE_UNKNOWN_REQUIRES_RECONCILIATION` で止まります。件数はmigration recordの`quarantined_rows`に残ります。
- **stack数の検証はそのbackendのmod／ruleに依存します。** LOADは各itemの最大stack数を超えるcountをfail closedで拒否します（2.1.11と同じ規則）。たとえばcarpetの`stackableShulkerBoxes`やessentialaddonsの`stackableShulkersInPlayerInventories`で重ねたshulker boxを持つPlayerは、同じruleが有効でないbackendではloginできません。全backendで、item／registry／stack数に影響するmodとruleを移行前と同一にしてください。production DBの複製でリハーサルする場合も同じmod／ruleを入れてください。
- item／effectのIDがそのserverのregistryに存在するかは起動時点では判定できません。未知IDを含むrowは、従来どおりそのPlayerのLOADがfail closedします（部分適用はしません）。
- coordination rowを持たないPlayerDataには、bootstrapと同じtransactionで **synthetic revision 1**（fence 0、`last_checkpoint_at`＝そのrowの最終書込み時刻）を与えます。採用したrow自体がdurable checkpointなので、READY直後・最初のcheckpoint前にbackendが落ちても通常のrecovery対象になります。最初のSAVEはrevision 2です。既存のcoordination rowのrevision・fenceは変更しません。件数は`synthetic_revision_rows`に残ります。

#### 移行前から残るbacklogのautomatic recovery

2.1.11時代に既にRECOVERY_REQUIREDだったPlayer、および2.1.11 backendが落ちてowner付きのまま残ったPlayerは、`recovery.mode=automatic`（明示ack付き）であれば**operator commandなしで**復旧します。

- 通常のcrashには `recovery.maxCheckpointAgeSeconds`（default 30秒）が適用されます。
- 「完了済みの承認付き2.1.11 bootstrapが存在し、そのrowのcheckpointが2.2.xによって一度も書き換えられておらず（producer versionなし）、bootstrap完了より新しくない」とDBから証明できるrowにだけ、`migration.legacy211RecoveryMaxCheckpointAgeSeconds`が適用されます。2.2.8が一度でもSAVEしたPlayerは以後ずっと通常ageです。
- active owner、未失効lease、stale fence、revision CASの各guardは通常のautomatic recoveryと同じです。auditには`source_version=2.1.11-legacy`が残ります。

保留が続くrowはbackground scanとlogin retryのたびに再評価されますが、logは「最初の保留」「状態が変わったとき」「60秒ごとの要約（その間の回数`deferrals=`／`evaluations=`付き）」だけです。

試行回数（`maxAutoRecoveryAttempts`）は、実際にresolution transactionを開始した場合にだけ消費します。checkpointが古い（`STALE_CHECKPOINT`）、delay待ち、retry間隔待ち、owner live等の**保留では消費しません**。以前のversionで保留だけで使い切ったrowは自動resetしません。確認の上で `/pdc recovery reset-attempts <uuid> <fence> <revision> <operation-uuid> confirm-attempts-were-policy-deferrals`（console専用・audit記録・recovery state自体は不変）を使ってください。

#### cluster fingerprint（全backend）

bootstrap完了後は、そのDBへ接続する**すべての有効なbackend**が、migration承認flagの有無に関係なく、起動時にcluster fingerprintを照合されます。fingerprintは「provenance形式version＋16 domainのenabled状態＋記録済みのlegacy new-domain policy」のSHA-256で、serverIdやlease、log設定は含みません。一致しないbackendは `CLUSTER_DOMAIN_FINGERPRINT_MISMATCH` で起動を拒否され、Playerを1人もLOADできません。

domain設定をcluster全体で意図的に変える場合: 全backendを停止 → 全backendへ同じ`sync.*`を設定 → 起動拒否messageに表示された新しいfingerprintを全backendの `migration.acknowledgeClusterDomainFingerprint` に設定 → 起動。変更時刻はmigration recordに残ります。これはclusterの整合性だけを更新する操作で、変更したdomainにはPlayerごとの通常のprovenance rule（OFF→ONはreconciliation）が引き続き適用されます。

#### rollback

- **bootstrap前**（起動拒否の段階）: DBは未変更です。JARを2.1.11へ戻すだけです。
- **bootstrap後、Playerがまだloginしていない**: 2.2.8を全台停止し、移行前に取得したDB backupをrestoreしてから2.1.11へ戻します。2.2.8が追加した列・tableは2.1.11の動作を妨げませんが、backupからのrestoreを推奨します。
- **Playerが2.2.8でlogin／SAVEした後**: DBには2.2.8形式の進捗・統計・recipe・profileと新しいrevisionが入っています。2.1.11はそれらを理解せず、placeholderで上書きします。online rollbackはできません。全台停止 → DBとWorldの両方を移行前backupへ戻す（移行後のplayは失われます）か、戻さず前進してください。
- いずれの場合も、2.1.11と2.2.8を同じDBへ同時に接続させないでください。

### `enable=false`からの再有効化

`enable=false`はDBへ一切接続しないため、その期間にlocalで変わったPlayer stateを共有DBのprovenanceは知り得ません。2.2.7は無効起動時にlocal fileの `config/playerdataconnector-disabled.marker`（`markerId`／`serverId`／`disabledAt`／`version`）だけを書きます。markerが残った状態で`enable=true`にすると `PDC_REACTIVATION_ACKNOWLEDGEMENT_REQUIRED` で起動を拒否し、表示された`markerId`を`migration.acknowledgeReactivationMarker`へ設定した場合のみ起動します（markerは削除され、同じ値は次回の無効期間には通用しません）。承認は「無効期間のlocal変更はlogin時にDB値で置換される」ことを受け入れる操作であり、mergeは行いません。local側を残したい場合は、先にsource-reviewed recoveryで採用してください。

これは**設定による無効化**だけを対象とします。JAR自体を外していた期間はmarkerを作れないため、同じguardは働きません。JARを外して運用した後に戻す場合は、operatorが同じ確認を手動で行う必要があります。

### 同期無効項目

例えば`enderchest=false`の場合、UPSERTの対象列から`enderchest`自体を除外します。`null`やローカルのデフォルト値で既存DBを上書きすることはありません。Armor、Offhand、Effects、Position、GameMode、Advancements、Statisticsも同じルールです。item domainを全OFFにした場合はpending列も対象外です。

1.18.2の`HungerManager`にはexhaustionのgetter/setterがあるため、food同期では`food_level`、`saturation`、`exhaustion`を取得・検証・適用します。既存JARのinventory JSON形式（`slot`、`item`、`count`、`damage`、`nbt`）も維持しています。

### データベース変更

既存の`player_data`はDROP/TRUNCATEしません。起動時に、指定されたplayer table名から導出したcoordination tableを作成します。

```sql
<player_table>_sync_state
(
  uuid VARCHAR(36) PRIMARY KEY,
  owner_server VARCHAR(64) NULL,
  owner_session CHAR(36) NULL,
  fencing_token BIGINT UNSIGNED NOT NULL DEFAULT 0,
  lease_until DATETIME(6) NULL,
  data_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
  recovery_required BOOLEAN NOT NULL DEFAULT FALSE,
  recovery_reason VARCHAR(64) NULL,
  recovery_required_at DATETIME(6) NULL,
  last_checkpoint_at DATETIME(6) NULL,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6)
)
```

テーブル名は`[A-Za-z0-9_]+`だけを許可します。coordination tableの行ロックをbackoff中に保持しません。

復旧audit用InnoDB tableも作成します（通常`<coordination>_recovery_audit`、長い名前の場合はcoordination名のSHA-256から導出した`pdc_rec_audit_*`）。previous owner/session/fence/revision、reason、operator、resolution、operation UUID、resolved fence/revisionを保存します。legacyのowner/session/leaseがすべてNULLのrowのみclean/freeと解釈し、既存expired ownerは自動cleanにしません。migrationまたはengine検証失敗時はwriter起動を拒否します。

## イベントの扱い

- JOIN: ownership acquire → DB load → server threadで事前検証・適用 → `READY`。DB rowがない場合は、現在のMinecraft stateをinitial snapshotとして保存し、commit後に`READY`。
- DISCONNECT: `LOADING`中は保存しません。`READY`ならserver threadでtransient itemを含むimmutable final snapshotを作り、cursor／crafting inputをdropせず閉じてからper-player queueへ渡します。PlayerData更新とownership解放は同一transactionでcommitし、失敗時にleaseを先行releaseしません。
- PERIODIC: server threadでPlayerInventoryとactive transientをread-only snapshotし、durable pending + 現在のtransientからpending overlayを再構成してDBへ置換保存します。DB処理だけを非同期化します。
- DEATH: 設定が有効ならsnapshotを通常の直列saveに入れます。
- RESPAWN / DIMENSION: 同じbackend/sessionのownershipを取り直さず、current Player参照を更新します。dimension changeのsaveは設定が有効な場合だけ行い、即時LOADのround-tripは行いません。
- SHUTDOWN: periodic taskを止め、新しいJOIN受付を止め、server threadでfinal snapshotを取得してbounded waitします。成功だけclean release、failure/timeoutはunclean recoveryです。Hikari datasourceはDB worker停止後に閉じます。
- DISCONNECT中の`FLUSHING` contextはfinal transactionのcommit／失敗までsession mapに保持します。これにより切断処理と再接続の世代境界が曖昧にならず、S2はS1のfinal transactionの後ろでownershipを待ちます。

## 失敗時の挙動

- DB LOAD失敗、timeout、deserialize失敗では`READY`にせず、saveを禁止してbounded retry後にPlayerを切断します。
- 同期対象列がDB上で`NULL`/空になっている場合も、local/default stateをauthoritativeとみなさずfail closedします。
- 復元データはclear前に全slot・ItemStack・registry・数値rangeを検証します。apply途中の例外でも変更前のlocal stateへrollbackを試み、saveは禁止します。
- lease renewal失敗が安全期限を越えた場合は`DEGRADED`として切断します。
- 古いfencing tokenのSAVE/renew/releaseはDB条件により拒否されます。
- leaseのローカルwatchdogは、server callbackの到着時刻ではなくDB worker側の保守的な処理開始時刻を基準にします。処理が長引いた場合は、可用性よりsplit-brain回避を優先して切断します。

## ビルドとテスト

```bash
./gradlew test build --no-daemon
```

JUnitではper-player save順序、異なるPlayerの並列性、例外後のqueue継続、即時再接続時のstale session無効化、設定値検証をdeterministicに確認しています。MySQL integrationは、専用DBを明示した場合だけ有効です。InnoDBの同時acquire、clean final handoff、unclean expiry拒否、復旧audit/idempotency/CAS、verified-source transaction rollback、fencing、deadlock、lock wait timeout、fault injectionを確認します。従来のfinal failure後automatic takeover試験は、拒否→明示的なoperator resolution→再取得の期待値へ変更しています。

```bash
PDC_TEST_MYSQL_DATABASE=playerdataconnector_test \
PDC_TEST_MYSQL_HOST=127.0.0.1 \
PDC_TEST_MYSQL_USERNAME=tester \
PDC_TEST_MYSQL_PASSWORD='...' \
./gradlew test --no-daemon
```

この統合テストはランダムな`pdc_it_*` tableを作成して最後に削除します。production DBの接続情報を指定しないでください。

## 限界

MySQLとMinecraft Worldは同一transactionではなく、exactly-once journalはありません。manual/safeは不確定stateを止めますが、automaticは承認済みcheckpoint復旧によるWorld側dupe/loss/rollbackを許容します。DB authority排他、fence、transaction atomicityは全modeで維持します。local World/vanilla file、独立writer、未知Mod GUIの完全保全は保証しません。mixed writerは禁止し、item-domain再ONやunsafe recoveryにはoperatorのreconciliationが必要です。
