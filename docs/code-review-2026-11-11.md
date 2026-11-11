# 代码审查报告（2026-11-11）

> 审查对象：LearnHub 0.1.7，审查基线提交 `3f0a84a`。
> 方式：通读架构与文档 → 逐项核对关键实现 → 实际运行测试与构建。审查期间未修改业务代码。
> 文中的行号对应上述提交；后续改动会使行号位移，请以文件与符号名为准。

---

## 0. 结论摘要

工程项目质量明显高于同类个人项目：测试量大、文档诚实、边界处理细致（SSRF 钉连接、XXE 防护、写操作审批、OCR/像素上限、侧车隔离）。

主要风险不在"写得糙"，而在**安全默认值**与少数**数据完整性 / 可用性**设计：

| 等级 | 问题数 | 代表问题 |
| --- | --- | --- |
| 高 | 2 | 无认证且源码部署监听所有网卡；Markdown 原始 HTML 未净化导致存储型 XSS |
| 中 | 7 | 抽取跑在数据库事务内；标题"互相包含"会删正文；审批卡在 executing；笔记保存无版本校验；检索每次全量读语料；请求体无上限；任务表只增不减 |
| 低 | 9 | 异常信息回传客户端、前端 7 个测试失败、未声明依赖、默认弱口令、文档漂移等 |

**建议顺序**：先做安全与数据两类（第 5 节第 1–4 项），再做可靠性与工程化。

---

## 1. 验证结果

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 后端单测 | `JAVA_HOME=jdk-21 mvn -B -s deploy/maven-settings.xml -f backend/pom.xml test` | **1632 项，0 失败 / 0 错误 / 26 跳过，BUILD SUCCESS**（3 分 39 秒） |
| CLI 单测 | `npm --prefix cli test` | **27/27 通过** |
| 前端单测 | `cd frontend && node --test src/*/*.test.js` | **862 项，855 通过、7 失败** |
| 前端构建 | `npm run build` | **成功**（2.21 s） |
| 未执行 | Docker Compose、MySQL 集成、迁移 Testcontainers、Windows EXE | 本机 Docker 守护进程未启动、无 .NET SDK；26 个跳过项即 DB/网络类测试 |

### 验证环境说明

- 机器默认 `JAVA_HOME` 指向 JDK 17，需显式使用 JDK 21（项目要求 Java 21）。
- 本机未安装 Maven，审查时临时下载 Maven 3.9.11 并使用仓库自带的 `deploy/maven-settings.xml`（阿里云镜像）；未改动项目配置。
- 前端依赖需 `npm ci` 完整安装后测试才有意义；镜像源慢时可能出现"半个 node_modules"导致误报。

**26 项跳过**包括：数据库迁移集成测试、部分依赖真实网络的下载测试、需要 `LEARNHUB_*_TESTS` 环境变量的集成用例。CI 会补齐这些，本地 `mvn verify` 的绿色不等于全链路验证。

---

## 2. 高优先级问题

### H1. 无认证 + 源码部署监听所有网卡 + 无 Host 校验

**证据**

- 后端只配置了 `server.port`，没有 `server.address`（`backend/src/main/resources/application.yml` 第 1–3 行）。Spring Boot 默认绑定 `0.0.0.0`。
- 全仓库无 `spring-boot-starter-security`、无认证过滤器、无 CORS/拦截器配置（检索 `SecurityFilterChain`、`HandlerInterceptor`、`@PreAuthorize` 均为空）。
- 源码 Compose 未限制绑定地址：MySQL 3307、后端 18080、前端 8888 全部映射到 `0.0.0.0`（`deploy/docker-compose.yml` 第 32–33、85、110–111 行）。
- 发布版 Compose 已默认绑定 `127.0.0.1`，但文档把"只监听本机"写成应用普遍性质（`docs/agent-memory-design.md` 第 253 行），与源码部署/直接 `java -jar` 不符。

**影响**

1. 同一局域网内任何设备可读写全部笔记、资料、设置，并可触发模型调用（消耗 API 额度）。
2. **DNS rebinding**：恶意网页把域名解析到 `127.0.0.1`，浏览器携带 `Host: attacker.example` 访问本机；nginx 以 `server_name _` 的默认站点接收并转发，后端不校验 Host，脚本即获得同源读写能力。
3. 即使不考虑 rebinding，在**无认证、无 CSRF** 的情况下，跨站表单即可触发无请求体的写接口，例如 `POST /api/kb/rebuild`（全量重建）与 `POST /api/files/upload`（multipart 简单请求）。

**建议**

