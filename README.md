# ACE Chat

把聊天框做成"通讯终端"的 Minecraft 模组：顶部通讯框（玩家名独占一行、正文居中、`<<< 正文 >>>` 包裹）、
半身像与角色卡系统、自定义立绘、叠加滤镜、通讯音效，以及名字 / 生命 / 饥饿等文字元素。

> 目前处于 **Beta 一期测试**：功能尚不完善、Alpha 内测条件不足，因此直接进入 Beta，欢迎反馈问题。

## 目录结构

| 目录 | 说明 |
|---|---|
| `src-1.20.1-forge/` | 1.20.1 Forge 标准版 |
| `src-1.21.1-neoforge/` | 1.21.1 NeoForge 标准版 |
| `src-1.20.1-forge-spec/` | **SPEC 分支**：对 NoChatReports 等"会把玩家聊天改写成系统消息"的模组的适配版 |
| `docs/` | 对外文档：模组介绍（长版 / 一段式）、开源声明、三个平台的《功能说明》、视频简介 |

每个目录里只有 `src/main/java`（源码）与 `src/main/resources`（语言文件、`mods.toml`、许可证、音效解码依赖）。

## 构建

本项目用离线工具链构建：`javac --release 17`（1.20.1）/ `--release 21`（1.21.1）
先把源码编译成"官方命名"的 class，再把 1.20.1 的产物用 ASM 重映射到 SRG，
最后与 JLayer 等依赖合并进 jar。**不使用 Gradle**，因此仓库里没有 `build.gradle`。

## 许可证

**GNU 通用公共许可证第 3 版或更高版本（GPL-3.0-or-later）**，全文见 [LICENSE](LICENSE)。

- 可以：使用、修改、再分发；收录进免费或**收费**整合包；用于商业服务器；
- 必须：保留作者与许可声明；对外分发时提供完整对应源码；修改后分发时修改版同样以 GPL-3.0-or-later 开源；不得附加额外限制。

内嵌的 [JLayer](https://www.javazoom.net/javalayer/javalayer.html) 1.0.1（MP3 解码）为 **LGPL-2.1**，
声明见 [NOTICE.txt](NOTICE.txt) 与 [LICENSES/](LICENSES/)。

## 反馈

请通过仓库 Issues 或视频评论区反馈；附上 `logs/latest.log` 和所用整合包名字会更快定位问题。