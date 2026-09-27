# db-connect-mcp

本机使用的只读 MCP 服务。第一版连接一个 MySQL 数据库和一个 Redis 实例；Codex 只连接 MCP 地址，不配置数据库或 Redis 凭据。

## 环境

- JDK 25、Maven 3.6.3 或更新版本
- Spring Boot 4.0.8、Spring AI 2.0.1
- 可访问的 MySQL 与 Redis，以及各自的只读账号

## YAML 配置

在 [`src/main/resources/application.yml`](src/main/resources/application.yml) 中填写 `dbmcp.database` 和 `spring.data.redis` 下的连接地址、账号和密码。文件当前包含示例占位值；不要将填写真实凭据后的文件提交到公开仓库。Codex 只需连接 MCP 地址，不需要配置这些连接信息。

`pom.xml` 中的 `<finalName>db-mcp</finalName>` 决定 JAR 名称。修改它后，可用 `DB_MCP_JAR_NAME` 环境变量让启动脚本使用相同名称。普通构建把可执行 JAR 放在 `target/db-mcp.jar`：

```powershell
mvn clean package
.\db-mcp.cmd start
```

指定导出目录时，普通 JAR 与 Spring Boot 重打包后的可执行 JAR 都写入该目录；运行 `db-mcp.jar`，`db-mcp.jar.original` 是 Spring Boot 保存的原始 JAR：

```powershell
mvn clean package '-Dbase.export.path=D:/opt/packaging/'
.\db-mcp.cmd start D:/opt/packaging/
.\db-mcp.cmd stop
.\db-mcp.cmd restart D:/opt/packaging/
```

Windows 使用 `db-mcp.cmd`（调用 `db-mcp.ps1`）；在 Linux 或 macOS 可使用 `sh ./db-mcp start /opt/packaging/`、`sh ./db-mcp stop` 和 `sh ./db-mcp restart`。`start` 和 `restart` 的第二个参数可以是 JAR 文件或所在目录；省略时会使用上次启动的 JAR，首次启动默认使用 `target/db-mcp.jar`。也可以通过 `DB_MCP_JAR_PATH` 或 `DB_MCP_EXPORT_PATH` 指定位置。脚本把进程号和日志写入项目的 `run/` 目录。

`application.yml` 会打进 JAR，因此修改连接配置后需要重新打包。若正在运行旧 JAR，先停止服务，再构建并启动新 JAR。

YAML 的 JDBC URL 必须以 `jdbc:mysql:` 开头。Oracle、SQL Server、PostgreSQL 与 StarRocks 的前缀已识别，但尚未实现对应适配器。由于凭据直接存放在项目的 `application.yml` 中，能读取项目文件的进程（包括具备相应文件访问权限的 Codex）也可能读到凭据；MCP 工具本身不会返回凭据。

## 连接 Codex

启动后，在 Codex 的 MCP 设置中添加 Streamable HTTP 服务：

```text
http://127.0.0.1:8080/mcp
```

服务只监听本机地址。第一版提供以下工具：

| 工具 | 作用 |
| --- | --- |
| `db_list_tables` | 列出当前数据库的表和视图 |
| `db_describe_table` | 查看表结构和主键 |
| `db_query` | 带参数的单条只读 SELECT 查询 |
| `redis_scan_keys` | 使用游标分页扫描 key |
| `redis_inspect_key` | 查看 key 类型、TTL 和大小 |
| `redis_read_key` | 限量读取 string、hash、list、set 或 sorted set |

`db_query` 最多返回 100 行，默认超时 10 秒；Redis 单次扫描和读取的数量也有限制。这些上限可在 `application.yml` 中调整。Redis 内容按 UTF-8 文本读取。工具返回查询结果本身，因此 Codex 会看到请求读取的数据，但不会收到连接账号和密码。

第一版不提供 DDL、DML 或 Redis 写入工具。仍应在 MySQL 和 Redis 端使用只读账号：工具校验及 MCP 注解不能代替数据库权限。以后增加其他数据库时实现 `DatabaseDialect` 并注册为 Spring Bean；写入操作应作为独立工具和授权层加入。

## 验证

```powershell
mvn test
mvn package
```

启动后用 Codex 的 `/mcp` 查看工具列表，再用只读账号试运行 `db_list_tables`、`db_query` 和 Redis 读取工具。没有真实 MySQL、Redis 实例时，单元测试和 MCP 工具注册可以验证，但无法验证实际数据读取。