- 默认 `server.address=127.0.0.1`，源码 Compose 同步绑定 `127.0.0.1`。
- 首次启动生成访问令牌（或支持反向代理 Basic Auth），前端同源注入。
- 至少增加 Host 白名单校验过滤器。
- 无请求体的 POST 增加 CSRF 防护，或要求自定义请求头。
- 文档明确"暴露到局域网必须自行加认证"。

### H2. Markdown 原始 HTML 未净化 → 存储型 XSS

**证据**

- 应用侧 Markdown 渲染显式允许原始 HTML：`frontend/src/utils/mdToHtml.js` 第 23–26 行（`html: true`）。
- `md-editor-v3` 的预览把渲染结果整体写入 `innerHTML`，其 `sanitize` 属性**默认是恒等函数**（库内置行为），项目未传入该属性（`frontend/src/utils/mdEditorSetup.js` 第 30–56 行）。
- 多处直接渲染不受控内容：
  - 笔记预览：`frontend/src/views/NoteEdit.vue` 第 4262–4270 行；
  - 融入/润色预览：同文件第 4426–4431 行；
  - 智能体回答：`frontend/src/components/AgentPanel.vue` 第 1166 行；
  - Wiki 生成页：`frontend/src/views/Knowledge.vue` 第 1575 行。
- `markdown-it({html:true})` 会原样输出 `<img src=x onerror=...>`；`<script>` 经 `innerHTML` 不执行，但 `onerror`/`onclick` 等事件属性会执行。
- nginx 对页面未设置 CSP / X-Frame-Options / Referrer-Policy（`deploy/nginx.conf`），仅 `/api/files/{id}/raw` 设了 CSP。
- 项目已依赖 `dompurify`，但只用于 Mermaid 与 Word 预览。

**影响**

网页导入、模型生成（网页提示注入后输出事件属性）、手工粘贴都可能把恶意 Markdown 写进笔记/速查卡/Wiki/回答。脚本与页面同源，可读取全部笔记、调用写接口、触发模型调用。

**建议**

- 给 `MdEditor`/`MdPreview` 传 `sanitize`（用已有的 DOMPurify）。
- 生成类内容（智能体回答、Wiki）关闭原始 HTML。
- 导出 HTML 前同样净化。
- nginx 增加 `Content-Security-Policy`（`default-src 'self'; script-src 'self'; object-src 'none'; frame-ancestors 'none'`）作为纵深防御。

---

## 3. 中优先级问题

### M1. 资料抽取跑在数据库事务内

`uploadBytes` 为 `@Transactional`，事务内同步调用 `extractInto`（`backend/.../service/FileStorageService.java` 第 235–267 行），后者会做 PDF 解析、版面还原、OCR（单页上限 60 秒、最多 20 页）与媒体/视觉模型调用。

**影响**：扫描件或大文档上传可能占用连接数分钟，并发几次即可打满 Hikari 连接池；nginx 对上传接口用默认 60 秒超时，客户端先失败而后端继续跑，重试会叠加任务。

**建议**：先短事务提交 `file_info`，抽取移至事务外或后台任务（项目已有 `DocumentProcessingJobs`），前端轮询 `textStatus`。

### M2. 标题"互相包含即视为重复"会删除正文

保存/更新笔记统一走 `normalizeNoteContent` → `stripDuplicatedTitle`，判据是 `sameTitleText` 的**互相包含**（`backend/.../service/NoteService.java` 第 265–273 行）；相邻标题合并也使用同一判据（同文件第 297–341 行）。

示例：标题 `Java 并发`，正文首行 `# Java 并发编程实战笔记` → 归一化后被包含 → **整行标题被删**，"编程实战笔记"从正文永久消失。

该函数位于每次保存的必经路径上，但**没有单元测试**（测试目录无引用）。

**建议**：仅"归一化后完全相等"才删除；包含关系改为保留更具体标题；补前缀/后缀/中缀/多级/代码块用例。

### M3. 待确认操作可能永久卡在 `executing`

审批先原子更新 `pending → executing`（`backend/.../service/AgentSessionService.java` 第 578–589 行），执行完才 `resolveAction`。若执行中 JVM 退出、重启或 `resolveAction` 写库失败，该行永远停在 `executing`：不能再确认，也不能取消（两者都要求 `pending`，见 `AgentService.java` 第 3936–3945 行），并持续出现在 `pendingActions` 与模型上下文中。

对比：文档处理任务有重启恢复逻辑（`DocumentProcessingJobs.java` 第 145–160 行），审批表没有。

**建议**：启动时把超时（如 10 分钟）的 `executing` 置为 `failed` 并注明"上次执行结果未知"，同时提供显式"标记已处理"入口。

### M4. 普通笔记保存没有并发版本校验

智能体定向编辑用 `expected_hash` 校验，但 `PUT /api/notes/{id}` 直接覆盖标题/正文/分类（`NoteService.java` 第 203–231 行），无版本前置条件。

