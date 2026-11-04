# Frozen migration inputs / 固定移行試験入力

These three historical PlayerDataConnector JARs are used by the real-MySQL migration and
interoperability tests through an isolated class loader. They are necessary fixed regression
inputs, not build output, current release assets, or recommended server downloads.

`manifest.json` records the original version, byte size and SHA-256. Tests also assert the original
hashes before loading classes. `tools/project.py check` checks the manifest, ZIP CRC and metadata.
Do not rebuild, relabel, update their licenses/metadata, or replace them with a current JAR:
that would stop testing what the old writers actually did. All current StateLink distribution JARs
are built separately and the release verifier rejects these files in a release bundle.

The test fixtures contain Mod code only, not production DB/player/world/configuration data.
Their historical Mod metadata is preserved; the 2.4.2 license update applies to newly built
StateLink JARs. Bundled dependencies retain their original notices and license terms.

実MySQL移行試験が過去のwriterそのものを実行するための固定入力です。現在の生成物とは
区別し、この3本だけを試験資料として保持します。配布JAR・Releaseには混ぜません。
元の版・ハッシュ・ライセンス情報は変更せず、テストのために過去版を再ビルドしません。
本番のDB・PlayerData・World・設定は含みません。
