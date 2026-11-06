<div align="center">

<h1>dsh-gocket</h1>

<p><strong>DSH-Go × dsh-pocket：一个 App 既能在手机本机跑 DSH，也能扫码连回你的电脑。</strong></p>

<p>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/based%20on-dsh--Go%201.0.11-4f8cff?style=flat-square" alt="based on dsh-Go 1.0.10" />
  <img src="https://img.shields.io/badge/plugin-dsh--pocket--plus-8b5cf6?style=flat-square" alt="dsh-pocket-plus" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-AGPL--3.0-blue?style=flat-square" alt="AGPL-3.0" /></a>
</p>

</div>

**dsh-gocket** 把两个项目合成了一个 App：

- [**dsh-Go**](https://github.com/ccch1mneyyy/dsh-Go)：DeepSeek Harness 的原生安卓 App。可以在手机本机运行 DSH，也可以通过 SSH 或 DSH Remote 遥控电脑。
- [**dsh-pocket-plus**](https://github.com/Mrsirlila/dsh-pocket-plus)：电脑端 DSH 的手机访问插件。提供 **局域网 / Tailscale / 公网** 三条入口，二维码里带着访问密码，扫码直接进入。

我在 dsh-Go 的 App 里加了自己的 **「三通」方案（Pocket 主机）**：扫一下电脑 DSH 设置页上的二维码就能连回电脑。连接时按 局域网 → Tailscale → 公网 的顺序自动选一条能通的，网络变化时自动切换。

> **需要在电脑上安装 dsh-pocket-plus 插件配合使用**，只装 App 连不上 Pocket 主机。

## 功能

| | 来自 | 说明 |
| --- | --- | --- |
| 手机本机运行 DSH | dsh-Go | 内置 PRoot 运行时，不需要电脑 |
| SSH 主机 / DSH Remote | dsh-Go | 原版的远程连接方式全部保留 |
| **Pocket 主机（三通）** | 本项目 | 扫码添加电脑，局域网 / Tailscale / 公网自动择优、断线自动切换 |
| 地址与密码自动同步 | 本项目 + 插件 | 连上任意一条入口，就从电脑的 `/dsh-pocket/entry.json` 同步另外两条的地址和公网密码。公网隧道地址变了也不用重新扫码 |
| 左下角一键切换 | 本项目 | 「切换主机」里直接列出 Pocket 主机，和本机、SSH 主机并列 |
| 入口锁定 | 本项目 | 可以只用某一条入口，例如固定走 Tailscale |

### 三条入口

| 入口 | 地址形态 | 适用场景 | 密码 |
| --- | --- | --- | --- |
| 局域网 | `http://192.168.x.x:3081` | 手机和电脑连同一个 Wi-Fi | 一般不用 |
| Tailscale | `http://100.x.x.x:3081` | 不在一个网络，手机和电脑登录同一个 tailnet | 一般不用 |
| 公网 | `https://xxx.trycloudflare.com` | 任意网络，经 Cloudflare 隧道 | 需要（扫码自动填） |

## 快速开始

### 1. 电脑：装 dsh-pocket-plus 插件

```bash
dsh plugin --profile web add "dsh-pocket@github:Mrsirlila/dsh-pocket-plus#path:/plugin"
```

也可以用 [Releases](../../releases) 里的 `dsh-pocket-plus-plugin.zip` 离线部署。装完重启 DSH，侧栏打开「DSH Pocket」设置页，能看到局域网 / Tailscale / 公网三张二维码。

插件的详细说明、补丁清单和常见问题见 [dsh-pocket-plus](https://github.com/Mrsirlila/dsh-pocket-plus)。

### 2. 手机：装 App

从 [Releases](../../releases) 下载 `dsh-go-<版本>-arm64.apk` 安装（仅支持 arm64 手机）。

### 3. 扫码添加电脑

App 里打开 **远程主机 → DSH Pocket 主机 → 添加 Pocket 主机**，扫电脑上任意一张二维码即可。其余两条入口会在第一次连上后自动补齐。

之后在侧栏左下角的 **切换主机** 里，就能在「本机」和你的电脑之间随时切换。

## 安装须知

- **签名**：本项目的 APK 用独立的签名密钥。它和官方 dsh-Go、和其他人编的版本签名都不一样，**第一次从这些版本换过来需要先卸载**（卸载会清除 App 数据，手机本机上的对话请先「导出对话日志」）。之后本项目的新版本都可以直接覆盖安装。
- **App 内更新提示**：App 内置的更新检查指向官方 dsh-Go。官方版本签名不同，没法覆盖安装，装了也会失去 Pocket 主机功能。请到本仓库的 Releases 下载更新。
- **包名**：沿用上游的 `io.github.ccch1mneyyy.dsh_app`，所以不能和官方 dsh-Go 同时安装。

## 自己编译

仓库自带 GitHub Actions 工作流 **Build APK**（`.github/workflows/build-apk.yml`）：

1. Fork 本仓库。
2. （可选）在 Settings → Secrets → Actions 里配置 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`，用固定密钥签名，这样新版能覆盖安装。不配就用一次性的调试密钥签名。
3. Actions → Build APK → Run workflow，编好的 APK 在运行结果的 Artifacts 里。

本地开发、测试和架构说明沿用上游文档：[README.dsh-go.md](README.dsh-go.md)、[CONTRIBUTING.md](CONTRIBUTING.md)、[docs/architecture.md](docs/architecture.md)。

## 代码在哪

和上游 dsh-Go 相比，Pocket 主机相关的改动集中在这些位置：

```
app/lib/remote/pocket/pocket_host_config.dart   Pocket 主机配置、三条入口、entry.json 解析
app/lib/remote/pocket/pocket_link.dart          入口探测、自动切换、地址同步
app/lib/ui/remote/pocket_host_form.dart         添加 / 编辑 Pocket 主机
app/lib/ui/remote/pocket_qr_scanner.dart        扫码（相机 / 相册）
app/lib/ui/remote/host_switcher.dart            左下角「切换主机」
app/lib/state/hosts_state.dart                  Pocket 主机的保存与密码存储
packages/dsh_client/lib/src/connection.dart     dsh-pocket 代理的登录握手
.github/workflows/build-apk.yml                 云端编译与签名
```

## 致谢

- [ccch1mneyyy/dsh-Go](https://github.com/ccch1mneyyy/dsh-Go)：App 本体
- [shaobeichen/dsh-pocket](https://github.com/shaobeichen/dsh-pocket)：dsh-pocket 插件上游
- [Zhen-Bo](https://github.com/Zhen-Bo)：Pocket 主机的最初实现和云端编译工作流

## License

App 部分沿用上游 dsh-Go 的 [AGPL-3.0](LICENSE)。dsh-pocket 插件沿用其上游的 GPL-2.0，见 [dsh-pocket-plus](https://github.com/Mrsirlila/dsh-pocket-plus)。