**影响**：两个标签页同时编辑同一篇笔记，后保存者静默覆盖前者。

**建议**：增加 `expectedHash`/`updatedAt`，UPDATE 带版本条件，冲突返回 409，复用现有"库中正文已更新"提示流程。

### M5. 每次检索全量读取语料并重新分块

`KnowledgeRetrievalService.currentPassages` 每轮读取 `allNotes/allRefs/allFiles`（含 `LONGTEXT` 正文），再取已存分块或现场切块（`KnowledgeRetrievalService.java` 第 304–321 行）；`VectorIndexService.persistedChunks` 每次读入全部 `kb_chunk` 行做指纹校验（第 734–739 行）；SQL 见 `KbChunkMapper.java` 第 112–128 行。

**影响**：个人库数百 KB 时无感；资料达到数 MB、几千块后，页面检索、智能体每轮自动检索、每次 `search_knowledge` 都付一次全量读 + 切块成本（向量扫描本身经实测很便宜）。

**建议**：加"来源正文 + 分块"缓存并按指纹失效；或关键词路先 SQL 预筛，只对命中来源取正文；`persistedChunks` 改为按来源键批量查。

### M6. 资源边界缺口

1. **请求体无上限**：`AiChatRequest.message`、`AiPolishRequest.text`、`NoteDTO.content` 只有 `@NotBlank`，无 `@Size`（`AiChatRequest.java` 第 19–27 行、`NoteDTO.java` 第 9–18 行）；Spring Boot 对 JSON 没有 multipart 那样的整体上限。
2. **对话并发**：仅按 `sessionId` 去重（`AgentService.java` 第 1002 行），非流式 `/api/ai/chat` 没有全局并发/队列上限；SSE 路径有 4+24 的限制，普通路径没有。
3. **分页未校验**：`GET /api/notes?size=-1` 直接构造 `Page`（`NoteController.java` 第 29–37 行）。MyBatis-Plus 3.5.17 在 `size < 0` 时跳过分页（已用 `javap` 核对字节码），等于返回全表；`page <= 0` 还可能生成非法 offset。文件分页已做 clamp，笔记没有。

**建议**：DTO 加长度上限；对话加全局信号量；分页统一 clamp 到 page ≥ 1、size 1–100。

### M7. 任务注册表只增不减，构建可无限排队

`WikiService.jobs`、`EntityCompileService.jobs`、`KgPipelineService.jobs`、`VectorIndexService.jobs`、`DocumentProcessingJobs.jobs` 均为只 `put` 不删（分别在第 666、88、60、276、26 行附近）。自动维护会周期性产生任务。

另外 `KgPipelineService.start()` 直接提交到单线程执行器，没有"已有任务则拒绝/合并"，可无限堆积花费模型调用的构建请求。

**建议**：任务完成后按时间/条数淘汰；`start()` 增加在途检查。

---

## 4. 低优先级与代码质量

1. **异常信息回传客户端**：兜底处理器把 `e.getMessage()` 拼进响应（`GlobalExceptionHandler.java` 第 30–35 行），可能泄露路径、SQL 片段或上游地址。建议只回 requestId + 通用文案。
2. **前端 7 个测试失败是真实问题**（干净安装后复现）：
   - `mdToHtml.test.js` 使用 `node:module` 的 `registerHooks`（第 4 行），该 API 需 **Node 22.15+**，而 `engines`/README 写 22.14+；
   - `mermaidThemeBehavior.test.js` 用 `/^import .*\n/gm` 剥离 import（第 16 行），不匹配 CRLF；Windows + `core.autocrlf=true` 时 6 个用例全部报 `Cannot use import statement outside a module`。改为 `\r?\n` 即可（其他行为测试已如此）。
3. **未声明的直接依赖**（靠 npm 提升才装得上）：`@codemirror/{state,view,language,lang-*}`、`@tiptap/extension-list`、`@tiptap/extension-underline`、`@tiptap/extension-bold`、`markdown-it-sup`、`markdown-it-sub`、`@vue/compiler-sfc`、`@mixmark-io/domino`。换包管理器或上游依赖树变动会直接构建失败。
4. **源码部署默认弱口令**：`root/root123456`（`deploy/.env.example`）；发布版已随机生成密码并绑定本机，源码版建议一致处理。
5. **文档与代码漂移**：
   - `docs/agent-memory-design.md` 写 `ai.max_tokens 16384 → 32768`，代码默认仍为 `16384`（`SettingsService.java` 第 159 行）；
   - `docs/deploy.md` 写 MCP 19 个工具，`mcp/README.md` 实列 20 个；
   - "后端只监听本机"与源码部署不符（见 H1）。
