# db-connect-mcp

A self-hostable MCP server with one current JDBC database and one Redis instance, providing read and write tools. Supported JDBC databases are MySQL, Oracle, Microsoft SQL Server, PostgreSQL, and StarRocks. Clients authenticate with a shared Bearer token and can switch the global JDBC data source without restarting. Startup database and Redis credentials stay on the server; switching requires supplying the new database credentials through a tool call.

## Requirements

- JDK 25 and Maven 3.8.x or newer
- Spring Boot 4.0.8 and Spring AI 2.0.1
- A reachable supported JDBC database and Redis instance with least-privilege accounts (SQL reads and writes share one account)

## Configuration

Set the initial connection under `dbmcp.database`, Redis settings under `spring.data.redis`, and a shared token under `dbmcp.auth` in [`src/main/resources/application.yml`](src/main/resources/application.yml). Do not commit this file publicly after adding real credentials. Clients need the MCP endpoint and token. Read and write tools share the current database account. The default Redis database comes from `spring.data.redis.database`; `redis_select_db` switches its logical database at runtime. Redis tool limits (`dbmcp.redis.max-items`, `dbmcp.redis.max-value-chars`, `dbmcp.redis.max-scan-count`, `dbmcp.redis.max-databases`) are optional properties with sensible defaults.

Startup requires a usable YAML database connection. The service checks the JDBC type, establishes a connection, verifies it with `Connection.isValid`, and resolves the metadata schema before it becomes ready. Invalid credentials, unreachable databases, invalid schema settings, or validation timeouts fail startup. This does not verify permissions for every table or DDL operation. Redis connectivity is still checked when its tools are used.

The JDBC URL selects the adapter automatically. All five independent drivers are included in the executable JAR:

| Database | Example `dbmcp.database.url` | Driver class | Default metadata namespace |
| --- | --- | --- | --- |
| MySQL | `jdbc:mysql://db-host:3306/appdb` | `com.mysql.cj.jdbc.Driver` | Database from the URL (JDBC catalog) |
| Oracle | `jdbc:oracle:thin:@//db-host:1521/FREEPDB1` | `oracle.jdbc.OracleDriver` | Current schema/owner, usually the uppercase user name |
| SQL Server | `jdbc:sqlserver://db-host:1433;databaseName=appdb;encrypt=true` | `com.microsoft.sqlserver.jdbc.SQLServerDriver` | Current schema, commonly `dbo` |
| PostgreSQL | `jdbc:postgresql://db-host:5432/appdb` | `org.postgresql.Driver` | Current schema from the connection's search path, commonly `public` |
| StarRocks | `jdbc:starrocks://fe-host:9030/default_catalog.appdb` | `com.starrocks.cj.jdbc.Driver` | Catalog `default_catalog`, schema/database `appdb` |

Keep usernames and passwords in the separate configuration fields. SQL Server's encrypted connection requires a trusted server certificate whose name matches the host. Configure the database's JDBC TLS options for your deployment. Oracle uses the pure-Java Thin driver, so no local Oracle client is required. Oracle's `ojdbc17` version is pinned for JDK 25 compatibility; Microsoft and PostgreSQL driver versions follow Spring Boot dependency management.

For example, to select PostgreSQL:

```yaml
dbmcp:
  auth:
    enabled: true
    token: "replace-with-a-random-long-token"
  database:
    url: jdbc:postgresql://db-host:5432/appdb
    username: app_user
    password: your-password
    schema: public
```

`dbmcp.database.schema` optionally narrows metadata discovery (Oracle owner, SQL Server/PostgreSQL schema, or StarRocks database); when omitted, the adapter uses the connection's current schema. MySQL uses its URL database instead. This setting does **not** change SQL name resolution: qualify objects in SQL such as `reporting.events`, or configure the connection's default schema/search path separately. `db_describe_table` accepts an optional `schemaName` override. Table lists include catalog/schema fields when the driver supplies them.

