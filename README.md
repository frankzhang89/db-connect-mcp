# db-connect-mcp

A self-hostable MCP server for one MySQL database and one Redis instance, providing both read and write tools. It binds to `127.0.0.1` by default and can be deployed to a remote server. Codex connects to the MCP endpoint; it does not need the database or Redis credentials.

## Requirements

- JDK 25 and Maven 3.6.3 or newer
- Spring Boot 4.0.8 and Spring AI 2.0.1
- Reachable MySQL and Redis instances with a least-privilege account (reads and writes share it)

## Configuration

Set the connection addresses, usernames, and passwords under `dbmcp.database` and `spring.data.redis` in [`src/main/resources/application.yml`](src/main/resources/application.yml). The file currently contains example values. Do not commit it to a public repository after adding real credentials. Codex only needs the MCP endpoint URL. The same MySQL account is used for both read and write tools. The default Redis database comes from `spring.data.redis.database`; `redis_select_db` can switch it at runtime. Redis tool limits (`dbmcp.redis.max-items`, `dbmcp.redis.max-value-chars`, `dbmcp.redis.max-scan-count`, `dbmcp.redis.max-databases`) are optional properties with sensible defaults.

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

Double-clicking `db-mcp.cmd`, or running it without arguments, defaults to `restart`. Both launchers wait for Spring Boot startup to complete and report the service's actual listening port. On an interactive terminal, they keep the result visible for five seconds, or until any key is pressed; redirected/non-interactive runs skip this pause. Closing this prompt leaves the background service running.

Startup waits up to 60 seconds; override this with `DB_MCP_STARTUP_TIMEOUT_SEC` (a positive integer). A timeout leaves the recorded process available for `stop` or `restart`. Both scripts prefer `JAVA_HOME/bin/java` when available, otherwise they use Java from `PATH`.

If startup fails because a port is already in use, the launcher reports the conflicting port, owner PID, executable, and program directory. For Java processes launched with `-jar`, it also reports the application JAR and uses its directory when resolvable. Linux diagnostics use `ss` (iproute2), `lsof`, or `fuser`; process details can be unavailable when permissions prevent inspection or the owner has already exited. The scripts do not terminate the conflicting process.

For `start` and `restart`, the optional second argument can be a JAR file or its directory. If omitted, the scripts reuse the last started JAR; on the first run, they use `target/db-mcp.jar`. You can also specify the location with `DB_MCP_JAR_PATH` or `DB_MCP_EXPORT_PATH`. The scripts store the process ID and logs in the project's `run/` directory.

## Connect Codex

After starting the server, add this Streamable HTTP endpoint in Codex's MCP settings:

```text
http://127.0.0.1:8283/db-mcp
```

The server listens on `127.0.0.1` by default; see [Remote deployment](#remote-deployment) to run it on a server. This version provides twelve tools:

| Tool | Purpose |
| --- | --- |
| `db_list_tables` | List tables and views in the configured database (at most 500) |
| `db_describe_table` | Inspect a table's columns and primary key |
| `db_query` | Run one parameterized, read-only `SELECT` query |
| `redis_scan_keys` | Scan keys with cursor-based pagination |
| `redis_inspect_key` | Inspect a key's type, TTL, and size |
| `redis_read_key` | Read a limited amount of data from a string, hash, list, set, or sorted set |
| `db_execute_dml` | Run one parameterized `INSERT`/`UPDATE`/`DELETE`/`MERGE`/`UPSERT` statement |
| `db_execute_ddl` | Run one `CREATE`/`ALTER`/`DROP`/`TRUNCATE`/`RENAME` statement (requires `dbmcp.write.allow-ddl`) |
| `redis_write_key` | Write a string, list, set, hash, or sorted set key with an optional TTL |
| `redis_delete_keys` | Delete one or more keys and report the removed count |
| `redis_expire_key` | Set or remove a key's TTL |
| `redis_select_db` | Switch the Redis database used by all Redis tools (SELECT) |

`db_query` returns at most 100 rows and has a default timeout of 10 seconds. Redis scans and reads also have limits. These limits can be changed in `application.yml`. Redis values are decoded as UTF-8 text. Codex can see the data returned by a requested read, but the tools do not expose connection credentials.

## Remote deployment

By default the server binds to `127.0.0.1`, so only local clients can reach the endpoint. To run it on a remote server:

1. Bind a reachable interface: set the environment variable `SERVER_ADDRESS=0.0.0.0` (all interfaces) or the server's private IP, and optionally `SERVER_PORT`. These override `server.address` and `server.port` in `application.yml`. Open the port in the firewall; the MCP endpoint is then `http://<server-host>:8283/db-mcp`.
2. Configure `dbmcp.database` and `spring.data.redis` with addresses reachable from the server, rebuild, and restart.
3. The MCP endpoint has **no built-in authentication** and now exposes write tools, so restrict who can reach it: allow-list client IPs in the firewall, bind a private interface, or place it behind a reverse proxy or VPN with authentication. Do not expose the endpoint to the public internet. Database and Redis credentials stay on the server; MCP clients never see them.

## Write tools

Read and write access share the connection and account configured under `dbmcp.database`, so the write tools are always registered alongside the read tools. `dbmcp.write.allow-ddl` (default `true`) is the only write-specific switch; set it to `false` to reject DDL while keeping DML available.

- `db_execute_dml` runs one parameterized `INSERT`, `UPDATE`, `DELETE`, `MERGE`, or `UPSERT` statement and returns the affected row count plus auto-generated keys for `INSERT` (at most 50).
- `db_execute_ddl` runs one `CREATE`, `ALTER`, `DROP`, `TRUNCATE`, or `RENAME` statement.
- `redis_write_key` writes a string, list, set, hash, or sorted set key. Lists append to the tail and hashes merge incrementally; an optional `ttlSeconds` sets the expiry.
- `redis_delete_keys` deletes at most `dbmcp.redis.max-items` keys per call.
- `redis_expire_key` sets a TTL, or removes it when `ttlSeconds` is `0`.
- `redis_select_db` switches the Redis database for all subsequent Redis tools. Spring Data Redis rejects `SELECT` on shared Lettuce connections, so the switch creates a dedicated connection factory for the target database; the allowed range is `0` to `dbmcp.redis.max-databases - 1` (16 databases by default), and the starting database comes from `spring.data.redis.database`.

Both SQL write tools accept a single statement without comments or `;`, validated by a JSqlParser whitelist before execution, with the shared `dbmcp.query.timeout-seconds` timeout. Redis writes are limited to `dbmcp.redis.max-items` elements of at most `dbmcp.redis.max-value-chars` characters each. The SQL whitelist and MCP annotations do not replace server-side permissions: use a least-privilege account, and remember that `DROP` and `TRUNCATE` are irreversible.

To add another database later, implement `DatabaseDialect` and register it as a Spring Bean.

## Verify

```powershell
mvn test
mvn package
```

After starting the server, use Codex's `/mcp` command to inspect the tool list, then try `db_list_tables`, `db_query`, and the Redis tools with a least-privilege account. Unit tests and MCP tool registration can be checked without live MySQL or Redis instances, but actual data reads require running services.