6. **工程化缺口**：无 ESLint/Prettier/Checkstyle/Spotless；前端 `package.json` 无 `test` 脚本；CI 未运行启动器 C# 测试与 `tools/` 下的 JS 测试。
7. **仓库卫生**：跟踪了 `backend/kg-*-backup.tsv`、`backend/wiki-*-backup.tsv`（本机图谱节点/页面备份，文件头混入 PowerShell 报错文本）与 `tmp-truncated.md`（引用作者 PDF 与笔记生成的真实讲义片段），与 `AGENTS.md` "示例、文档、截图与测试使用合成数据"冲突。
8. **大型懒加载 chunk**：构建警告 `elk` 1.46 MB、`mdEditorSetup` 769 kB；可考虑延迟加载 ELK 布局引擎。
9. **日志隐私**：`application.yml` 默认 `org.dyh.learnhub: debug` + MyBatis StdOutImpl（第 37–48 行）；`KgService` 会把模型输出前 120 字写日志（第 429 行）。源码 JAR 部署建议默认 `info`。

---

## 5. 建议实施顺序

**第 1 批（安全与数据，1–2 天）**

1. 默认绑 `127.0.0.1` + 访问令牌/Host 校验（H1）；
2. `MdPreview` 接 DOMPurify + nginx CSP（H2）；
3. 标题判定改为"归一化相等"并补测试（M2）；
4. `executing` 启动恢复（M3）。

**第 2 批（可靠性，2–4 天）**

5. 抽取移出上传事务（M1）；
6. 笔记保存加版本校验（M4）；
7. DTO 长度上限、对话全局并发、分页 clamp（M6）；
8. 任务表淘汰 + 构建去重（M7）。

**第 3 批（性能与工程化）**

9. 检索侧缓存或 SQL 预筛（M5）；
10. 前端测试 CRLF/Node 版本修复、补齐未声明依赖；
11. 引入 ESLint/Checkstyle，CI 增加启动器与 tools 测试，清理真实数据文件。

---

## 6. 做得好的地方

- **抓取的 SSRF 防护**：一次解析、全部地址必须公网、连接钉在已校验 IP、HTTPS 用主机名校验证书、每跳重定向重新校验、跨源重定向拒绝、字节/字符/时间上限（`WebService.java` 第 379–437 行、`PinnedHttpClient.java` 第 70–120 行）。
- **Word/XML 安全**：关闭 DOCTYPE、外部实体与外部 DTD，限制解压大小（`WordLayoutExtractor.java` 第 152–171 行），并有 XXE 回归测试。
- **上传文档 HTML 净化**：`originalPreviewHtml` 采用白名单重建（标签/属性/CSS/链接/图片逐项校验），不是简单过滤（`originalPreviewHtml.js` 第 85–166 行）；KaTeX 统一 `trust:false`、`strict:error`、MathML 输出。
- **写操作闸门**：参数快照、原子状态流转、幂等拒绝重复审批、失败不谎报成功、定向编辑绑定 `content_hash`（`AgentSessionService.java` 第 548–604 行）。
- **资源上限**：OCR 单图 16 MP/12 MiB、媒体派生 5 MP/8 MiB、PDF 页数、来源 10 万字、网页 64 快照/200 万字符，均有实现与文档。
- **测试质量**：1632 个后端测试（含 SSRF、XXE、迁移、并发状态机）与 855 个前端测试通过；行为断言多于快照断言；CI 覆盖 MySQL 8.0/8.4 隔离迁移与双架构镜像。

---

## 7. 复现方式

```powershell
# 后端（需 JDK 21；本机无 Maven 时可用临时 Maven + 仓库自带镜像配置）
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
mvn -B -s deploy/maven-settings.xml -f backend/pom.xml test
# → Tests run: 1632, Failures: 0, Errors: 0, Skipped: 26

# 前端
cd frontend
npm ci                       # Node ≥ 22.15 才能让 mdToHtml.test.js 通过
node --test src/*/*.test.js  # → 862 项 / 7 失败（见第 4 节第 2 条）
npm run build                # → 成功

# 单独复现两处失败
node --test src/utils/mdToHtml.test.js              # registerHooks 不存在（Node 22.14）
node --test src/utils/mermaidThemeBehavior.test.js  # CRLF 下 import 未被剥离
```

---

## 8. 未覆盖范围

本次审查**没有**验证：

- Docker Compose 全栈启动、MySQL 8.0/8.4 真实迁移、Testcontainers 集成测试；
- Windows 启动器（`.NET` 构建、自更新、`--demo` 界面）与 MCP 服务的端到端流程；
- 真实模型调用的质量、成本与延迟；
- 浏览器内的交互与无障碍（仅阅读组件与工具函数测试）。

以上需在有 Docker 与 .NET SDK 的环境补充验证。
