# Harness Server

My Harness For Codex 的中台后端。已提供登录鉴权、设备注册、隔离项目、Agent WebSocket、会话/Turn、Skill 下发和用户定向实时事件。

## 本地启动

运行环境：Java 21、Maven 3.6.3+、MySQL 8、Redis。当前本机环境为 MySQL 8.0.23、Redis 8.10.1。

技术基线：Spring Boot 3.5.16、MyBatis Starter 3.0.5（MyBatis 3.5.19 / MyBatis-Spring 3.0.5）。MySQL Connector/J、Spring Data Redis 和 Lettuce 由 Spring Boot 管理版本。POM 强制构建 JDK 为 21，主代码与测试均生成 Java 21 字节码，不能再使用 Java 8 启动。

Redis 使用 `spring.data.redis.*` 配置，环境变量对应 `SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT`、`SPRING_DATA_REDIS_PASSWORD`。

1. 本版本按全新邮箱账号模型安装，不迁移旧账号。在全新空数据库执行 [schema.sql](src/main/resources/db/schema.sql)（同步副本：[docs/harness.sql](../../docs/harness.sql)），再执行 [seed-rbac.sql](src/main/resources/db/seed-rbac.sql)，核对 2 个角色、20 项权限和 34 条关联。种子脚本可重复执行，启动器只检查、不自动补齐 RBAC。
   仅 TRUNCATE 旧表不能获得新结构；旧库应由部署者备份后重建最终结构。完整 schema 内含一次性 ALTER，不能在已有表的库中反复执行，也不要叠加历史迁移脚本。
   清库重装须同时轮换 `HARNESS_JWT_SECRET`、清理旧 Redis 登录/票据数据并让客户端重新登录，以免用户 ID 复用使旧凭证再次有效。保留最终表结构的全量清空可用 [truncate-all-tables.sql](src/main/resources/db/truncate-all-tables.sql)，之后重新执行种子脚本；清空初始化记录意味着允许重新引导管理员。
   如果已用修正前的完整 schema 初始化，且发布专家报 `Unknown column 'compatible_upgrade'`，先检查 `expert_version` 是否缺少该列；缺失时仅执行一次 [migration-expert-compatible-upgrade.sql](src/main/resources/db/migration-expert-compatible-upgrade.sql) 补齐版本字段，无需清空数据或重跑完整 schema。修正后的全新 schema 已在 `expert_version` 内声明此字段，不再需要该补列脚本。
2. 在 PowerShell 中设置本地配置：

```powershell
$env:HARNESS_DB_USERNAME = 'root'
$env:HARNESS_DB_PASSWORD = '你的数据库密码'
$env:HARNESS_JWT_SECRET = '至少32字节的随机开发密钥'
$env:HARNESS_MODEL_SECRET_KEY = '独立的模型凭据加密密钥'
$env:HARNESS_BOOTSTRAP_ADMIN_ENABLED = 'true'
$env:HARNESS_BOOTSTRAP_ADMIN_EMAIL = 'admin@example.com'
$env:HARNESS_BOOTSTRAP_ADMIN_DISPLAY_NAME = 'Administrator'
$env:SPRING_MAIL_HOST = 'smtp.example.com'
$env:SPRING_MAIL_PORT = '465'
$env:SPRING_MAIL_USERNAME = 'sender@example.com'
$env:SPRING_MAIL_PASSWORD = 'SMTP授权码'
$env:HARNESS_MAIL_ENABLED = 'true'
$env:HARNESS_MAIL_FROM = 'sender@example.com'
$env:HARNESS_MAIL_PUBLIC_BASE_URL = 'https://harness.example.com'
$env:HARNESS_MAIL_HMAC_KEY = '独立随机32字节的Base64密钥'
$env:HARNESS_MAIL_ENCRYPTION_KEY = '另一个独立随机32字节的Base64密钥'
$env:HARNESS_AGENT_PUBLIC_BASE_URL = 'http://localhost:9010'
$env:HARNESS_AGENT_SKILL_STORAGE_DIR = 'D:/my-harness/skill-storage'
```

