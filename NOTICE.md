# NOTICE · 来源、版权与第三方许可

本文件用于明确本仓库的来源、授权方式与随附第三方组件的许可，属于 GPL-3.0 分发要求的一部分。

## 一、来源

| 项目 | 值 |
|---|---|
| 上游项目 | [FongMi/TV](https://github.com/FongMi/TV) |
| 上游许可 | GNU General Public License v3.0 |
| 本仓库基线 | 上游 commit `fe6a5b2`（*Stabilize external subtitle configurations*，2026-08-27） |
| 本仓库性质 | 衍生的独立分支（长辈版 UI 重构），**非官方版本**，与上游作者无从属关系 |

## 二、版权

- 原始代码版权归 FongMi/TV 原作者及其贡献者所有。
- 本分支新增与修改的部分，同样以 GPL-3.0 授权发布。
- 上游 `LICENSE.md`（GPL-3.0 全文）保持原样，未作任何修改；本仓库不另行替换为其他许可证。

## 三、为什么必须是 GPL-3.0

本仓库包含并修改了上游 GPL-3.0 代码，属于 GPL-3.0 定义的衍生作品。根据 GPL-3.0 条款，衍生作品**必须**继续以 GPL-3.0（或与之兼容的更强许可，如 AGPL-3.0）分发，**不得**改为 MIT / Apache-2.0 / BSD 等宽松许可。因此本项目不存在"自选许可证"的空间。

## 四、第三方组件

随本仓库源码或构建产物一同分发的第三方组件（含 `app/libs/*.aar`、各 `*/src/main/jniLibs/*.so`、`other/tools/*.jar`）：

| 组件 | 用途 | 许可 | 与本项目许可的兼容性 |
|---|---|---|---|
| AndroidX / Material Components | UI 基础库 | Apache-2.0 | 兼容（Apache-2.0 与 GPLv3 单向兼容） |
| AndroidX Media3 / ExoPlayer | 播放内核 | Apache-2.0 | 兼容 |
| FFmpeg（`lib-decoder-ffmpeg`） | 软解 | LGPL-2.1+ / 视编译选项可为 GPL | 需按实际编译选项分发对应许可文本 |
| mpv / libplacebo（`lib-mpvplayer`） | 渲染 | GPL-2.0+ / LGPL-2.1+ | GPLv2+ 与 GPLv3 兼容 |
| QuickJS | JS 引擎 | MIT | 兼容 |
| Chaquopy | Python 运行时 | 商业许可（开源项目免费） | 本仓库为开源项目，适用其免费条款；闭源分发需自行取得商业授权 |
| okhttp / okio | 网络 | Apache-2.0 | 兼容 |
| `other/tools/bfg.jar` | 上游附带工具 | 以其自身声明为准 | — |

> 如果计划以商业方式分发，请先逐项核对上表，特别是 FFmpeg 的编译选项（`--enable-gpl` 会使其变为 GPL）与 Chaquopy 的商业条款。

## 五、内容与数据

本仓库**不包含**任何影视、直播内容源、接口地址或用户数据：

- 没有内置任何接口配置 JSON；
- 没有内置任何账号、密钥、Cookie、Token；
- `local.properties`（含本机 SDK 路径与签名口令）被 `.gitignore` 排除，不入库；
- 签名文件 `*.jks` 被 `.gitignore` 排除，不入库。

## 六、二进制分发

若通过 Release 或其他渠道分发 APK，依据 GPL-3.0 §6，必须同时提供**对应版本的完整源码**（指向本仓库对应 tag/commit 即可满足）。

**源码完整性说明**：`app/libs/` 下的 20 个 `lib-*.aar`（约 44 MB，含解码器、播放器与 ExoPlayer 定制库）**已随本仓库一并入库**，因此克隆仓库即可编译，不需要另外寻找这批预编译库。需要说明的是，这些 `.aar` 是二进制形式，其内部实现（FFmpeg / mpv / libplacebo 等）的源码由对应上游项目提供，不在本仓库内；对外分发 APK 时，建议在 Release 说明中注明这些组件的来源，以便接收者取得对应源码。