Unquoted table/schema names follow the driver's identifier case rules (uppercase in Oracle, lowercase in PostgreSQL). Preserve case-sensitive names with SQL identifier quotes, for example pass `"MixedCase"` including the quotes for a quoted PostgreSQL table. JDBC wildcard characters in metadata names are escaped, so an underscore or percent sign is treated literally.

StarRocks uses its own `com.starrocks:starrocks-connector-j` driver and `jdbc:starrocks:` URL; it does not route through the MySQL adapter. The URL path contains **catalog.database**, and the FE query port defaults to `9030`. The driver's catalog and schema APIs represent these two levels separately. See the [official StarRocks driver documentation](https://docs.starrocks.io/docs/integrations/JDBC_driver/).

One server process has **one current global JDBC data source**, shared by every MCP client. Old pools exist temporarily while operations acquired before a switch finish. There is no session isolation or `expectedDatasourceId` parameter. Use separate server processes if clients need independent database targets.

`application.yml` is packaged inside the JAR. Editing this packaged configuration requires rebuilding and restarting. Use `db_switch_datasource` for a runtime database change. Runtime switches never modify YAML or other configuration files, and restart restores the YAML connection.

Credentials and tokens in `application.yml` are readable by processes with access to the project or JAR. They are not included in tool results or application diagnostic objects. Database credentials supplied to the switch tool may be retained in the MCP client's tool history; the server cannot control client-side recording.

## Shared Bearer authentication

Authentication defaults to enabled even when `dbmcp.auth.enabled` is omitted. Set `dbmcp.auth.token` directly in server YAML to a randomly generated long token (for example, 32 random bytes encoded as 64 hexadecimal characters). This configuration does not depend on an environment variable. The example `replace-with-a-random-long-token` is deliberately rejected; replace it before starting. A missing, blank, placeholder, or malformed token fails startup. Changing the token requires restarting the server and updating clients.

Configure the MCP client to send this header on **every** HTTP request, including initialization, tool discovery, tool calls, and session requests:

```http
Authorization: Bearer <the-token-from-server-yaml>
```

The configured Streamable HTTP MCP endpoint and its subpaths are protected for all HTTP methods. Missing or invalid credentials receive HTTP `401` with `WWW-Authenticate: Bearer`, before any tool executes. Session IDs and URL query parameters do not replace this header. The token grants access to all JDBC and Redis tools; there are no per-user roles, OAuth login, issuance, expiry, or refresh flows. Explicitly setting `dbmcp.auth.enabled: false` disables this check.

## Switch the JDBC database without restarting

Call `db_switch_datasource` with:

```json
{
  "jdbcUrl": "jdbc:postgresql://db-host:5432/appdb",
  "username": "app_user",
  "password": "database-password",
  "schema": "public"
}
```

`jdbcUrl`, `username`, and `password` are required; an empty password is valid. `schema` is optional and affects metadata discovery only. Omitting it uses the new connection's default schema, never the previous target's schema. Supported JDBC prefixes are the same five listed above, and StarRocks continues to use its independent driver.

The service creates and validates a candidate pool while the current data source remains usable. Only successful validation publishes the new pool, dialect, schema, and `datasourceId` together. Subsequent operations use the new target; operations that already acquired the old target finish there. The old pool is closed after its last operation releases it. Multiple switches are serialized. A failed or timed-out candidate is closed, and late validation completion cannot replace the current data source. Repeating the same URL still creates a fresh pool, so changed credentials take effect.

A successful response includes `success`, `previousDatasourceId`, `datasourceId`, `databaseType`, and catalog/schema when available. Existing database tools also return the ID they actually used. IDs are diagnostic only: they do not pin a caller's subsequent operations to that database. Failed switches return a tool error with a code such as `INVALID_ARGUMENT`, `AUTHENTICATION_FAILED`, `CONNECTION_FAILED`, `VALIDATION_FAILED`, or `VALIDATION_TIMEOUT`, without driver messages or credentials.

Optional server-wide timeouts:

```yaml
dbmcp:
  switch:
    timeout-seconds: 15
    validation-timeout-seconds: 5
```

