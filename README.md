# Harness Server

My Harness For Codex 的中台后端。已提供登录鉴权、设备注册、隔离项目、Agent WebSocket、会话/Turn、Skill 下发和用户定向实时事件。

## 本地启动

运行环境：Java 21、Maven 3.6.3+、MySQL 8、Redis。当前本机环境为 MySQL 8.0.23、Redis 8.10.1。

技术基线：Spring Boot 3.5.16、MyBatis Starter 3.0.5（MyBatis 3.5.19 / MyBatis-Spring 3.0.5）。MySQL Connector/J、Spring Data Redis 和 Lettuce 由 Spring Boot 管理版本。POM 强制构建 JDK 为 21，主代码与测试均生成 Java 21 字节码，不能再使用 Java 8 启动。

Redis 使用 `spring.data.redis.*` 配置；外部配置中的旧 `spring.redis.*` 需同步迁移，环境变量对应 `SPRING_DATA_REDIS_HOST`、`SPRING_DATA_REDIS_PORT`、`SPRING_DATA_REDIS_PASSWORD`。本次升级不修改数据库结构、业务协议或启用虚拟线程。

1. 使用 `src/main/resources/db/schema.sql` 初始化 `newharness` 数据库（可交付副本：`../../docs/newharness.sql`）。全新数据库只执行完整初始化脚本，无需再执行历史迁移脚本。脚本不复制旧 `harness` 库的数据或管理员账号。
   已使用旧版 `schema.sql` 初始化过的数据库，先按需执行 `migration-agent-v1.sql`、`migration-dynamic-workspace.sql`、`migration-project-isolation.sql`，再按 [用户与机器授权上线说明](../../docs/user-device-rbac.md) 显式选择管理员，执行一次 `migration-user-device-rbac.sql`。
   已有多个 Skill Version 的数据库在部署本版本前，还需执行一次 `migration-skill-single-active-version.sql`，将每个 Skill 的最新版本设为 ACTIVE，并停用旧版本。
2. 在 PowerShell 中设置本地配置：

```powershell
$env:HARNESS_DB_USERNAME = 'root'
$env:HARNESS_DB_PASSWORD = '你的数据库密码'
$env:HARNESS_JWT_SECRET = '至少32字节的随机开发密钥'
$env:HARNESS_BOOTSTRAP_ADMIN_ENABLED = 'true'
$env:HARNESS_BOOTSTRAP_ADMIN_USERNAME = 'admin'
$env:HARNESS_BOOTSTRAP_ADMIN_PASSWORD = '首次管理员密码'
$env:HARNESS_BOOTSTRAP_ADMIN_DISPLAY_NAME = 'Administrator'
$env:HARNESS_AGENT_PUBLIC_BASE_URL = 'http://localhost:9010'
$env:HARNESS_AGENT_SKILL_STORAGE_DIR = 'D:/my-harness/skill-storage'
```

3. 启动服务：

```powershell
mvn spring-boot:run
```

服务默认监听 `http://localhost:9010`。管理员创建成功后，应关闭 `HARNESS_BOOTSTRAP_ADMIN_ENABLED` 并清除环境中的管理员明文密码；已有同名管理员不会被覆盖。

## Conversation Artifact 文件交付

支持 Agent 交付文件上传、会话文件卡片、历史下载和失败重试。升级前执行 [产物迁移](src/main/resources/db/migration-conversation-artifacts.sql)，并持久化 harness.artifacts.storage-dir。完整协议、接口、限制和验收说明见 [Conversation Artifact 方案](../../docs/conversation-artifacts.md)。产物 Agent 接口使用 Device Token，用户列表、下载与重试使用 JWT。

## 已实现接口

- `POST /api/v1/auth/login`
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
- `POST /api/v1/projects/{projectId}/conversations`
- `GET /api/v1/projects/{projectId}/conversations`
- `GET /api/v1/projects/{projectId}/conversations/{id}`
- `GET /api/v1/projects/{projectId}/conversations/{id}/active-turn`
- `GET /api/v1/projects/{projectId}/conversations/{id}/messages`
- `GET /api/v1/projects/{projectId}/conversations/{id}/approvals`
- `POST /api/v1/projects/{projectId}/conversations/{id}/turns`
- `POST /api/v1/projects/{projectId}/conversations/{conversationId}/turns/{turnId}/interrupt`
- `POST /api/v1/approvals/{id}/decision`
- `POST /api/v1/skill-deployments/devices/{deviceId}/versions/{versionId}`
- `POST /api/v1/skill-deployments/{id}/remove`

一个 `agent_workspace` 只能绑定一个 `codex_project`。会话通过数据库复合外键同时锁定项目、用户、设备和工作区；项目会话接口检查当前用户、归属和机器授权。Agent 连接 `/ws/agent`，浏览器先以 JWT 调用 `POST /api/v1/auth/socket-ticket`，再连接 `/ws/client?ticket=<一次性票据>`；不再接受长期 JWT 查询参数。生产环境应通过同源反向代理提供 HTTPS/WSS。用户管理、新接口和迁移说明见 [用户与机器授权](../../docs/user-device-rbac.md)。

除 Agent 注册和 Skill 下载接口外，`/api/v1/**` 均要求 `Authorization: Bearer <JWT>`。

## 验证

```powershell
java -version
mvn -version
mvn clean verify
# 启动独立临时 Redis，不连接或清空业务 Redis
mvn '-Dredis.integration=true' test
# 只读连接配置中的 MySQL，执行 SELECT；不启动业务服务或执行初始化 SQL
mvn '-Dmysql.integration=true' '-Dtest=MysqlCompatibilityTest' test
```

测试 JVM 已显式加载 Mockito agent，并把临时目录放在模块 `target` 下。IDE 的 Project SDK、Maven Runner 与部署服务均需选用 JDK 21；如全局 Maven settings 仍激活 `jdk-1.8` profile，请在本机设置中移除或调整，项目不会修改用户的全局配置。

2026-09-04 升级验证：`mvn clean verify '-Dredis.integration=true' '-Dmysql.integration=true'` 通过，46 项测试全部成功，包含 MyBatis/Jakarta/Redis 自动配置、14 项独立 Redis 8.10.1 测试和 MySQL 只读连接测试。可执行 JAR 已生成，入口字节码 major version 为 65（Java 21）。未执行数据库迁移或生产发布。