以下原生 PowerShell 代码可直接复制到终端执行。函数使用 `RandomNumberGenerator` 生成 32 个随机字节，再编码为 Base64；每次调用生成独立密钥。定义函数后，可按需执行对应的环境变量赋值语句：

```powershell
function New-HarnessSecret {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
        [Convert]::ToBase64String($bytes)
    }
    finally {
        $rng.Dispose()
        [Array]::Clear($bytes, 0, $bytes.Length)
    }
}

# JWT 签名密钥
$env:HARNESS_JWT_SECRET = New-HarnessSecret

# 模型 API Key 加密密钥
$env:HARNESS_MODEL_SECRET_KEY = New-HarnessSecret

# 邮件验证码 HMAC 密钥
$env:HARNESS_MAIL_HMAC_KEY = New-HarnessSecret

# 邮件任务加密密钥
$env:HARNESS_MAIL_ENCRYPTION_KEY = New-HarnessSecret
```

也可使用仓库中的 [New-HarnessSecrets.ps1](scripts/New-HarnessSecrets.ps1)；默认生成 JWT 与模型密钥，通过 `-Names` 选择其他项。

| 环境变量 | 用途与读取方式 |
| --- | --- |
| `HARNESS_JWT_SECRET` | JWT 签名；当前代码将生成的字符串按 UTF-8 使用，44 个 ASCII 字符满足至少 32 字节要求 |
| `HARNESS_MODEL_SECRET_KEY` | 模型 API Key 加密；当前代码从该字符串派生 AES 密钥 |
| `HARNESS_MAIL_HMAC_KEY` | 验证码 HMAC；Base64 解码后必须为 32 字节 |
| `HARNESS_MAIL_ENCRYPTION_KEY` | 邮件任务加密；Base64 解码后必须为 32 字节 |

脚本只设置当前 PowerShell 进程的环境变量，随后在同一窗口启动服务即可继承；不显示密钥、不修改 YAML，也不写入系统环境变量。关闭窗口后这些值不再保留，首次生成后应持久保存在部署环境的秘密配置中，所有实例使用相同值。重复运行会更换所选项，不应放入日常启动脚本。

更换 JWT 密钥会使旧 Token 失效；更换模型密钥会使此前加密的模型 API Key 无法解密。模型密钥留空时当前实现复用 JWT 密钥，因此已有模型数据时不要直接更换或补设它。首次安装时设置独立模型密钥，邮件两项也分别独立设置，均不复用 SMTP 凭据。

465 使用 SSL、关闭 STARTTLS；587 使用 STARTTLS 且 required=true、关闭 SSL。两种方式都须开启 `mail.smtp.ssl.checkserveridentity=true` 校验主机名。连接/读取/写入超时均需有限值。启用发信时会静态检查 SMTP 必填项、TLS、站点 URL 和密钥，配置缺失直接报告错误，不尝试创建空壳管理员。关闭发信后仍需保留密钥，供已发出的验证码校验和限流使用。前端站点必须可被收件人访问，生产环境要求 HTTPS。

3. 启动服务：

```powershell
mvn spring-boot:run
```

服务默认监听 `http://localhost:9010`。首次空库启动按配置邮箱创建待激活管理员，后台发送 `https://harness.example.com/activate?token=...`。管理员打开链接自行设置密码后以邮箱登录。初始化后可关闭 `HARNESS_BOOTSTRAP_ADMIN_ENABLED`；永久初始化记录防止重启、修改配置邮箱或管理员被禁用后重复创建、补权。链接过期可在登录页进入激活邮件重发流程。

邀请、激活、6 位验证码找回、邮件任务状态与默认策略见 [邮箱认证架构](../../docs/email-authentication.md)。SMTP 已接受表示进入邮件服务器，不保证到达收件箱；自动化测试不会验证实际邮箱认证或发送真实邮件。

## Workspace 文件与消息附件