The total deadline includes waiting for another switch and validation, and also applies during startup. The validity-check and driver login timeout must be positive and no greater than the total (maximum 300 seconds). The pool's `dbmcp.database.connection-timeout` still limits waiting to borrow a connection. Candidate cleanup can take additional driver shutdown time; a driver that ignores interruption cannot publish a late result. Driver timeout properties in JDBC URLs may affect their network behavior.

Read/write limits, pool sizing, and `dbmcp.write.allow-ddl` remain server-wide settings. Switching performs no SQL writes, DDL, or migration. It does not change Redis settings. Shutdown rejects new database operations, cancels validation, and closes pools after existing operations release them.

## Build and run

The `<finalName>db-mcp</finalName>` setting in `pom.xml` controls the JAR filename. If you change it, set `DB_MCP_JAR_NAME` to the corresponding filename when using the startup scripts.

A standard build creates the executable JAR at `target/db-mcp/db-mcp.jar`:

```powershell
mvn clean package
.\db-mcp.cmd start target/db-mcp/db-mcp.jar
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

Startup waits up to 60 seconds; override this with `DB_MCP_STARTUP_TIMEOUT_SEC` (a positive integer). A timeout leaves the recorded process available for `stop` or `restart`.

The JDK environment variable name is configurable near the top of each launcher: `$jdkHomeEnvName = 'JDK25'` in `db-mcp.ps1`, and `JDK_HOME_ENV=JDK25` in `db-mcp`. Set that environment variable to the JDK home directory (not its `bin` directory). If the name is empty or the named variable is unset/empty, the scripts use `JAVA_HOME`; if neither home is set, they use Java from `PATH`. A configured home with a missing Java executable produces an error instead of silently selecting another JDK. Startup prints the selected Java executable. `restart` validates the Java selection before stopping the running service.

If startup fails because a port is already in use, the launcher reports the conflicting port, owner PID, executable, and program directory. For Java processes launched with `-jar`, it also reports the application JAR and uses its directory when resolvable. Linux diagnostics use `ss` (iproute2), `lsof`, or `fuser`; process details can be unavailable when permissions prevent inspection or the owner has already exited. The scripts do not terminate the conflicting process.

For deployment, put `db-mcp.jar` alongside the startup scripts. `start` and `restart` default to that sibling JAR, regardless of the caller's working directory or the previously started JAR. The optional second argument can specify a different JAR file or directory; `DB_MCP_JAR_PATH` and `DB_MCP_EXPORT_PATH` also override the default. `DB_MCP_JAR_NAME` can change the expected filename. The recorded JAR path is used only to identify the managed process when checking or stopping it. The scripts store the process ID and logs in the script directory's `run/` folder.

## Connect Codex

After starting the server, configure this Streamable HTTP endpoint and the Bearer request header in your MCP client:

```text
http://127.0.0.1:8283/db-mcp
```

The listening interface comes from `server.address`; see [Remote deployment](#remote-deployment). This version provides thirteen tools:

| Tool | Purpose |
| --- | --- |
| `db_list_tables` | List tables and views in the configured database (at most 500) |
| `db_describe_table` | Inspect a table's columns and primary key, optionally selecting a schema |
| `db_query` | Run one parameterized, read-only `SELECT` query |
| `db_switch_datasource` | Validate and switch the global JDBC target without restarting; memory-only |
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

Set `server.address` to `127.0.0.1` for local-only access. To run it on a remote server:

1. Bind a reachable interface: set the environment variable `SERVER_ADDRESS=0.0.0.0` (all interfaces) or the server's private IP, and optionally `SERVER_PORT`. These override `server.address` and `server.port` in `application.yml`. Open the port in the firewall; the MCP endpoint is then `http://<server-host>:8283/db-mcp`.
2. Configure `dbmcp.database` and `spring.data.redis` with addresses reachable from the server, rebuild, and restart.
3. Keep Bearer authentication enabled and use HTTPS through a TLS reverse proxy for remote access. The token grants access to all write and data-source-switch tools, so restrict network access as appropriate. Startup credentials stay on the server; clients supply new JDBC credentials only when using the switch tool.

## Write tools

