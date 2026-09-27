# Phase 2F — 実データ互換性の追加検証

## 判定

**PARTIAL — REAL DATABASE VALIDATION INCOMPLETE**

この実行では、ローカルに残る H2 1.4.200 の NIS performance fixture を原本から分離して export/import し、H2 2.2.220 上で Hibernate 読み取り、全テーブルの論理データ比較、Flyway 3.2.1 / 9.22.3 の history 認識を検証した。Mainnet または Testnet の代表的な実チェーン DB は利用できず、matching-genesis 比較もできない。したがって production DB rollout の gate は未解除である。

## 実行情報

- Repository: `nemnesia/nem`
- Branch: `agent/nis-phase0-baseline`
- Requested / actual starting HEAD: `2dc92ad366f32dd02c3a9358a760d9b3ad0b5787`
- Java: OpenJDK `17.0.20.1`
- Maven: `3.8.7`
- H2: `1.4.200` → `2.2.220`
- Flyway: `3.2.1` → `9.22.3`
- Hibernate: `5.4.33.Final`
- Final HEAD: この記録を含む phase completion commit（具体的な SHA は最終報告）
- 変更範囲: 本文書のみ。production code、dependency、schema、migration SQL、Flyway metadata は変更していない。

## DB artifact inventory と保護

`/home/harvestasya/nem/nis/data` と workspace の modernization 記録を調べた。見つかった DB は `test.mv.db`（509,284,352 bytes）、`nis5_mijinnet.mv.db`（20,480 bytes）、`h2_speed_test.mv.db`（20,480 bytes）だった。完全な Mainnet / Testnet DB copy は見つからなかった。

`test.mv.db` は Phase 2H / baseline の記録どおり、integration run が作成した 5,000 block の synthetic performance fixture であり、実ネットワークの代表 snapshot ではない。block version の network byte は Testnet (`0x98`) だが、DB の genesis block hash `a590926ece7cb9aee4e72ba6b9617b9861265e4929c4e41e91d6e9da983cdc88` は repository の Testnet nemesis hash `33496b75b6e5827cd11f50070df2dd38b31e20398b166cb719dd544d4844ed59` と一致しない。`nis5_mijinnet.mv.db` と `h2_speed_test.mv.db` に application table data はなかった。従って、いずれも Mainnet/Testnet 実 DB として扱っていない。

原本には接続せず、`test.mv.db` と空の Mijin DB を `/tmp/phase2f-realdb/original-copies/` に複製して検証した。検証前後の原本 SHA-256 は変わらなかった。

| 原本 | 検証前後 SHA-256 |
| --- | --- |
| `test.mv.db` | `6e68d1a1c604e2bed0af9c0feb6ac17a6d60991f30d5ac8fc2bc408cf710184e` |
| `nis5_mijinnet.mv.db` | `f3712f2efce98178d8b506351c2be41cc306c3c7426579af697c42c6f88d7ac5` |

H2 1.4 はコピーを開いた際にその copy を checkpoint したため copy の file hash は変化したが、これは disposable copy 上の file-level 書き込みであり、元 DB には書き込んでいない。データの論理比較は後述のとおり一致した。

## H2 1.4 → 2.2 offline conversion

H2 1.4.200 で disposable copy を開き、`SCRIPT TO` で SQL export（約194 MiB）を作成した。新規の空 DB に H2 2.2.220 `RunScript` の `FROM_1X` option で import した。production URL と同じ `MODE=LEGACY;NON_KEYWORDS=VALUE` を付けて H2 2.2 DB を開いた。H2 1.4 file format を H2 2.2 で直接開く方法は使っていない。これは H2 の公式 1.x → 2.x 手順（旧 engine で SQL export、新 DB へ import）に沿う。

- H2 1.4 source copy: 約486 MiB
- SQL export: 約194 MiB
- H2 2.2 converted DB: 約166 MiB
- H2 2.2 Hibernate `SessionFactoryLoader.load(DataSource)` 起動: 成功
- Hibernate HQL `select max(b.height) from DbBlock b`: `5000`
- Hibernate/JDBC read: 5,000 blocks、100 accounts、475,018 transfers
- production NIS chain service の完全起動は行っていない。fixture genesis が既知 Testnet genesis と異なるため、実チェーンとしてロードするのは適切でない。

H2 1.4 と変換後 H2 2.2 を別々の JDBC process / driver で読み、全19テーブル（18 application table と `schema_version`）を主キー順に走査した。各行の全列を型付き canonical representation にし、テーブルごとの row count と SHA-256 を比較した。19テーブルすべてで count / digest が一致した。主な件数は `BLOCKS=5000`、`ACCOUNTS=100`、`TRANSFERS=475018`、`IMPORTANCETRANSFERS=24982`、`MULTISIGSENDS=74993`、`MULTISIGRECEIVES=74993`、`MULTISIGTRANSACTIONS=74993`、`MULTISIGSIGNATURES=149986`。全テーブル digest の生出力は `/tmp` にのみ置き、DB data とともに commit しない。

変換前後で次の chain marker が一致した。

