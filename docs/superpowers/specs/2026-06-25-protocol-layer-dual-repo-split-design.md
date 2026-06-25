# 协议层去历史化 + 双仓拆分 — 设计

- 日期:2026-06-25
- 状态:已批准,待实现
- 主题:把 laqunxitong 的协议层以全新历史迁到 itdxzss 组织下,拆成 wheel / armada 两个独立仓库

## 1. 背景与目标

当前协议层(WhatsApp/Baileys 协议封装,Fastify + Baileys 7.x,约 1.15 万行 TS)位于他人私有库
`github.com/Siontk/laqunxitong.git` 的 `protocol-layer/` 子目录。wheel 通过 HTTP 调用其线上实例
(13.234.217.33:8080)。

目标:不在 Siontk 的提交历史上继续提交,把这份代码以**全新干净历史**迁入自有 `itdxzss` 组织,
并拆成两个**独立仓库**:

| 仓库 | 用途 | 形态 |
|---|---|---|
| `itdxzss/protocol-layer` | wheel 使用 | 保持稳定,起步=当前 main 快照 |
| `itdxzss/armada-protocol` | armada 重构目标 | 起步与上面逐字节一致,后续单独重构 |

两份是"两个产品",独立 CI / 部署 / 版本,可自由分叉。

## 2. 已批准的决策

1. 仓库结构:**两个独立仓库**(非单仓多分支、非 monorepo)。
2. Git 历史:**全新干净历史** —— 单个初始 commit,不携带 Siontk 的 37 次提交。
3. 基线:**main 分支**快照(丢弃 `feat/message-link-card` 上未提交的 button-card / stale-detector WIP)。
4. 重构范围:**本次只建好两个仓库**;armada 重构是后续独立任务(单独 brainstorm + plan)。
5. 可见性:两仓均 **private**。
6. 目录布局:**保持原布局(零代码改动)**。

## 3. 探查实证出来的约束

1. **运行时必需内容 = `protocol-layer/` + `openapi/` 两个目录。**
   `protocol-layer/src/types/api.ts` 通过 `../../../openapi/generated/aliases.js` 引用仓库根的
   `openapi/`(该相对路径从 `protocol-layer/src/types/` 回退三级即仓库根)。两个目录均已提交在 main。
   因此不能只抽 `protocol-layer/`,必须连 `openapi/` 一并带入,且保持二者在仓库根下的相对位置不变。
2. **参考资料非运行时依赖,不带入新仓库**:`Baileys-master_协议`(Baileys 上游源码副本)、
   `importers-poc`(POC)、`Baileys协议层接口封装…2000账号承载方案_副本.md`、`重构需求文档` ——
   `protocol-layer/src` 与 `scripts` 对它们零引用(grep 实证)。保留在老 `laqunxitong/` 目录作参考。
3. **baileys 补丁随行**:`protocol-layer/patches/baileys+7.0.0-rc11.patch` + `package.json`
   的 `postinstall: patch-package`,已位于 `protocol-layer/` 内,自动随快照带上。
4. **凭证就绪**:macOS keychain 已存 `itdxzss` PAT(`x-oauth-scopes: repo`),可经 GitHub API 建仓库
   + 经 https 推送;本机未装 gh CLI,故走 API,不依赖 gh。
5. 基线取 main 可天然避开当前 `laqunxitong` 工作区在 `feat/message-link-card` 上的脏改动与未追踪文件。

## 4. 目标仓库布局(布局 A)

两个仓库结构相同:

```
itdxzss/protocol-layer  (或 armada-protocol)   ← 仓库根
├── .gitignore                 # 沿用老 laqunxitong 根 .gitignore
├── README.md                  # 新写:说明来源、用途、与对方仓库的关系
├── protocol-layer/            # 服务本体(src / package.json / patches / deploy / docs / ...)
└── openapi/                   # 被 api.ts 引用的契约与生成类型(generated/ + protocol-v1.yaml + ...)
```

说明:仓库名为 protocol-layer,内部仍含同名 `protocol-layer/` 子目录,略冗余但换来对相对路径
`../../../openapi` 的**零改动**,wheel 那份风险最低。布局优化(提升为根)留给 armada 后续重构。

## 5. 执行步骤

1. **建空仓库**:用 PAT 经 GitHub API 创建 `itdxzss/protocol-layer`、`itdxzss/armada-protocol`
   (private,空仓:不自动生成 README/license,以便我们推全新初始 commit)。
2. **导出 main 快照**:在老仓库执行
   `git archive main protocol-layer openapi | (cd <新目录> && tar -x)`,得到不含 `.git`、不含脏 WIP
   的纯净文件树;对两个新目录各导出一次(或导出一次后复制)。
3. **初始化各新仓库**(本地目录 `IdeaProjects/protocol-layer`、`IdeaProjects/armada-protocol`):
   - 放入 `protocol-layer/` + `openapi/`;
   - 写入顶层 `.gitignore`(取自老仓库根)与新 `README.md`;
   - `git init` → `git add -A` → 单个初始 commit(全新历史,作者=itdxzss);
   - `git remote add origin https://github.com/itdxzss/<repo>.git` → `git push -u origin main`。
4. **本地落位**:两个新目录置于 `IdeaProjects/` 下;老 `laqunxitong/` **原样保留**(仍挂 Siontk
   remote 与 feat WIP),仅作只读参考。
5. **验证**:对至少一个新仓库执行干净克隆 → `cd protocol-layer && npm ci && npm run lint`
   (`tsc --noEmit`)通过,证明 `../../../openapi` 相对路径在新布局下未断。

## 6. 本次明确不做(范围外)

- 不动生产部署:13.234.217.33:8080 的运行实例不变,仅"部署来源"以后切到 itdxzss。
- 不改 wheel 对协议层的调用(URL / 契约 / Kafka 事件不变)。
- 不开始 armada 重构。
- 不携带参考资料目录(Baileys 源码副本、POC、需求文档)。
- 不迁移 Siontk 的分支与历史。

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| openapi 相对路径在新布局下失效 | 布局 A 完整保留二者相对位置;步骤 5 用 `tsc --noEmit` 实测兜底 |
| 误把脏 WIP / .git / node_modules 带入 | 用 `git archive main`(只取已提交的 main 树),天然排除 |
| PAT 权限不足以建仓库 | 已实测 `scope=repo`,具备建仓库权限 |
| 误改/误删老 laqunxitong | 全程对老仓库**只读**(git archive / git ls-tree),不切分支、不写、不删 |
| 两仓起步不一致 | 同一份 main 快照分发到两仓;初始 commit 后用 diff 比对确认逐字节一致 |

## 8. 验收标准

- GitHub 上存在 `itdxzss/protocol-layer`、`itdxzss/armada-protocol` 两个 private 仓库,各仅一个初始 commit。
- 两仓内容除 README 外逐字节一致,且都含 `protocol-layer/` + `openapi/`。
- 任一仓库干净克隆后 `npm ci && npm run lint` 通过。
- 老 `laqunxitong/` 工作区未被改动(git status 与迁移前一致)。
