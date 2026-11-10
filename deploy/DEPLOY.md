# EchoLint 生产部署手册（deploy/）

> 对应审计项 **P2-27**（生产拓扑入库）、**P2-28**（数据库低权限角色）、以及 compose 健康检查加固。
> 生产编排文件：`deploy/docker-compose.prod.yml`；本手册与其配套，避免部署方式只存在于服务器 backups。

---

## 1. 文件与关系

| 文件 | 用途 |
|---|---|
| `deploy/docker-compose.prod.yml` | **生产编排（独立文件，非 root compose 的 overlay）** |
| `deploy/.env.prod.example` | 生产环境变量模板（复制为 `.env.prod`，已被 `deploy/.gitignore` 忽略） |
| `deploy/postgres/init/01-app-role.sql` | 仅全新空数据卷首次初始化：vector 扩展 + 低权限角色 `echolint_app` + 库 owner |
| `deploy/postgres/init/02-app-password.sh` | 仅全新空数据卷首次初始化：从 `ECHOLINT_APP_PASSWORD` 写入应用角色口令（缺失/示例口令时 fail-closed） |

与仓库根 `docker-compose.yml` 的关系：

- **本地开发**继续使用仓库根 `docker-compose.yml`（回环端口 + 仓库根 `.env`）。
- **生产**使用 `deploy/docker-compose.prod.yml`。两者 `name: echolint`、服务名、镜像名与命名卷名一致，
  因此共用 `echolint_pg-data` / `echolint_uploads` / `echolint_knowledge-docs` 卷与
  `echolint-backend` / `echolint-frontend` 镜像——从开发切生产不会换卷丢数据。
- 生产差异：frontend 发布到宿主 `80`（公网入口）；backend/postgres 仍只绑 `127.0.0.1`；
  backend 使用低权限数据库角色 `echolint_app`；backend 的变量来自 `deploy/.env.prod`。

## 2. 前置条件

- Docker Engine 24+、Docker Compose **≥ 2.24**（`env_file` 长语法 `required: false`）。
- 仓库 checkout 到服务器（如 `/opt/echolint`），且已构建/拉取所需基础镜像。
- 宿主已安装 `curl`（健康检查与运维脚本使用；容器内部不需要）。
- 语音转写（部署后在「系统设置 → 语音转写」选择，二选一）：
  ① 阿里云百炼录音文件识别——只需在设置页录入 API Key，无需宿主服务（推荐）；
  ② 本机 whisper.cpp（可选兜底）——见 `tools/whisper-server.sh` 顶部注释；Linux 宿主需
  `WHISPER_HOST=0.0.0.0` 启动并用防火墙只放行 Docker 网桥网段（whisper.cpp 无鉴权）。
- HTTPS：本编排默认仍是 HTTP 80（与现有部署一致），TLS 由宿主反代终结，见 §5。

## 3. 端口矩阵

| 服务 | 生产端口（本编排） | 说明 |
|---|---|---|
| frontend (nginx) | `0.0.0.0:80 → 80` | 公网入口；使用宿主 TLS 反代时改为 `127.0.0.1:8081:80` |
| backend (Spring Boot) | `127.0.0.1:8082 → 8080` | 仅回环；`/actuator/health` 可直接本机访问 |
| postgres | `127.0.0.1:5433 → 5432` | 仅回环；备份/运维用 |
| whisper.cpp（宿主进程） | `:9900` | 可选（本地方案）；容器经 `host.docker.internal:9900` 访问，百炼方案无需 |

## 4. 首次部署（全新数据卷）

```bash
cd /opt/echolint

# 1) 准备生产变量文件
cp deploy/.env.prod.example deploy/.env.prod
chmod 600 deploy/.env.prod
$EDITOR deploy/.env.prod          # 替换所有 CHANGE_ME_*；API Key 推荐部署后在系统设置页录入（加密落库）

# 2) 部署前自检（可选但推荐）：示例口令/空口令必须为零
grep -n 'CHANGE_ME' deploy/.env.prod && echo "!! 仍有示例口令，停止" && exit 1

# 3) 校验配置（本机有 .env 也不会误用：生产只读 .env.prod）
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml config -q

# 4) 启动（首次会执行 postgres init 脚本创建 vector 扩展与 echolint_app 角色）
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml up -d --build

# 5) 验证
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml ps
curl -fsS http://127.0.0.1:8082/actuator/health   # {"status":"UP"}
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1/   # 200
docker exec echolint-postgres psql -U audit -d echolint -tAc \
  "select rolname, rolsuper from pg_roles where rolname='echolint_app';"   # echolint_app|f
```

