# Harness Server

My Harness For Codex 的中台后端。已提供登录鉴权、设备注册、隔离项目、Agent WebSocket、会话/Turn、Skill 下发和用户定向实时事件。

## 本地启动

运行环境：Java 8、Maven、MySQL 5.7.19 或 MySQL 8。

1. 使用 `src/main/resources/db/schema.sql` 初始化 `newharness` 数据库（可交付副本：`../../docs/newharness.sql`）。全新数据库只执行完整初始化脚本，无需再执行历史迁移脚本。脚本不复制旧 `harness` 库的数据或管理员账号。
   已使用旧版 `schema.sql` 初始化过的数据库，先按需执行 `migration-agent-v1.sql`、`migration-dynamic-workspace.sql`，最后执行一次 `migration-project-isolation.sql`。
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

一个 `agent_workspace` 只能绑定一个 `codex_project`。会话通过数据库复合外键同时锁定项目、用户、设备和工作区；项目会话接口会再次校验当前用户和项目。Agent 连接 `/ws/agent`，浏览器使用 JWT 连接 `/ws/client?access_token=<token>`，会话事件只发送给会话所有者。生产环境应将 `public-base-url` 配为 HTTPS，并在反向代理上提供 WSS。

除 Agent 注册和 Skill 下载接口外，`/api/v1/**` 均要求 `Authorization: Bearer <JWT>`。

## 验证

```powershell
mvn test
mvn -DskipTests package
```
