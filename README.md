# Navigation Overlay — ナビオーバーレイ（BRouter内蔵試作版）

Android 12以降向け v0.2.0。BRouter v1.7.10 のJavaエンジンをAPK内に統合する構成。別アプリのインストール・起動・サービス設定は不要。ビルド時にソースコード、プロファイル、地域のrd5を取得しAPKに含め、初回探索時に端末内に展開する。アプリには INTERNET 権限なし。

## Windowsでビルド・インストール
JDK 17、Android SDK 35、Gradle 8.9（またはGradle wrapper）、Git、adbを準備。初回ビルド時はPCにインターネット接続と十分な空き容量が必要。

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

標準地域は `E135_N35`（東京周辺）。隣接地域も必要なら `-Tiles 'E135_N35,E140_N35,E135_N30'` を付ける。別途 `gradle :app:assembleDebug -PbrouterTiles=E135_N35` でもビルド可。キャッシュを削除して再ビルドすると経路データを更新できる。APKサイズは選んだ地域データのサイズに依存し、初回展開時に追加のストレージが必要。全国データは同梱しない。

目的地の名前や住所を入力し、「地名・住所を検索」を押す。検索結果が1件なら緯度・経度を入力し、複数件なら候補から選べる。検索は端末の Android Geocoder を使い、通信が必要な場合がある。緯度・経度を手入力する方法も使える。正確な位置情報、通知、オーバーレイ権限を許可してナビを開始する。徒歩は上流の hiking-mountain プロファイル（街歩き専用の調整は未実施）、自転車は trekking を使用する。

## 機能
次の方向、距離、残距離、GPS追跡、経路逸脱時の再探索、バイブレーション、到着終了、表示位置・文字サイズ、住所・施設名の検索、登録地点と座標付きgeoリンク、CSV取込。

現在地の取得には GPS と端末の融合位置情報を使う。案内には15秒以内、推定誤差45m以内の位置を使用する。位置情報が無効な場合や、受信待ち・精度不足の場合は、その状態をオーバーレイに表示する。

「進む向き」は端末の方位と経路を照合して8方向で表示する。画面を上に向けたときは画面上端、立てて持ったときはカメラ側を正面とする。次の交差点の案内と距離はその下に表示する。

## 目的地CSV
UTF-8、ヘッダー `name,lat,lon`、1行につき1地点。カンマや改行を含む名前、引用符付きCSVは非対応。同名を更新する。最大1万行/取込。座標は WGS84 の十進度。座標は地図アプリなどで確認すること。

```csv
name,lat,lon
テスト地点,35.000000,139.000000
```

## 実装・ライセンス
- EmbeddedRouter: BRouterのローカルJava API、プロファイル/rd5の端末内展開。外部AIDL接続を削除。
- NavService, MainActivity, Route: 既存のGPS案内とオーバーレイを継承。
- BRouter は MIT License。ビルド時に上流LICENSEをAPKの assets/brouter にコピーする。経路データ © OpenStreetMap contributors (https://www.openstreetmap.org/copyright)。公開配布前にはデータ再配布条件を確認すること。
- 参照: https://github.com/abrensch/brouter/tree/v1.7.10 と https://zod.github.io/brouter/users/download_segments.html

## 検証状況
純Javaの経路案内テストは実行済み。BRouter本体とrd5のダウンロード、Androidビルド、端末インストール、実ルート、プロファイルの適合性、機内モード動作は未検証。この環境は外部Gitアクセス・Android SDKがないため。GitHub Actionsでビルド検証できる構成。
道路状況・標識を優先し、自転車走行中は操作しないこと。
