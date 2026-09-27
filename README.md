# db-connect-mcp

A local, read-only MCP server for one MySQL database and one Redis instance. Codex connects to the MCP endpoint; it does not need the database or Redis credentials.

## Requirements

- JDK 25 and Maven 3.6.3 or newer
- Spring Boot 4.0.8 and Spring AI 2.0.1
- Reachable MySQL and Redis instances with read-only accounts

## Configuration

Set the connection addresses, usernames, and passwords under `dbmcp.database` and `spring.data.redis` in [`src/main/resources/application.yml`](src/main/resources/application.yml). The file currently contains example values. Do not commit it to a public repository after adding real credentials. Codex only needs the MCP endpoint URL.

The JDBC URL must begin with `jdbc:mysql:`. The code recognizes prefixes for Oracle, SQL Server, PostgreSQL, and StarRocks, but adapters for those databases have not been implemented yet.

`application.yml` is packaged inside the JAR. After changing the connection settings, stop the running server, rebuild the JAR, and start it again.

Credentials stored in `application.yml` can be read by any process with access to the project files, including Codex if it has that access. MCP tools do not return credentials.

## Build and run

The `<finalName>db-mcp</finalName>` setting in `pom.xml` controls the JAR filename. If you change it, set `DB_MCP_JAR_NAME` to the corresponding filename when using the startup scripts.

A standard build creates the executable JAR at `target/db-mcp.jar`:

```powershell
mvn clean package
.\db-mcp.cmd start
```

To write the JAR to a specific directory, set `base.export.path`. Spring Boot also writes `db-mcp.jar.original` there; run `db-mcp.jar`, which is the executable JAR.

```powershell
mvn clean package '-Dbase.export.path=D:/opt/packaging/'
.\db-mcp.cmd start D:/opt/packaging/
.\db-mcp.cmd stop
.\db-mcp.cmd restart D:/opt/packaging/
```

On Windows, `db-mcp.cmd` invokes `db-mcp.ps1`. On Linux or macOS, use `sh ./db-mcp start /opt/packaging/`, `sh ./db-mcp stop`, or `sh ./db-mcp restart`.

For `start` and `restart`, the optional second argument can be a JAR file or its directory. If omitted, the scripts reuse the last started JAR; on the first run, they use `target/db-mcp.jar`. You can also specify the location with `DB_MCP_JAR_PATH` or `DB_MCP_EXPORT_PATH`. The scripts store the process ID and logs in the project's `run/` directory.

## Connect Codex

After starting the server, add this Streamable HTTP endpoint in Codex's MCP settings:

```text
http://127.0.0.1:8080/mcp
```

The server listens only on the local address. This version provides six read-only tools:

| Tool | Purpose |
| --- | --- |
| `db_list_tables` | List tables and views in the configured database |
| `db_describe_table` | Inspect a table's columns and primary key |
| `db_query` | Run one parameterized, read-only `SELECT` query |
| `redis_scan_keys` | Scan keys with cursor-based pagination |
| `redis_inspect_key` | Inspect a key's type, TTL, and size |
| `redis_read_key` | Read a limited amount of data from a string, hash, list, set, or sorted set |

`db_query` returns at most 100 rows and has a default timeout of 10 seconds. Redis scans and reads also have limits. These limits can be changed in `application.yml`. Redis values are decoded as UTF-8 text. Codex can see the data returned by a requested read, but the tools do not expose connection credentials.

This version does not provide DDL, DML, or Redis write tools. Use read-only accounts in MySQL and Redis as well: tool validation and MCP annotations do not replace server-side permissions. To add another database later, implement `DatabaseDialect` and register it as a Spring Bean. Write operations should be added as separate tools with their own authorization layer.

## Verify

```powershell
mvn test
mvn package
```

After starting the server, use Codex's `/mcp` command to inspect the tool list, then try `db_list_tables`, `db_query`, and the Redis read tools with read-only accounts. Unit tests and MCP tool registration can be checked without live MySQL or Redis instances, but actual data reads require running services.
