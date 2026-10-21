# ACE Chat　开源声明

## 一、声明

**ACE Chat（含 SPEC-ACE Chat 分支）是开源软件，以 GNU 通用公共许可证第 3 版或更高版本（GPL-3.0-or-later）发布。**

- 版权：Copyright (C) 2026 ACE Chat 作者
- 许可证：GPL-3.0-or-later（SPDX 标识：`GPL-3.0-or-later`）
- 源码仓库：https://github.com/Tech-yon/ACE-Chat
- 协议全文：随模组分发，见 jar 内 `LICENSE` 与 `LICENSES/GPL-3.0.txt`

本声明与 `LICENSE`、`NOTICE.txt` 三者共同构成该项目的授权说明；若三者有冲突，以 GPL-3.0 协议正文为准。

## 二、你可以做什么

- **使用**：在任意数量的客户端与服务器上安装、游玩；
- **修改**：按自己的需要修改源代码；
- **再分发**：分发原始版本或你的修改版；
- **商业使用**：包括收录进**收费整合包**、用于**商业服务器**、随整合包或服务收费分发——GPL 允许商业使用，本项目不作额外限制；
- **二次开发**：把本项目作为其它开源项目的基础。

## 三、你必须做什么

1. **保留声明**：保留版权声明、许可证声明与 `NOTICE.txt`、`LICENSES/`，不得删除或篡改作者信息；
2. **提供源码**：对外分发本模组（包括放进整合包）时，必须同时提供**完整的对应源码**——通常给出上述源码仓库链接即可，或随包附带源码；
3. **同许可证**：若你修改后对外分发，修改版**同样必须以 GPL-3.0-or-later 发布**并公开其源码；
4. **不加额外限制**：不得对本模组附加 GPL 之外的额外条款或限制（例如禁止他人再分发）。

## 四、第三方组件

| 组件 | 版本 | 许可证 | 用途 |
|---|---|---|---|
| JLayer（JavaZOOM） | 1.0.1 | **LGPL-2.1** | MP3 解码，用于自定义通讯音效 |

- JLayer 已做**包重定位**（`cn/acechat/shade/javazoom/jl/...`），不占用公共包名，避免与其它内嵌同一库的模组发生冲突；
- 其许可证全文见 `LICENSES/JLayer-LGPL-2.1.txt`，分发时请一并保留。

## 五、与 Mojang / Microsoft 的关系

本项目是**非官方**的第三方模组，**与 Mojang Studios、Microsoft 及其关联公司无关**，未获其背书或授权。
“Minecraft”为 Mojang Studios 的商标，本项目仅在描述适用平台时使用该名称。

## 六、免责声明

本软件按“现状”提供，**不附带任何明示或暗示的担保**，包括但不限于对适销性、特定用途适用性与非侵权性的担保。在任何情况下，作者或版权持有人均不对因本软件或本软件的使用或其它交易而引起的任何索赔、损害或其它责任负责，无论是合同诉讼、侵权行为还是其它方式，亦无论是否已告知此种损害的可能性。

在服务器或整合包中使用前，建议先备份存档与 `ACE_config/` 目录。

## 七、反馈与协作

- 问题反馈：https://github.com/Tech-yon/ACE-Chat/issues
- 反馈时附上 `logs/latest.log` 与所用整合包名称，可显著加快定位；
- 欢迎提交 Issue 与 Pull Request；提交即表示同意你的贡献以 GPL-3.0-or-later 授权并入本项目。

---

## Open Source Statement (English, short)

ACE Chat (including the SPEC-ACE Chat branch) is free and open-source software licensed under the **GNU General Public License v3.0 or later (GPL-3.0-or-later)**.
Copyright (C) 2026 the ACE Chat authors. Source code: https://github.com/Tech-yon/ACE-Chat
You may use, modify and redistribute it, including commercially (e.g. in paid modpacks). If you distribute it, you must keep the copyright and license notices, provide the complete corresponding source code, and license any modified version under GPL-3.0-or-later without additional restrictions.
Bundled third-party component: JLayer 1.0.1 (LGPL-2.1), relocated under `cn/acechat/shade/` to avoid package conflicts.
This is an unofficial mod and is not affiliated with, endorsed by, or sponsored by Mojang Studios or Microsoft. The software is provided “as is”, without warranty of any kind.