上传、下载与 Agent 生成文件统一使用项目工作区。消息附件仅保留关联与校验，平台传输存储配置为 `harness.workspace-files`，默认目录为 `${project.folder}/workspace-file-storage`。旧模块清理与数据库升级见 [Workspace 文件方案](../../docs/workspace-files.md)。

文件重命名、目录删除、文件移动和多选 ZIP 下载需要新增 [文件操作数据库迁移](src/main/resources/db/migration-workspace-file-actions.sql)，并配套升级 Agent/前端。已有数据库按一次性脚本升级；全新环境直接使用更新后的完整 schema。详细能力、限制与未知结果恢复见 [升级与验收](../../docs/workspace-file-actions-release.md)。

## 已实现接口

- `POST /api/v1/auth/login`（JSON 与 `X-Harness-Refresh: 1`）
- `POST /api/v1/auth/refresh`（JSON、专用请求头与 HttpOnly Cookie，详见自动续期说明）
- `POST /api/v1/auth/activity`（有效 Bearer；仅用户交互驱动）
- `POST /api/v1/auth/activation/validate`
- `POST /api/v1/auth/activate`
- `POST /api/v1/auth/activation/resend`
- `POST /api/v1/auth/password-reset/code`
- `POST /api/v1/auth/password-reset`
- `POST /api/v1/users`（邮箱、角色；邀请用户）
- `POST /api/v1/users/{id}/activation-email`（管理员重发）
- `GET /api/v1/auth/profile`
- `POST /api/v1/agent/enroll`（Agent，无 JWT）
- `GET /api/v1/agent/skill-versions/{id}/download`（Agent Device Token）
- `POST /api/v1/devices/enrollments`
- `GET /api/v1/devices`
- `GET /api/v1/devices/{id}/workspaces`
- `GET /api/v1/devices/{id}/workspace-roots`
- `POST /api/v1/devices/{id}/workspaces`
- `PATCH /api/v1/devices/{id}/status`
- `POST /api/v1/projects`
- `GET /api/v1/projects`
- `GET /api/v1/projects/{projectId}`
- `PUT /api/v1/projects/{projectId}`（`{ "projectName": "项目名称" }`）
- `DELETE /api/v1/projects/{projectId}`
- `POST /api/v1/projects/{projectId}/conversations`
- `GET /api/v1/projects/{projectId}/conversations`
- `GET /api/v1/projects/{projectId}/conversations/{id}`
- `PUT /api/v1/projects/{projectId}/conversations/{id}`（`{ "title": "会话名称" }`）
- `DELETE /api/v1/projects/{projectId}/conversations/{id}`
- `GET /api/v1/projects/{projectId}/conversations/{id}/active-turn`
- `GET /api/v1/projects/{projectId}/conversations/{id}/messages`
- `GET /api/v1/projects/{projectId}/conversations/{id}/approvals`
- `POST /api/v1/projects/{projectId}/conversations/{id}/turns`
- `POST /api/v1/projects/{projectId}/conversations/{conversationId}/turns/{turnId}/interrupt`
- `POST /api/v1/approvals/{id}/decision`

一个 `agent_workspace` 只能绑定一个 `codex_project`。会话通过数据库复合外键同时锁定项目、用户、设备和工作区；项目会话接口检查当前用户、归属和机器授权。Agent 连接 `/ws/agent`，浏览器先以 JWT 调用 `POST /api/v1/auth/socket-ticket`，再连接 `/ws/client?ticket=<一次性票据>`；不再接受长期 JWT 查询参数。生产环境应通过同源反向代理提供 HTTPS/WSS。用户管理、新接口和迁移说明见 [用户与机器授权](../../docs/user-device-rbac.md)。

名称修改仅影响展示名称，不改变 Workspace 路径或 Codex Thread。删除会移除平台内的项目/会话及会话历史和附件关联；项目独占 Workspace 标记为不可用，Device 上的实际文件保留。若相关会话仍有 CREATED、RUNNING 或 WAITING_APPROVAL 状态的 Turn，删除返回 409，需先停止任务。准备中或初始化中的项目/会话可删除，晚到的 Agent 回调不会恢复已删除记录。工作区传输操作失效，平台临时传输文件由现有清理任务回收。

