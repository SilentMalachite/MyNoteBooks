# MyNoteBooks

ページをブロックの並びとして書く、Notion 風のローカルノートアプリです。Kotlin Multiplatform と Compose Multiplatform で作っており、Windows・macOS のデスクトップ版と Web 版が同じ UI コードで動きます。データは端末内の SQLite に保存し、サーバーやアカウントは使いません。

## できること

- ページをブロックの並びとして編集する。ブロックの種類はテキスト、見出し、To-do、箇条書き、ページリンクの5つ
- Enter で、カーソルのあるブロックの下にテキストブロックを追加する
- 空のブロックで Backspace を押すか、種類メニューの「Delete」でブロックを削除する。ページには最低1つのブロックが残る
- サイドバーのツリーからページを開く。「New」で開いているページの子ページを作る
- ヘッダーの「Delete」で、確認のうえ開いているページを削除する。子ページは親の位置へ繰り上がる
- タイトルでページを検索する（本文は検索対象外）
- 入力が 400 ms 止まると自動で保存する。ページとブロックは1つのトランザクションで書き込む

## 技術構成

| 項目 | 内容 |
| --- | --- |
| 言語 | Kotlin 2.4 |
| UI | Compose Multiplatform 1.12（`commonMain` の1つの UI を全ターゲットで使う） |
| DB | SQLDelight 2.4（非同期 API） |
| デスクトップ | JVM ターゲット + JDBC SQLite ドライバ |
| Web | wasmJs ターゲット + `@sqlite.org/sqlite-wasm` を Web Worker 上で動かし、OPFS に保存 |

## 必要なもの

- JDK 17 以上（Gradle の実行用）。ビルドには JDK 21 を使い、手元になければ Gradle が自動でダウンロードします
- Web 版のビルド時はインターネット接続（npm パッケージを取得するため）

## 起動方法

### デスクトップ版

```sh
./gradlew :composeApp:run
```

データベースは `~/.notebook/notebook.db` に作られます。

### Web 版（開発サーバー）

```sh
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

起動したら http://localhost:8080 を開きます。開発サーバーは OPFS に必要な COOP/COEP ヘッダーを付けて配信します。OPFS が使えないブラウザではメモリ上の DB で動き、タブを閉じるとノートが消える旨の警告を画面上部に表示します。

## テスト

```sh
./gradlew :composeApp:jvmTest
```

## 配布物の作成

### デスクトップ

```sh
./gradlew :composeApp:packageDmg   # macOS
./gradlew :composeApp:packageMsi   # Windows
./gradlew :composeApp:packageDeb   # Linux
```

各 OS のパッケージは、その OS 上でビルドします。出力先は `composeApp/build/compose/binaries/` です。

### Web

```sh
./gradlew :composeApp:wasmJsBrowserDistribution
```

`composeApp/build/dist/wasmJs/productionExecutable/` の中身を静的ファイルとして配信します。

**本番サーバーでは、次の2つのレスポンスヘッダーを必ず付けてください。**

```
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
```

ヘッダーがないと OPFS を使えず、データはメモリ上にしか残りません。タブを閉じるかページを再読み込みすると消えます。あわせて `.wasm` ファイルを `application/wasm` として配信してください。

## データの保存先

| ターゲット | 保存先 |
| --- | --- |
| デスクトップ | `~/.notebook/notebook.db` |
| Web | ブラウザの OPFS 内の `/notebook.db`（オリジンごとに別のデータ） |

Web 版では、タブが非表示になったときやページを離れるときに、未保存の変更を Worker へ送って書き込みます。

## ディレクトリ構成

```
composeApp/src/
├── commonMain/
│   ├── kotlin/app/
│   │   ├── App.kt, Workspace.kt   画面全体と状態管理
│   │   ├── data/                  ページ・ブロックの読み書き
│   │   ├── editor/                ブロックエディタと自動保存
│   │   ├── nav/                   ページツリー
│   │   └── search/                タイトル検索
│   └── sqldelight/app/db/Notes.sq テーブル定義とクエリ
├── jvmMain/                       デスクトップ用ドライバとエントリポイント
├── wasmJsMain/                    Web 用ドライバ、SQLite Worker、index.html
└── jvmTest/                       テスト
docs/plan.html                     設計計画
```

## 現在のバージョンにない機能

- 同期、アカウント、ログイン
- 画像やファイルの添付、ゴミ箱の表示
- ボード・テーブル・カレンダー表示、コメント、共有リンク
- 複数人での同時編集
- iOS / Android アプリ

## 既知の注意点

- 日本語などの IME 入力は、Web 版で十分に試されていません。変換を確定する Enter でブロックが増えないかを、リリース前に確認してください。

## ライセンス

[Apache License 2.0](LICENSE) で公開しています。

Copyright 2026 Silent Malachite
