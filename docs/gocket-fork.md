# gocket fork：Pocket 与 Root 的固定约定

本仓库是 [ccch1mneyyy/dsh-Go](https://github.com/ccch1mneyyy/dsh-Go) 的 fork（`Mrsirlila/dsh-gocket`，分支 `gocket`）。
相对上游我们长期维护两件事：**DSH Pocket 主机**（局域网 / Tailscale / 公网连自己的电脑）与**用 root 代替无障碍**（AI Shell 与手机操控）。
每次跟上游合并都必须把它们带回来，本文就是这个检查单。改动这两块功能时先读这里。

## 跟上游的关系

| 项 | 值 |
| --- | --- |
| origin | `https://github.com/Mrsirlila/dsh-gocket` |
| upstream | `https://github.com/ccch1mneyyy/dsh-Go` |
| 分支 | `gocket`（交付分支**兼默认分支**；`main` 只是上游镜像，不要直接在上面改） |
| 更新源 | `app/lib/update/update_config.dart` 的 `const updateRepo = 'Mrsirlila/dsh-gocket';` |

### 版本号规则

上游发 `vX.Y.Z` 时，我们发 **`X.Y.(Z+1)`**（例如上游 1.4.1 → 我们 1.4.2）。

原因不是好看：`AppVersion` 按 semver 比较，`1.4.1-gocket.1` 这种预发布**永远小于** `1.4.1`。
只要本地版本低于官方版，`UpdateChecker` 就会继续问官方下载源并提示客户装官方包，而官方包签名不同、装不上。所以 gocket 标识只放在 Release 标题与 About 署名里，不进版本名。

构建时还要关掉官方更新源，只认自己的 Release：

```bash
flutter build apk --release --flavor arm64 --target-platform android-arm64 \
  --build-name <X.Y.Z> --build-number <tooling/build_number.py 的值> \
  --dart-define=DSH_APP_VERSION=<X.Y.Z>+<build-number> \
  --dart-define=DSH_UPDATE_MIRROR= --dart-define=DSH_UPDATE_FEED=
```

## Pocket 主机（必须一直保留）

Pocket 让手机直连装了 `dsh-pocket` 插件的电脑，三条线路（局域网 / Tailscale / 公网）自动切换。

### 要带回来的文件

- `app/lib/remote/pocket/pocket_host_config.dart`、`pocket_link.dart`
- `app/lib/ui/remote/pocket_host_form.dart`、`pocket_qr_scanner.dart`
- `app/lib/l10n/parts/pocket_zh.arb`、`pocket_en.arb`
- `app/test/remote/pocket_link_test.dart`、`pocket_qr_scanner_test.dart`

### 必须存在的接线

| 文件 | 内容 |
| --- | --- |
| `app/lib/state/hosts_state.dart` | `enum HostKind { local, ssh, relay, pocket }`、`HostRef.pocket`、`hostLabel` 的 pocket 分支、`HostsState.pocket` / `pocketById`、`pocketPrefsKey = 'remote_pocket_hosts'`、`savePocket` / `loadPocketSecrets` / `applyPocketEntry` / `removePocket` |
| `app/lib/state/connection_state.dart` | `pocketLinkFactoryProvider`、`hostLinkProvider` 的 `case HostKind.pocket`（含 `ref.listen(...?.mode)` → `retry()`）、`hostNameProvider` 与 `currentConnectionProvider` 的 pocket 分支 |
| `app/lib/ui/remote/host_switcher.dart` | 主机切换 sheet 要列 Pocket 主机（`hostsProvider.select((s) => s.pocket)`，空判 `ssh.isEmpty && pocket.isEmpty && relayHosts.isEmpty`） |
| `app/lib/ui/remote/remote_screen.dart` | 远程页**以 Pocket 为主**：第一张卡是 DSH Pocket（主机列表 + 添加行），DSH Remote 收进后面的「其他方式：DSH Remote」卡 |
| `app/lib/ui/web/dsh_web_launcher.dart` | `HostKind.pocket` 返回 `NoDshWeb(appL10n.pocketWebUnavailable)`，同时保留上游 relay 的 `RelayDshWeb` 支持 |
| `app/lib/state/island_gate.dart` | `HostKind.relay \|\| HostKind.pocket => false` |
| `app/lib/ui/remote/host_status.dart` | pocket 用 `AppIcons.computer` 与 `tint: 3` |
| `app/lib/state/notify_state.dart`、`read_list_identity.dart` | `HostRef.pocket(id)` / `ReadListIdentity.of(['pocket', host.id, epoch])` |
| `app/lib/state/system_entry_host.dart`、`app/lib/ui/web/web_home.dart` | 指向已删除 Pocket 主机的快捷方式要报「gone」 |
| `app/lib/ui/settings/settings_screen.dart` | 主机计数 `s.ssh.length + s.pocket.length` |
| `app/lib/ui/settings/index/spots.dart` | `Spots.pocketAddHost` 注册进设置索引与搜索 |
| `packages/dsh_client/lib/src/connection.dart` | `DshCookie.companions`（`header` 拼 `k=v`）与 `loginViaProxy({String? pin})`（PIN 换 launch token）；测试 `packages/dsh_client/test/proxy_login_test.dart` |

### 合并后自检

```bash
git grep -n "HostKind.pocket" app/lib | wc -l      # 应为十几处，不该是 0
git grep -n "pocketAddHost" app/lib                # Spots 与 add 行都在
(cd app && flutter test --no-pub test/remote/pocket_link_test.dart \
  test/remote/pocket_qr_scanner_test.dart test/remote/remote_widgets_test.dart)
```

## Root 权限（必须一直保留）

目标：**rooted 手机完全不用开无障碍**就能读屏、点按。三条链路各自独立，别互相替代。

### 1. AI Shell 的 root 后端

`app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/shell/`：

- `ShellRootBackend.kt`：`su -c "CLASSPATH=<cp> exec /system/bin/app_process /system/bin <AndroidShellMain>"` 起进程，stdio 交给与 ADB 后端相同的 `ShellWire` 帧协议；`available()` 先看 `SU_PATHS` 可执行位，再退回 `sh -c "command -v su"`（`SU_PATHS` 含 `/system/bin/su`、`/system/xbin/su`、`/sbin/su`、`/su/bin/su`、`/debug_ramdisk/su`、`/data/adb/ksu/bin/su`、`/data/adb/ap/bin/su`）
- `ShellLink.kt` 的 `fun process(process: java.lang.Process): ShellLink`（backend 名 `"root"`）
- `ShellProtocol.kt` 的 `validUid`：`"root" -> uid == 0`
- `AndroidShellController.kt`：backend 接受 `"root"`、`rootStatus`、`rootMissing` 原因、`connection()` / `cancel()` / `cancelAll()` 的 root 分支
- `PhoneAndroidShell.kt` 的授权判断 `|| backend == "root" && uid == 0`

### 2. 手机操控的 root 驱动

- `app/android/app/src/main/kotlin/io/github/ccch1mneyyy/dsh_app/phone/root/RootScreenDriver.kt`：实现 `PhoneDriver`，用 `uiautomator dump --compressed` 读屏（XML → `UiNode` 树，交给 `ScreenFormat.format` 复用同一套文本与安全标记），用 `input tap|swipe|text|keyevent` 点按，`monkey` 启动 App，`dumpsys` 取前台包名
- `PhoneControl.kt` 的 `driverForTask` MAIN 分支：`return PhoneRegistry.mainDriver ?: rootScreen.takeIf { rootEnabled() }`
- `PhoneControl.testScreen()` 里 driver 身份判定要用 `(PhoneRegistry.mainDriver ?: rootScreen) === driver`

root 模式的边界（不要承诺更多）：只读一屏不翻页、没有截图与 OCR、没有悬浮层；后台屏 / 不打扰仍要无障碍。

### 3. 开关（两个地方都能关）

- 原生偏好 `dsh_phone` / `rootEnabled`（默认 `true`）：`PhoneControl.rootEnabled()` / `setRootEnabled()`，`captureStatus()` 返回 `root: {enabled, su, available}`
- 原生 IPC：`PhonePlugin.kt` 的 `"setRootEnabled"`
- Dart：`PhoneStatus.rootEnabled` / `rootAvailable` / `rootSu`、`PhoneChannel.setRootEnabled(bool)`
- 界面：`app/lib/ui/phone/phone_control_screen.dart` 的 `RootSwitchRow`（手机操控页顶部第一张卡）；`app/lib/ui/settings/settings_screen.dart` 设置首页「能力」分组的「Root 权限」行（没有 `su` 时置灰并说明原因）
- 文案：`app/lib/l10n/parts/phone_{zh,en}.arb` 的 `phoneRootTitle` / `phoneRootOn` / `phoneRootOff` / `phoneRootNoSu`，以及 `phoneControlNeedsAccessibility`（「还差一步：打开无障碍或给 root」）

关掉开关后 `driverForTask` 只返回无障碍驱动，UI 与状态都跟着变；这是「自由开关」，不能做成只读展示。

## 跟上游合并的固定流程

```bash
cd <repo>
# 1) github.com 直连经常不通，走本地代理抓
git -c http.proxy=http://127.0.0.1:7897 -c http.version=HTTP/1.1 -c http.postBuffer=524288000 \
    fetch --no-tags upstream "+refs/heads/main:refs/remotes/upstream/main"
git rev-list --left-right --count HEAD...upstream/main
# 2) 备份
git branch -f backup-gocket-pre-<commit> HEAD
# 3) 合并，逐个解冲突（版本行、remote_screen.dart、dsh_web_launcher.dart 是常客）
git merge --no-commit --no-ff upstream/main
# 4) 版本行：app/pubspec.yaml 与 4 个 guide（见下）
# 5) 文案
py app/tool/l10n_merge.py        # 需要 FLUTTER=<flutter bin>/flutter.bat
# 6) 依赖
#    app/pubspec.lock 里 https://pub.dev → https://pub.flutter-io.cn 后：
(cd app && flutter pub get --enforce-lockfile)
# 7) 验证
(cd app && flutter analyze --no-pub)
(cd app && flutter test --no-pub test/remote test/phone/android_shell)
# 8) 构建（build_number = py tooling/build_number.py）
android-build 脚本见下
# 9) 交付后把 lock 还原
git checkout -- app/pubspec.lock
```

版本行一共 5 处，改漏会被 `guide_sync.py --check` 抓到：

- `app/pubspec.yaml` 的 `version:`
- `app/assets/skills/dsh-go-guide/README.md`、`app/assets/skills/dsh-go-guide/harmony-README.md`
- `docs/guide/README.md`、`docs/guide/harmony/README.md`

### 本地构建 APK（Windows）

`D:\android-build\build-gocket-<ver>.bat` 的固定形态：

```
set JAVA_HOME=D:\android-build\jdk-tmp\jdk17.0.20_10
set ANDROID_HOME=D:\android-build\sdk
set FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn
set PUB_HOSTED_URL=https://pub.flutter-io.cn
call D:\android-build\flutter-dl\flutter\bin\flutter.bat pub get --enforce-lockfile
call ... flutter.bat build apk --release --flavor arm64 --target-platform android-arm64 ^
     --build-name <ver> --build-number <code> --dart-define=DSH_APP_VERSION=<ver>+<code> ^
     --dart-define=DSH_UPDATE_MIRROR= --dart-define=DSH_UPDATE_FEED=
```

签名用 `signing/dsh-go-release.jks`（`keyAlias=dshgo`，证书 `CN=Mrsirlila dsh-Go`，SHA-256 `89880b38abca7b4d8772346aaf9fcf7b353fba03e95620cc568c986d1ee8b537`），`app/android/key.properties` 与 `local.properties` 不进仓库。**每次交付的包必须用同一把钥匙**，否则客户端装不上更新。

### 发布

客户下载地址永远是 `https://github.com/Mrsirlila/dsh-gocket/releases/latest/download/dsh-go-<ver>-arm64.apk`，App 内的「检查更新」也读同一个 Release（`updateRepo = 'Mrsirlila/dsh-gocket'`），所以每次发版必须三件套齐全：`dsh-go-<ver>-arm64.apk`、`SHA256SUMS`、`update.json`。

**默认分支是 `gocket`**（`main` 只作为上游镜像，不要去改）。这样 Actions 里能点到 `Build APK`，它的 `workflow_dispatch` 有 `publish=release`：会先用 `apksigner` 校验不是 debug 签名，再生成三件套，最后 `gh release create`。

⚠ `runtime=upstream` 在这棵树上**会失败**：上游 1.4.1 的 release APK 里 `manifest.json` 缺 `runtime/build-runtime.sh` 里 pin 的 38 个 `ohos-*.diff`，CI 报 `upstream runtime does not match this tree (engine patches missing: …)`。要跳过就用 `runtime=build`（自己构建 runtime，慢），或者干脆在本机发版：

```
# 1) 构建（build-gocket-<ver>.bat，见上一节）
# 2) 发版：生成三件套 + 打 tag + 建 Release + 上传（幂等，可重跑）
powershell -ExecutionPolicy Bypass -File D:\android-build\release-gocket.ps1 -Version 1.4.6 -NotesFile D:\android-build\notes-146.md
```

`release-gocket.ps1` 会先核对 `app/pubspec.yaml` 的版本号，对不上直接退出；重复跑同一个版本会覆盖旧资产。它用 `git credential` 里的 PAT，需要 `py` 与 `curl` 在 PATH。


## 测试的已知噪声（不是回归）

- `test/phone/**` 的 golden：本机渲染与仓库 goldens 有约 1.36% 的基线差异，`git stash` 掉改动后同样失败 ⇒ 本地失败不代表改坏了，**不要** `--update-goldens` 把本机差异提交进去
- `test/remote/remote_bootstrap_test.dart`：需要 `sh`，Windows 上必挂
- `test/remote/relay_credit_test.dart`：合并卡布局与上游断言不一致（1.3.1 起就有）
- `test/phone/phone_self_check_test.dart` 的一条：Windows 路径分隔符 `/` 与 `\` 的差异