| 指標 | H2 1.4 copy | H2 2.2 converted copy |
| --- | --- | --- |
| block count / height range | 5,000 / 1–5,000 | 5,000 / 1–5,000 |
| accounts | 100 | 100 |
| transfers | 475,018 | 475,018 |
| height 1 block hash | `a590926ece7cb9aee4e72ba6b9617b9861265e4929c4e41e91d6e9da983cdc88` | 同一 |
| height 5,000 block hash | `1c1fd47b929ff8628727048aa5ad36de4493347bace25ee07fa698ba3acdc1c7` | 同一 |
| all table row digests | 19/19 identical | 19/19 identical |

これは当該 fixture のデータ保持を証明するが、Mainnet/Testnet matching-genesis equivalence を証明しない。Mainnet DB と Testnet DB の相互 network mismatch startup test も実施できていない。

## Flyway 3.2.1 / 9.22.3

disposable H2 1.4 copy に対し Flyway `3.2.1` の `validate()` を現在の `db/h2` scripts と実行し、8/8 migration が PASS した。history は `schema_version`、version `1.0.0`–`1.0.7`、全行 `SUCCESS=true` だった。stored checksum は次のとおり。

| Version | stored checksum |
| --- | ---: |
| 1.0.0 | 1235287926 |
| 1.0.1 | -867359331 |
| 1.0.2 | 88353754 |
| 1.0.3 | 1899963525 |
| 1.0.4 | -1352422778 |
| 1.0.5 | 1524875301 |
| 1.0.6 | -1123275759 |
| 1.0.7 | 1393077514 |

変換後 DB に対する Flyway `9.22.3` の直接 `validate()` は8件すべて checksum mismatch を返した。Stored → current Flyway 9 resolved values:

| Version | Stored | Flyway 9 resolved |
| --- | ---: | ---: |
| 1.0.0 | 1235287926 | -1137050968 |
| 1.0.1 | -867359331 | -1083108641 |
| 1.0.2 | 88353754 | 1259780587 |
| 1.0.3 | 1899963525 | 57641099 |
| 1.0.4 | -1352422778 | -1433398904 |
| 1.0.5 | 1524875301 | -378188078 |
| 1.0.6 | -1123275759 | -1095467556 |
| 1.0.7 | 1393077514 | -1269544226 |

Flyway 3.2.1 が同じ現在の migration files を validate したため、今回の証拠から SQL が old checksum と食い違う形に編集されたとは判断できない。一方 Flyway 9 の resolved checksum は全件異なる。従ってこの generation 間の validate 互換性は成立していない。どの歴史的コード変更が checksum を変えたかはこの実行では確定していない。Git history は migration file に過去の変更があることを示すが、これだけで8件すべての mismatch 原因とは断定しない。

repository の production config は `schema_version` を継続使用し、`flyway.validate` property が未設定の場合 `validateOnMigrate=false` となる。変換後 copy に対して同じ設定で Flyway 9 `migrate()` を実行したところ、current schema `1.0.7`、executed migrations `0`、success `true` で no-op だった。実行前後で8 history row の version / description / type / script / checksum / success は同一だった。`repair`、baseline、metadata の手編集、validate の無効化による gate の偽装は行っていない。production の `validateOnMigrate=false` は既存設定として観測したものであり、checksum validation が通ったことを意味しない。

checksum mismatch は rollout 時に無視できない運用上の制約として残す。migration history を変更せずに strict `validate()` を通す方法は、この Phase では実証されていない。

## 実施した validation と未実施項目

- Java 17 / Maven 3.8.7: `mvn -B -pl nis dependency:build-classpath ...` 成功。
- Java 17: `mvn -B -pl nis -am -DskipTests package` 成功（Core / Deploy / Peer / NIS）。
- Java 17: H2 1.4 export、H2 2.2 `FROM_1X` import、Flyway 3 validate、Flyway 9 validate failure と no-op migrate、Hibernate SessionFactory/HQL read を実行。
- Java 17 full clean test は未実施。本 Phase では repository code を変更せず、既存 DB migration path の targeted probe を実施した。
- Java 25 DB validation は未実施。
- Hosted CI はこの documentation-only change では起動していない。
- NIS production process の full startup、write behavior、Mainnet/Testnet chain load は未実施。

## 残存 gate

以下をすべて未解決のまま維持する。

1. operator から提供された、原本から分離した代表 Mainnet DB copy と代表 Testnet DB copy を用いた H2 1.4 export/import。
2. strict Flyway checksum validation の generation mismatch を、migration history を repair/書換えせずどう運用するかの判断。現在の証拠は production `validateOnMigrate=false` で no-op migrate となることまで。
3. 代表 DB 上の full NIS startup、network/genesis recognition、latest known height/hash、DAO/state reads。
4. matching-genesis の Mainnet/Testnet snapshot を使った conversion 前後の full chain-state comparison と network mismatch detection。

よって H2 export/import がこの synthetic fixture ではデータを保持したという証拠は得られたが、実データ gate の完了ではない。実 Mainnet/Testnet copy と matching-genesis 比較が得られるまで DB rollout gate を維持する。

## 参照

- [H2 1.4 から 2.x への migration guide](https://h2database.com/html/migration-to-v2.html)
- [H2 SQL commands (`RUNSCRIPT FROM_1X`)](https://h2database.com/html/commands.html)
- [Flyway schema history table](https://documentation.red-gate.com/fd/flyway-schema-history-table-273973417.html)
- [Flyway validate](https://documentation.red-gate.com/fd/validate-277578898.html)
