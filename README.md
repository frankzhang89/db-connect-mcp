# db-connect-mcp

A self-hostable MCP server for one JDBC database and one Redis instance, providing both read and write tools. Supported JDBC databases are MySQL, Oracle, Microsoft SQL Server, PostgreSQL, and StarRocks. Codex connects to the MCP endpoint; it does not need the database or Redis credentials.

## Requirements

- JDK 25 and Maven 3.8.x or newer
- Spring Boot 4.0.8 and Spring AI 2.0.1
- A reachable supported JDBC database and Redis instance with least-privilege accounts (SQL reads and writes share one account)

## Configuration

Set the connection addresses, usernames, and passwords under `dbmcp.database` and `spring.data.redis` in [`src/main/resources/application.yml`](src/main/resources/application.yml). Do not commit this file to a public repository after adding real credentials. Codex only needs the MCP endpoint URL. The same database account is used for both read and write tools. The default Redis database comes from `spring.data.redis.database`; `redis_select_db` can switch it at runtime. Redis tool limits (`dbmcp.redis.max-items`, `dbmcp.redis.max-value-chars`, `dbmcp.redis.max-scan-count`, `dbmcp.redis.max-databases`) are optional properties with sensible defaults.

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
  database:
    url: jdbc:postgresql://db-host:5432/appdb
    username: app_user
    password: your-password
    schema: public
```

`dbmcp.database.schema` optionally narrows metadata discovery (Oracle owner, SQL Server/PostgreSQL schema, or StarRocks database); when omitted, the adapter uses the connection's current schema. MySQL uses its URL database instead. This setting does **not** change SQL name resolution: qualify objects in SQL such as `reporting.events`, or configure the connection's default schema/search path separately. `db_describe_table` accepts an optional `schemaName` override. Table lists include catalog/schema fields when the driver supplies them.

Unquoted table/schema names follow the driver's identifier case rules (uppercase in Oracle, lowercase in PostgreSQL). Preserve case-sensitive names with SQL identifier quotes, for example pass `"MixedCase"` including the quotes for a quoted PostgreSQL table. JDBC wildcard characters in metadata names are escaped, so an underscore or percent sign is treated literally.

StarRocks uses its own `com.starrocks:starrocks-connector-j` driver and `jdbc:starrocks:` URL; it does not route through the MySQL adapter. The URL path contains **catalog.database**, and the FE query port defaults to `9030`. The driver's catalog and schema APIs represent these two levels separately. See the [official StarRocks driver documentation](https://docs.starrocks.io/docs/integrations/JDBC_driver/).

One server process still connects to **one** configured JDBC database. To expose several database connections simultaneously, run separate server processes with distinct configurations and ports.

`application.yml` is packaged inside the JAR. After changing the connection settings, stop the running server, rebuild the JAR, and start it again.

Credentials stored in `application.yml` can be read by any process with access to the project files, including Codex if it has that access. MCP tools do not return credentials.

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

After starting the server, add this Streamable HTTP endpoint in Codex's MCP settings:

```text
http://127.0.0.1:8283/db-mcp
```

The server listens on `127.0.0.1` by default; see [Remote deployment](#remote-deployment) to run it on a server. This version provides twelve tools:

| Tool | Purpose |
| --- | --- |
| `db_list_tables` | List tables and views in the configured database (at most 500) |
| `db_describe_table` | Inspect a table's columns and primary key, optionally selecting a schema |
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

Unit tests load all five real JDBC drivers and check URL acceptance, Spring adapter registration, schema/name handling, per-dialect read behavior, parameter binding, row limits, generated-key policy, and SQL guards without connecting to a database. They do not establish compatibility with a live server.

After starting the server, use Codex's `/mcp` command to inspect the tool list, then try `db_list_tables`, `db_describe_table`, `db_query`, and the Redis tools with a least-privilege account. For each target database, verify a parameterized SELECT, metadata in the expected schema, and permitted DML/DDL in a disposable test schema. Use `SELECT 1 FROM dual` for Oracle and `SELECT 1` for the other engines. Real data access requires reachable services and credentials.