登录、激活和找回仅按列出的 POST 路径公开；刷新接口使用专用请求头与 Refresh Token Cookie 独立鉴权，Agent 使用独立凭据。其余用户 API（包括 activity）要求 `Authorization: Bearer <JWT>`，且账号已激活、启用。用户返回 `email` 和 `displayName`，JWT 保存稳定用户 ID、凭证版本、登录会话 ID `sid` 和标准生命周期字段。

用户采用单端登录：后登录替换旧登录，同源浏览器多标签共享当前会话。Redis 保存唯一当前登录会话，鉴权同时检查数据库版本与 Redis 会话。自动续期采用 Access JWT 120 分钟、提前 10 分钟刷新、空闲 120 分钟、登录起绝对最长 7 天；Refresh Token 使用 HttpOnly Cookie，同源 HTTPS 代理及支持 Web Locks 的浏览器为生产部署前提。Redis 暂不可用返回 503，前端保留凭据供恢复后重试。本次不新增 SQL 表；已执行验证、Cookie 配置及 Redis 快照恢复边界见 [自动续期说明](../../docs/automatic-session-renewal.md)，首次单端实现记录见 [单端登录说明](../../docs/single-login-sessions.md)。

## 验证

```powershell
java -version
mvn -version
mvn clean verify
# 启动独立临时 Redis，不连接或清空业务 Redis
mvn '-Dredis.integration=true' test
# 启动独立临时 MySQL（mysqld/mysql 需在 PATH），验证邮件事务与并发；不连接业务数据库
mvn '-Dmysql.isolated.integration=true' test
# 只读连接配置中的 MySQL，执行 SELECT；不启动业务服务或执行初始化 SQL
mvn '-Dmysql.integration=true' '-Dtest=MysqlCompatibilityTest' test
```

测试 JVM 已显式加载 Mockito agent，并把临时目录放在模块 `target` 下。IDE 的 Project SDK、Maven Runner 与部署服务均需选用 JDK 21；如全局 Maven settings 仍激活 `jdk-1.8` profile，请在本机设置中移除或调整，项目不会修改用户的全局配置。

2026-09-04 升级验证：`mvn clean verify '-Dredis.integration=true' '-Dmysql.integration=true'` 通过，46 项测试全部成功，包含 MyBatis/Jakarta/Redis 自动配置、14 项独立 Redis 8.10.1 测试和 MySQL 只读连接测试。可执行 JAR 已生成，入口字节码 major version 为 65（Java 21）。未执行数据库迁移或生产发布。

## 邮箱版本验证（2026-09-10）

后端 `mvn -Dredis.integration=true -Dmysql.isolated.integration=true verify` 已通过：311 项测试中 293 项成功，18 项历史业务数据库 opt-in 测试跳过，0 失败、0 错误；可执行 JAR 已生成。覆盖独立 MySQL 的全新 schema 安装、邀请/激活/找回事务、并发单次消费、失败计数、初始化幂等与邮件租约测试，以及独立 Redis 的限流测试；真实 Spring 调度测试确认慢 SMTP 不阻塞普通任务。需连接既有业务 MySQL 的历史 opt-in 测试未启用。未启动连接业务库的 Server，未执行实际清库或 SMTP 发信。

前端 `npm test` 的 8 项测试与 `npm run test:unit` 的 40 个文件、265 项测试通过，`npm run build`（含类型检查）通过；使用本机 Chrome 的 10 项模拟 API E2E 覆盖激活、找回、邮件重发及邮箱登录。浏览器测试覆盖交互与协议，真实 SMTP/收件效果仍按部署流程验收。

Skill 分配使用 `/api/v1/skill-expert-assignments`，更新专家草稿。详见 [方案](../../docs/skill-expert-assignment.md)。