Read and write access share the current global connection and account, initially configured under `dbmcp.database`. Write tools are always registered alongside read tools. `dbmcp.write.allow-ddl` (default `true`) is the only write-specific switch; set it to `false` to reject DDL while keeping DML available. Runtime database switches do not change this setting.

- `db_execute_dml` runs one parameterized `INSERT`, `UPDATE`, `DELETE`, `MERGE`, or `UPSERT` statement supported by the target database and returns the affected row count. MySQL and SQL Server INSERTs also return generated keys (at most 50); other DML statements do not request keys. Oracle, PostgreSQL, and StarRocks currently return only affected rows. Oracle's generic JDBC generated-key flag returns ROWID, and PostgreSQL's generic flag may return whole rows, so these are not mislabeled as generated primary keys. `RETURNING` and `OUTPUT` clauses are rejected before execution.
- `db_execute_ddl` runs one `CREATE`, `ALTER`, `DROP`, `TRUNCATE`, or `RENAME` statement.
- `redis_write_key` writes a string, list, set, hash, or sorted set key. Lists append to the tail and hashes merge incrementally; an optional `ttlSeconds` sets the expiry.
- `redis_delete_keys` deletes at most `dbmcp.redis.max-items` keys per call.
- `redis_expire_key` sets a TTL, or removes it when `ttlSeconds` is `0`.
- `redis_select_db` switches the Redis database for all subsequent Redis tools. Spring Data Redis rejects `SELECT` on shared Lettuce connections, so the switch creates a dedicated connection factory for the target database; the allowed range is `0` to `dbmcp.redis.max-databases - 1` (16 databases by default), and the starting database comes from `spring.data.redis.database`.

Both SQL write tools accept a single comment-free statement, validated by a JSqlParser whitelist before execution, with the shared `dbmcp.query.timeout-seconds` timeout. One trailing `;` is allowed on writes (SQL Server MERGE requires it); embedded semicolons remain rejected. Use the target database's SQL syntax: this server does not translate MySQL SQL into Oracle, T-SQL, PostgreSQL, or StarRocks SQL. Vendor-specific syntax outside the parser/statement whitelist is rejected. SQL Server bracket-quoted identifiers are supported, as are PostgreSQL array expressions. Redis writes are limited to `dbmcp.redis.max-items` elements of at most `dbmcp.redis.max-value-chars` characters each.

Read queries reject SELECT INTO, write-bearing CTEs, locking clauses, and known side-effect/sequence functions. Oracle SELECTs run inside `SET TRANSACTION READ ONLY` and are rolled back; MySQL, SQL Server, and PostgreSQL use JDBC's read-only hint and a rollback. StarRocks SELECTs avoid MySQL transaction-mode commands and rollback because transaction capabilities vary by server version. JDBC read-only is only a hint on some drivers. The SQL whitelist and MCP annotations do not replace server-side permissions; functions may have side effects, and writes share the configured account. StarRocks DML/DDL support also depends on server version, catalog, and table model. Use a least-privilege account, and remember that `DROP` and `TRUNCATE` are irreversible.

To add another database later, implement `DatabaseDialect` and register it as a Spring Bean.

## Verify

```powershell
mvn test
mvn package
```

Tests load all five real JDBC drivers and check URL acceptance, schema/name handling, read/write behavior, row limits, and SQL guards. Additional tests cover switch publication, concurrent leases, retirement, timeout/late completion, shutdown, authentication defaults, HTTP methods, and redaction. HTTP integration tests start the actual application against an in-process JDBC test double, perform MCP initialization and tool calls, check failed-switch rollback and restart-to-YAML behavior, and assert that configuration files remain unchanged. They do not establish compatibility with live database servers.

After starting the server, use Codex's `/mcp` command to inspect the tool list, then try `db_list_tables`, `db_describe_table`, `db_query`, and the Redis tools with a least-privilege account. For each target database, verify a parameterized SELECT, metadata in the expected schema, and permitted DML/DDL in a disposable test schema. Use `SELECT 1 FROM dual` for Oracle and `SELECT 1` for the other engines. Real data access requires reachable services and credentials.