> 全新空库首次启动的 Flyway V3 排序问题（V3 在 Hibernate 建表前 UPDATE 业务表）仍存在，处理步骤见
> `docs/postgres-migration.md` §0.1（先临时 `SPRING_FLYWAY_ENABLED=false` 建表，再恢复 Flyway）。

## 5. TLS 反向代理（推荐）

生产入口保留 HTTP 仅供内网调试，公网由宿主 nginx/Caddy 终结 TLS：

1. 把本编排 frontend 的端口改为只绑回环：`- "127.0.0.1:8081:80"`。
2. 宿主 nginx 站点示例（证书路径按需替换）：

```nginx
server {
    listen 443 ssl http2;
    server_name echolint.example.com;

    ssl_certificate     /etc/letsencrypt/live/echolint.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/echolint.example.com/privkey.pem;

    client_max_body_size 201m;          # 与容器内 nginx 的 200MiB 口径一致
    proxy_read_timeout 600s;
    proxy_request_buffering off;

    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
server {
    listen 80;
    server_name echolint.example.com;
    return 301 https://$host$request_uri;
}
```

3. 启用 HTTPS 后，把 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`（即
   `server.servlet.session.cookie.secure`，加入 `deploy/.env.prod`）打开，避免会话 Cookie 走明文。

## 6. 从现有部署（根 compose）切换到生产编排

现有生产若用根 compose 启动，数据卷和镜像名与本编排一致，切换不会丢数据。建议在维护窗口执行：

```bash
cd /opt/echolint
git pull                                   # 先同步本仓库（含 deploy/）

# 1) 准备生产变量（沿用现有密钥，不要重生成 APP_SECRET_KEY）
cp deploy/.env.prod.example deploy/.env.prod
chmod 600 deploy/.env.prod
$EDITOR deploy/.env.prod                   # 迁移已有值：API Key、APP_SECRET_KEY、ADMIN 口令等

# 2) 停机（根 compose 与生产编排共用容器名，先停旧的）
docker compose down

# 3) 只用生产编排先启动 postgres：已有数据卷不会自动执行 init 脚本，
#    但容器会挂载 deploy/postgres/init，便于下面手工执行同样的初始化脚本
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml up -d postgres

# 4) 数据库角色最小化迁移（现有卷，init 脚本不会自动执行；以下步骤幂等）
#    4.1 创建低权限角色 + 扩展 + 库 owner
docker exec -i echolint-postgres psql --username audit --dbname echolint \
  -f /docker-entrypoint-initdb.d/01-app-role.sql
#    4.2 把既有表/序列/迁移历史（owner=audit）转给 echolint_app，
#        否则 ddl-auto/Flyway 无权修改既有表
docker exec -i echolint-postgres psql --username audit --dbname echolint -c \
  'REASSIGN OWNED BY audit TO echolint_app;'
#    4.3 设置应用角色口令（值来自 deploy/.env.prod）
docker exec -e ECHOLINT_APP_PASSWORD="$(grep '^ECHOLINT_APP_PASSWORD=' deploy/.env.prod | cut -d= -f2-)" \
  -i echolint-postgres bash /docker-entrypoint-initdb.d/02-app-password.sh

# 5) 启动完整生产编排
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml up -d --build
```

> ⚠️ 第 4.2 步的 `REASSIGN OWNED BY audit` 只影响 `echolint` 库内 audit 拥有的对象（不会动其它库）；
> 若不执行，应用角色访问既有表会因 owner 不同而失败（尤其 Flyway V4/V5 的 ALTER TABLE）。
>
> ⚠️ 角色口令/超级用户口令变更对**已有卷**不会自动生效：必须像 4.3 一样先在库内改，
> 再更新 `deploy/.env.prod`，最后重启 backend。超级用户口令仅在需要从宿主机 TCP
> （`127.0.0.1:5433`）远程连接时才有影响——容器内的本地 socket 为 trust 认证，
> 备份命令 `docker exec ... pg_dump -U audit` 不需要口令。

## 7. 备份与恢复

```bash
BACKUP=/opt/echolint/backups/$(date +%Y%m%d-%H%M%S); mkdir -p "$BACKUP"
# 1) 数据库（含全部业务表与 flyway 历史）
docker exec echolint-postgres pg_dump -U audit -d echolint | gzip > "$BACKUP/echolint.sql.gz"
# 2) 上传录音（含双声道分轨）
docker run --rm -v echolint_uploads:/src:ro -v "$BACKUP":/dst alpine \
  tar czf /dst/uploads.tgz -C /src .
# 3) 知识库原文（PDF/DOCX/TXT/MD；不备份 = 导入文档永久丢失）
docker run --rm -v echolint_knowledge-docs:/src:ro -v "$BACKUP":/dst alpine \
  tar czf /dst/knowledge-docs.tgz -C /src .
# 4) 生产变量文件（含密钥，注意目录权限 700）
install -m 600 deploy/.env.prod "$BACKUP/env.prod"

# 恢复（示例：整库）
gunzip -c "$BACKUP/echolint.sql.gz" | docker exec -i echolint-postgres psql -U audit -d echolint
docker run --rm -v echolint_uploads:/dst -v "$BACKUP":/src alpine \
  sh -c 'cd /dst && tar xzf /src/uploads.tgz'
docker run --rm -v echolint_knowledge-docs:/dst -v "$BACKUP":/src alpine \
  sh -c 'cd /dst && tar xzf /src/knowledge-docs.tgz'
```

建议 cron 每日执行并保留 7~30 天（保留期按合规要求确定）。

## 8. 回滚

```bash
# 1) 代码/编排回滚到上一个版本
git checkout <上一个部署的 commit> -- deploy/
# 2) 镜像回滚（部署前先给当前镜像打 tag）：部署前执行
#    docker tag echolint-backend:latest echolint-backend:rollback-$(date +%Y%m%d-%H%M%S)
docker tag echolint-backend:rollback-20261022-230331 echolint-backend
docker tag echolint-frontend:rollback-20261022-230331 echolint-frontend
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml up -d --no-build
# 3) 数据库回滚：从 §7 的 pg_dump 恢复；涉及迁移时先与开发确认 Flyway checksum/版本
```

## 9. 日常升级

```bash
cd /opt/echolint
git pull
docker tag echolint-backend:latest echolint-backend:rollback-$(date +%Y%m%d-%H%M%S)   # 保留回滚点
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml up -d --build
docker compose --env-file deploy/.env.prod -f deploy/docker-compose.prod.yml ps
```

前端单独发版可只 `... build frontend && ... up -d frontend`（`/assets/` 带内容哈希，可安全长缓存）。

## 10. 健康检查与排障

| 现象 | 排查命令 |
|---|---|
| backend 一直 unhealthy | `docker inspect --format '{{json .State.Health}}' echolint-backend \| jq`；`docker logs --tail 200 echolint-backend` |
| DB 连接失败 | `docker exec echolint-postgres psql -U echolint_app -d echolint -c 'select 1'`（应返回 1） |
| 转写全 FAILED | docker 内探端口：`docker exec echolint-backend bash -c 'echo > /dev/tcp/host.docker.internal/9900 && echo REACHABLE'`；宿主 `curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:9900/v1/audio/transcriptions -F file=@sample.wav -F model=whisper-1` |
| 上传 413 | 确认文件 ≤ 200MiB；nginx 侧 `client_max_body_size 201m`、后端 `app.max-size-bytes=209715200` |
| 磁盘占用高 | 检查 `docker logs` 轮转（各服务 10m×3）、`/var/lib/docker/volumes/echolint_*` 体积、备份目录 |
| 会话/Cookie 异常 | 确认是否已启用 HTTPS 并打开 `session.cookie.secure`（§5） |

## 11. 注意事项

- **密钥文件**：`deploy/.env.prod` 绝不能提交；权限 600；轮换 `APP_SECRET_KEY` 会导致已存 AI 供应商密文不可解密，需重新录入。
- **数据库角色**：应用使用 `echolint_app`（非超级用户）；`audit` 仅用于初始化/备份/运维，不要写进应用配置。
- **Flyway 空库首启**：`docs/postgres-migration.md` §0.1 的绕过步骤仍适用（不改已发布的迁移文件）。
- **单实例假设**：当前编排和进程内索引/配额均为单实例语义，不要横向扩容 backend（见 `docs/production-readiness.md` 已知限制）。
- **HTTPS**：本手册提供宿主反代方案；未启用 TLS 前不要对公网开放敏感操作，并按已知项尽快补上。
