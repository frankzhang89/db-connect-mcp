# db-connect-mcp

A self-hostable MCP server with one current JDBC database and one Redis instance, providing read and write tools over stdio or Streamable HTTP. Supported JDBC databases are MySQL, Oracle, Microsoft SQL Server, PostgreSQL, and StarRocks. HTTP clients authenticate with a shared Bearer token. Clients can switch the process-wide JDBC data source without restarting. Startup database and Redis credentials stay on the server; switching requires supplying the new database credentials through a tool call.

## Requirements

- JDK 25 and Maven 3.8.x or newer
- Spring Boot 4.0.8 and Spring AI 2.0.1
- A reachable supported JDBC database and Redis instance with least-privilege accounts (SQL reads and writes share one account)

## Configuration

### Select the transport

One JAR supports two mutually exclusive transports. Select the mode in `application.yml` and restart:

```yaml
spring:
  ai:
    mcp:
      server:
        stdio: false # false (default): Streamable HTTP; true: stdio
        protocol: STREAMABLE
        type: SYNC
```

| Mode | Startup | HTTP listener | Bearer authentication | Application logs |
| --- | --- | --- | --- | --- |
| Streamable HTTP (`stdio: false` or omitted) | Existing start/stop/restart scripts | Configured address and port | Existing `dbmcp.auth` settings | Existing stdout behavior |
| stdio (`stdio: true`) | Client launches `java -jar <absolute-jar-path>` directly | None | Skipped automatically, including token validation | stderr only |

Keep `protocol: STREAMABLE` in both modes; do not set `protocol: STDIO`. No Spring profile is needed. Standard Spring configuration overrides such as `--spring.ai.mcp.server.stdio=true` also work. In stdio mode the application enforces a non-web context, its stderr-only Logback configuration, and no banner, even if conflicting web/logging/banner options are supplied. `logging.level.*` remains configurable. Do not print ordinary messages to stdout or merge stderr into stdout with `2>&1`.

Stdio does not use the launch scripts: those background the process and redirect its communication streams. The MCP client owns the child process and its pipes. A completed tool call leaves it running; closing stdin triggers application shutdown, rollback of retained transactions, and pool cleanup. Abrupt process termination cannot guarantee application cleanup. Each independently launched process has its own connection pools, selected data source, Redis selection, and transactions. Database and Redis credentials remain necessary in both modes; startup still validates the initial JDBC connection.

Set the initial connection under `dbmcp.database`, Redis settings under `spring.data.redis`, and (for HTTP mode) a shared token under `dbmcp.auth` in [`src/main/resources/application.yml`](src/main/resources/application.yml). Do not commit this file publicly after adding real credentials. HTTP clients need the MCP endpoint and token; stdio clients need the Java launch command. Read and write tools share the current database account. The default Redis database comes from `spring.data.redis.database`; `redis_select_db` switches its logical database at runtime. Redis tool limits (`dbmcp.redis.max-items`, `dbmcp.redis.max-value-chars`, `dbmcp.redis.max-scan-count`, `dbmcp.redis.max-databases`) are optional properties with sensible defaults.

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

One server process has **one current global JDBC data source**, shared by every MCP client. Old pools exist temporarily while operations or explicit transactions acquired before a switch finish. Database selection is global; `db_execute` separately isolates transaction handles within their owning MCP sessions and pins each transaction to its original target. There is no `expectedDatasourceId` parameter. Use separate server processes if clients need independent default database targets.

`application.yml` is packaged inside the JAR as the default configuration. Deployment bundles also provide an external copy next to the JAR and launchers; edit that copy and restart to override the packaged defaults without rebuilding. Both launchers start Java in their own directory so Spring Boot finds this external file. Use `db_switch_datasource` for a runtime database change. Runtime switches never modify YAML or other configuration files, and restart restores the configured YAML connection.

Credentials and tokens in `application.yml` are readable by processes with access to the project or JAR. They are not included in tool results or application diagnostic objects. Database credentials supplied to the switch tool may be retained in the MCP client's tool history; the server cannot control client-side recording.

## Automated GitHub Releases

The [release workflow](.github/workflows/release.yml) runs when a tag starting with `v` is pushed. It installs Temurin JDK 25, checks that the tag matches the non-SNAPSHOT version in `pom.xml`, runs `mvn clean verify` (including tests), and validates that the result is a Spring Boot executable JAR with bundled dependencies. Only a successful build creates a GitHub Release with automatically generated release notes and these assets:

- `db-mcp-<version>.zip` and `db-mcp-<version>.tar.gz` (deployment bundles)
- `SHA256SUMS` (SHA-256 checksums for both archives)

Each deployment bundle includes the executable JAR from `target/db-mcp/db-mcp.jar`, excluding `.jar.original`. No standalone JAR is uploaded to the Release.

Both archives contain the same directory layout. The JAR inside keeps the stable name expected by the launchers:

```text
db-mcp-0.1.0/
  db-mcp.jar
  db-mcp
  db-mcp.cmd
  db-mcp.ps1
  application.yml
  LICENSE
  README.md
```

For deployment, extract either archive and edit its `application.yml` for your database, Redis and HTTP authentication. On Windows run `.\db-mcp.cmd start` from the extracted directory; on Linux/macOS run `sh ./db-mcp start`. Use `stop` or `restart` for the other actions. Both launchers set the Java process working directory to the script directory, so Spring Boot loads the adjacent `application.yml` and uses it to override the configuration bundled in the JAR, including when the launcher is invoked from another directory. The `.tar.gz` archive also preserves the Unix launcher's executable permission. JDK 25 or newer is required.

Commit and push the workflow before publishing the first version. For the current `pom.xml` version `0.1.0`, run:

```powershell
git add .github/workflows/release.yml README.md
git commit -m "Add automatic runnable JAR releases"
git push origin main
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin v0.1.0
```

Watch the **Actions → Release runnable JAR** run, then download the JAR from [Releases](https://github.com/frankzhang89/db-connect-mcp/releases). GitHub Actions must be enabled for the repository and its policies must allow the workflow's `contents: write` permission. The workflow uses the automatically supplied `GITHUB_TOKEN`; no personal token or custom secret is needed. If a run fails before creating the Release, fix the cause and use **Re-run failed jobs** for a transient failure, or commit a source fix and publish a new version/tag. An existing Release is not overwritten.

For later releases, update `pom.xml` and the MCP server version in `src/main/resources/application.yml`, commit and push, then push the corresponding new tag (for example, `v0.1.1`). A version such as `0.2.0-rc.1` with tag `v0.2.0-rc.1` is published as a prerelease. Pushing ordinary commits or changing a version file alone does not publish a Release.

Run the downloaded JAR with JDK 25 or newer, supplying your deployment configuration:

```powershell
java -jar db-mcp.jar --spring.config.additional-location=file:./application.yml
```

The JAR and deployment bundles include the repository's `src/main/resources/application.yml`; keep its values suitable for public distribution and supply real deployment settings through the external configuration file. The application still requires a reachable database and appropriate Redis/authentication settings as described above.

## Shared Bearer authentication

In Streamable HTTP mode, authentication defaults to enabled even when `dbmcp.auth.enabled` is omitted. Set `dbmcp.auth.token` directly in server YAML to a randomly generated long token (for example, 32 random bytes encoded as 64 hexadecimal characters). This configuration does not depend on an environment variable. The example `replace-with-a-random-long-token` is deliberately rejected; replace it before starting HTTP mode. A missing, blank, placeholder, or malformed token fails HTTP startup. Changing the token requires restarting the server and updating clients. Stdio automatically skips HTTP authentication and token validation; no extra `dbmcp.auth.enabled=false` setting is needed. Existing auth settings apply again when switching back to HTTP.

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

The scripts below always launch Streamable HTTP mode by passing `--spring.ai.mcp.server.stdio=false`, overriding the YAML setting. This applies to Windows (`db-mcp.cmd` delegates to `db-mcp.ps1`) and Linux/macOS (`db-mcp`), for both `start` and `restart`. For stdio, agents launch the same JAR directly with `--spring.ai.mcp.server.stdio=true`, as described under [Connect Codex](#connect-codex). The two transports can run simultaneously in separate processes, each with independent connection pools and runtime selections.

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

Double-clicking `db-mcp.cmd`, or running it without arguments in an interactive terminal, opens a menu with `stop`, `restart`, and `start`. Use the Up/Down arrow keys to cycle through the actions and Enter to execute; `restart` is initially highlighted. The menu counts down for five seconds. Moving the highlight does not reset the countdown: without Enter, the launcher executes `restart`, even if another action is highlighted. Explicit action arguments bypass the menu. Redirected/non-interactive Windows runs without an action immediately default to `restart`. The launcher prints the final action before executing it.

Both launchers wait for Spring Boot startup to complete and report the service's actual listening port. On an interactive terminal, they keep the result visible for five seconds, or until any key is pressed; redirected/non-interactive runs skip this pause. Closing this prompt leaves the background service running.

Startup waits up to 60 seconds; override this with `DB_MCP_STARTUP_TIMEOUT_SEC` (a positive integer). A timeout leaves the recorded process available for `stop` or `restart`.

The JDK environment variable name is configurable near the top of each launcher: `$jdkHomeEnvName = 'JDK25'` in `db-mcp.ps1`, and `JDK_HOME_ENV=JDK25` in `db-mcp`. Set that environment variable to the JDK home directory (not its `bin` directory). If the name is empty or the named variable is unset/empty, the scripts use `JAVA_HOME`; if neither home is set, they use Java from `PATH`. A configured home with a missing Java executable produces an error instead of silently selecting another JDK. Startup prints the selected Java executable. `restart` validates the Java selection before stopping the running service.

If startup fails because a port is already in use, the launcher reports the conflicting port, owner PID, executable, and program directory. For Java processes launched with `-jar`, it also reports the application JAR and uses its directory when resolvable. Linux diagnostics use `ss` (iproute2), `lsof`, or `fuser`; process details can be unavailable when permissions prevent inspection or the owner has already exited. The scripts do not terminate the conflicting process.

For deployment, put `db-mcp.jar` alongside the startup scripts. `start` and `restart` default to that sibling JAR, regardless of the caller's working directory or the previously started JAR. The optional second argument can specify a different JAR file or directory; `DB_MCP_JAR_PATH` and `DB_MCP_EXPORT_PATH` also override the default. `DB_MCP_JAR_NAME` can change the expected filename. The recorded JAR path is used only to identify the managed process when checking or stopping it. The scripts store the process ID and logs in the script directory's `run/` folder.

## Connect Codex

### Configuration file and server names

Codex stores user-level MCP entries in `~/.codex/config.toml` (on Windows, normally `%USERPROFILE%\.codex\config.toml`). Trusted projects can also use a project-local `.codex/config.toml`. See the [official MCP configuration documentation](https://developers.openai.com/codex/mcp).

In `[mcp_servers.db_connect]`, `db_connect` is a custom registration name, not a Java class, tool name, or Spring application name. Choose a distinct name for each server, such as `db_local` and `db_remote`, and avoid registering the same connection again through the MCP settings UI. A plugin's display name is not necessarily its MCP registration name; use distinct server names when configuring separate connections.

After saving, reload MCP connections or restart the client, and confirm the server is connected and its tools are available. In Codex CLI, `/mcp` shows active servers. Then request an operation explicitly, for example: "Use db_connect's db_list_tables tool to list tables in the current database." Mentioning a server name does not install, configure, or connect it.

### Stdio

Set `spring.ai.mcp.server.stdio: true` in the server YAML, rebuild if editing the packaged YAML, and add a stdio entry to Codex's `config.toml`. Use the actual absolute JAR path (the example uses the default build output):

```toml
[mcp_servers.db_connect]
command = "java"
args = ["-jar", "D:/work/Repositories/gitRepositories/mcp/db-connect-mcp/target/db-mcp/db-mcp.jar"]
cwd = "D:/work/Repositories/gitRepositories/mcp/db-connect-mcp"
startup_timeout_sec = 60
tool_timeout_sec = 60
```

Use an absolute JDK 25 `java.exe` path for `command` if `java` on PATH resolves to another version. Configure either this command entry or an HTTP URL entry for the server. Do not manually start a background instance first; Codex starts its own process. Reload the client's MCP configuration and inspect its tools. No URL, port, Bearer header, or Spring profile is needed. Keep the startup timeout long enough for JDBC validation. External Spring YAML files can be supplied using `--spring.config.location=file:/...` in `args`.

If the packaged YAML still has `stdio: false`, explicitly override it in the client launch arguments instead:

```toml
args = [
  "-jar",
  "D:/work/Repositories/gitRepositories/mcp/db-connect-mcp/target/db-mcp/db-mcp.jar",
  "--spring.ai.mcp.server.stdio=true"
]
```

Do not use `--spring.profiles.active=stdio`: this project selects the transport with the `stdio` property and does not provide a `stdio` profile. Use the freshly built `target/db-mcp/db-mcp.jar`, not a stale `target/db-mcp.jar` from an earlier build layout.

### Select JDK 25 on Windows

The simplest way to select a particular installed JDK is an absolute executable path. TOML literal strings (single quotes) preserve Windows backslashes without escaping:

```toml
command = 'D:\Program Files\Java\jdk-25.0.1\bin\java.exe'
```

Keep the other stdio fields unchanged. This selects that JDK regardless of `JAVA_HOME` or other JDK variables. A bare `command = "java"` instead uses the executable found on the client's PATH.

To resolve an existing environment variable named `JDK25` at launch time, use PowerShell explicitly. `JDK25` must point to the JDK root directory, not `bin` or `java.exe`. Replace the entire server entry with this alternative; do not append a second `[mcp_servers.db_connect]` table:

```toml
[mcp_servers.db_connect]
command = "powershell.exe"
env_vars = ["JDK25"]
args = [
  "-NoLogo",
  "-NoProfile",
  "-NonInteractive",
  "-Command",
  '& "$env:JDK25\bin\java.exe" -jar "D:/work/Repositories/gitRepositories/mcp/db-connect-mcp/target/db-mcp/db-mcp.jar" --spring.ai.mcp.server.stdio=true; exit $LASTEXITCODE'
]
cwd = "D:/work/Repositories/gitRepositories/mcp/db-connect-mcp"
startup_timeout_sec = 60
tool_timeout_sec = 60
```

`env_vars` forwards `JDK25` from the Codex process environment; PowerShell expands `$env:JDK25` and the quotes handle spaces in its path. Do not put `%JDK25%` or `$env:JDK25` directly in `command` and expect shell expansion. After adding or changing a Windows environment variable, fully quit and reopen Codex so it inherits the updated value. If the variable is absent, fix it before launching; this configuration does not fall back to a different JDK. Do not add output banners, logging, background startup, or `2>&1` to the launch command.

### Debug stdio manually

To control tool parameters manually, run the official MCP Inspector instead of an agent:

```powershell
npx @modelcontextprotocol/inspector java -jar "D:/work/Repositories/gitRepositories/mcp/db-connect-mcp/target/db-mcp/db-mcp.jar"
```

Use the Inspector's stdio connection and tools view to list tools, fill arguments, and inspect results. Keep the same connection for a transaction: call `db_execute` with `sql: BEGIN`, then pass the returned `transactionId` on subsequent statements and COMMIT/ROLLBACK. A new connection/process cannot reuse an old handle. Inspector's own local web UI may listen on ports; the MCP server still communicates over stdio.

### Streamable HTTP

With `stdio: false`, start the server using the existing scripts, then configure this Streamable HTTP endpoint and the Bearer request header in your MCP client:

```text
http://127.0.0.1:8283/db-mcp
```

The listening interface comes from `server.address`; see [Remote deployment](#remote-deployment). Both modes expose these fourteen tools:

| Tool | Purpose |
| --- | --- |
| `db_list_tables` | List tables and views with comments in the configured database (at most 500) |
| `db_describe_table` | Inspect table/column comments, columns, defaults, auto-increment/identity flags, primary key, indexes, foreign keys, and unique constraints; optional schema override |
| `db_query` | Run one parameterized, read-only `SELECT` query |
| `db_execute` | Agent-controlled transaction: `BEGIN`, query/DML/TRUNCATE scripts, then explicit `COMMIT` or `ROLLBACK`; arguments are `sql` and `transactionId?` |
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

### Metadata response contract

`db_list_tables.tables[]` adds `comment` (string or null) alongside `name`, `type`, and optional `catalog`/`schema`.
`db_describe_table` keeps its existing fields and adds:

| Field | Meaning |
| --- | --- |
| `comment` | Table or view comment; SQL Server uses the `MS_Description` extended property |
| `columns[].comment` | Column comment |
| `columns[].defaultValue` | Default value/expression as reported by JDBC, without evaluating it or reconstructing DDL; quoting varies by database |
| `columns[].autoIncrement` | JDBC auto-increment flag, including MySQL `AUTO_INCREMENT`; unknown flags return null |
| `columns[].identity` | Identity column flag for Oracle, SQL Server, and PostgreSQL; MySQL returns false; unknown/not supported returns null. PostgreSQL `serial` is not an Identity column |
| `indexes[]` | `{name, unique, type, columns: [{name, expression, sortDirection}]}` |
| `foreignKeys[]` | `{name, columns, referencedCatalog, referencedSchema, referencedTable, referencedColumns, updateRule, deleteRule}` |
| `uniqueConstraints[]` | `{name, columns}` for actual UNIQUE constraints; excludes primary keys and standalone unique indexes where the database distinguishes them |

Composite key/index columns retain their declared order. `sortDirection` is `ASC`, `DESC`, or null; expression index entries have `name: null` and an `expression`. Index types retain database-native names. Index column lists describe key columns, excluding PostgreSQL/SQL Server INCLUDE columns; SQL Server columnstore/XML/spatial indexes expose their indexed columns without a key sort direction. Filter predicates, prefix lengths, and index options are not returned. MySQL represents UNIQUE declarations as unique indexes/constraints, so these can appear in both lists. Foreign key rules are `CASCADE`, `RESTRICT`, `SET_NULL`, `NO_ACTION`, `SET_DEFAULT`, or null.

An empty collection means discovery succeeded and found no objects; null means unsupported or unknown. Missing comments/defaults are null (or an empty comment if reported that way by the driver). Catalog permission/query failures are reported as errors, not as an empty collection. MySQL table comments are read from `information_schema.tables`, scoped to the connection's catalog, because JDBC table remarks can be empty even when comments exist. Oracle comments and Identity flags are read from `ALL_*` views, without changing connection remark settings. Oracle sequence/default-sequence/trigger associations are not inspected and no `sequence` field is returned.

StarRocks continues to expose table/column comments and defaults supplied by its native driver; `identity` is null. Its `indexes`, `foreignKeys`, and `uniqueConstraints` are null because the corresponding [information schema views are placeholders](https://docs.starrocks.io/docs/sql-reference/information_schema/). This version does not parse `SHOW CREATE TABLE` or optimizer-only constraint properties to infer that metadata.

## Remote deployment

Set `server.address` to `127.0.0.1` for local-only access. To run it on a remote server:

1. Bind a reachable interface: set the environment variable `SERVER_ADDRESS=0.0.0.0` (all interfaces) or the server's private IP, and optionally `SERVER_PORT`. These override `server.address` and `server.port` in `application.yml`. Open the port in the firewall; the MCP endpoint is then `http://<server-host>:8283/db-mcp`.
2. Configure `dbmcp.database` and `spring.data.redis` with addresses reachable from the server, rebuild, and restart.
3. Keep Bearer authentication enabled and use HTTPS through a TLS reverse proxy for remote access. The token grants access to all write and data-source-switch tools, so restrict network access as appropriate. Startup credentials stay on the server; clients supply new JDBC credentials only when using the switch tool.

## Agent-controlled transactions: `db_execute`

Use this tool when an agent must change data, query its uncommitted changes, and decide whether to keep or undo them. It has only two input fields: required `sql` and optional `transactionId`. The server obtains session ownership from the MCP exchange; callers do not provide a session field. Existing `db_query`, `db_execute_dml`, and `db_execute_ddl` remain independent and never join these transactions.

1. Call `{"sql":"BEGIN"}` without an ID. `START TRANSACTION` and `BEGIN TRANSACTION` are also accepted. The server returns a generated `transactionId`, `datasourceId`, database type, `state: "ACTIVE"`, `isolation: "READ_COMMITTED"`, timeout limits, and `truncateRollbackSupported`.
2. Include that ID on every subsequent call. Queries, DML, and mixed semicolon-separated scripts run on the same retained JDBC connection with auto-commit disabled. Queries see that transaction's own writes.
3. Inspect results and send more SQL as needed. Call `{"sql":"COMMIT","transactionId":"tx-..."}` only after verification, or `{"sql":"ROLLBACK","transactionId":"tx-..."}` to discard the remaining uncommitted work. Control commands must be standalone calls.

For example, on a schema containing `account(id, balance)`:

```json
{"sql":"BEGIN"}
```

Save the returned ID, then use it in place of `tx-...`:

```json
{"transactionId":"tx-...","sql":"SELECT id, balance FROM account WHERE id IN (1,2) FOR UPDATE"}
{"transactionId":"tx-...","sql":"UPDATE account SET balance=balance-100 WHERE id=1; UPDATE account SET balance=balance+100 WHERE id=2; SELECT id,balance FROM account WHERE id IN (1,2)"}
{"transactionId":"tx-...","sql":"ROLLBACK"}
```

Use the target engine's locking syntax; `FOR UPDATE` is not SQL Server syntax. Choose `COMMIT` instead of `ROLLBACK` when the checks pass. Ordinary SQL without an ID is rejected rather than silently auto-committed. Ended, unknown, or expired IDs never create replacement transactions. No parameter array is exposed: write trusted SQL literals with correct escaping, and never concatenate untrusted user text. The server preserves statement text, removing outer separators; it adds the terminator required by SQL Server MERGE.

**SQL scope.** The server validates the complete script before executing its first statement. It accepts parser-supported queries/CTEs, INSERT/UPDATE/DELETE/MERGE/UPSERT, locking reads, supported RETURNING/OUTPUT row sets, and **TRUNCATE**. Other DDL, SELECT INTO, CALL/EXEC, anonymous procedure blocks, dynamic SQL, session configuration, and embedded transaction controls are rejected. Savepoints and nested transactions are not implemented. Comments outside quoted literals are rejected, including executable MySQL comments. Semicolons inside supported quoted literals/identifiers do not split statements. Parser acceptance does not imply that the target engine accepts the syntax; vendor constructs outside the parser's supported grammar are rejected. Triggers, existing functions, external effects, sequence changes, and nontransactional storage are governed by database permissions and engine semantics, not made reversible by this tool.

**TRUNCATE is an explicit exception to the DDL ban.** On [MySQL](https://dev.mysql.com/doc/refman/8.4/en/truncate-table.html) and [Oracle](https://docs.oracle.com/en/database/oracle/oracle-database/21/sqlrf/TRUNCATE-TABLE.html), it cannot be rolled back and may implicitly commit earlier work, including before a failing TRUNCATE attempt. After an attempt on these engines, responses retain `implicitCommitPossible: true`, `rollbackScope: "SINCE_LAST_IMPLICIT_COMMIT"`, and a warning through transaction completion. The handle stays usable for later statements, but no longer represents one atomic unit from BEGIN. `ROLLBACK` cannot undo work already committed. Use DELETE instead when full data rollback is required. [PostgreSQL](https://www.postgresql.org/docs/current/sql-truncate.html) and [SQL Server](https://learn.microsoft.com/en-us/sql/t-sql/statements/truncate-table-transact-sql?view=sql-server-ver17) support transactional TRUNCATE subject to engine restrictions. This exception is independent of `dbmcp.write.allow-ddl`, which controls only the old DDL tool.

**Results and failures.** Each success returns transaction identity/state and an ordered `results` array. Entries identify their zero-based `statementIndex` and `resultIndex`, then contain either query columns/rows/truncation or an affected-row count. Row sets from RETURNING/OUTPUT use the query format. Counts are driver-reported; TRUNCATE does not promise a deleted-row count, and generated keys are not requested automatically. Query results use `dbmcp.query.max-rows` and `max-cell-chars`. The row limit does not limit how many rows DML modifies. A script timeout supplements the per-statement query timeout; drivers can exceed cancellation deadlines.

Failures set MCP `isError: true` and return a JSON body with `success: false`, a safe `code` and message, and transaction state when available. SQL execution failures also return the zero-based failing statement index, completed results, and a sanitized SQLState when available. A validation failure runs no SQL and leaves the transaction usable. An execution failure stops the script and sets `ROLLBACK_ONLY`; only explicit ROLLBACK is accepted afterward. No automatic retry or commit occurs. Failed commit/rollback acknowledgement returns `state: "UNKNOWN"` with `COMMIT_OUTCOME_UNKNOWN` or `ROLLBACK_OUTCOME_UNKNOWN`, invalidates the handle, and discards its connection. Verify database state independently; retrying writes could duplicate a committed change. Lost tool responses, including a lost BEGIN response, must not be blindly retried.

**Isolation and lifetime.** Different transaction IDs retain different connections even within one MCP session. The same ID is serialized, belongs to its creating session, and remains pinned across global data source switches. Agents sharing both an ID and session share the transaction; an ID is not a separate authenticated agent identity. READ COMMITTED does not make a multi-query snapshot stable. Other sessions must avoid dirty reads to exclude uncommitted data; writes and locking queries can block other sessions before commit. Explicit MCP session DELETE, idle/lifetime expiry, and service shutdown attempt rollback and release resources. Ending a POST or disconnecting an SSE stream does not end a transaction. Process crashes cannot provide a cleanup acknowledgement. Handles are memory-only and do not survive restart. StarRocks is rejected at BEGIN rather than silently using auto-commit; other targets must report JDBC transaction support. Use transactional tables (for example InnoDB on MySQL) and avoid indirect nontransactional effects.

Optional limits, with defaults:

```yaml
dbmcp:
  transaction:
    idle-timeout-seconds: 120
    max-lifetime-seconds: 600
    max-active: 5
    max-statements: 20
    max-sql-chars: 20000
    script-timeout-seconds: 30
```

The active limit is global, including retired data sources and transactions being opened. Each target also caps retained transactions at `max(1, maximumPoolSize - 1)` to leave one connection for independent tools when possible. A one-connection pool can hold one transaction, which occupies its entire capacity. Calls sharing a busy transaction wait at most the query timeout before `TRANSACTION_BUSY`; lock waiting counts toward idle/lifetime checks after acquisition. The cleanup task skips executing transactions and checks them again afterward; query timeouts are driver-enforced rather than a hard kill guarantee. Keep transactions short while an agent examines results. See [skill.md](skill.md#agent-controlled-transactions-db_execute) for the detailed agent workflow and recovery table.

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

Tests load all five real JDBC drivers and check URL acceptance, schema/name handling, read/write behavior, row limits, and SQL guards. Additional tests cover switch publication, concurrent leases, retirement, timeout/late completion, shutdown, authentication defaults, HTTP methods, and redaction. HTTP integration tests use both an in-process JDBC test double and a test-only H2 database. Transaction tests verify own-write visibility, isolation between connections, commit/rollback, script rejection and failure, original-target retention, expiry, session ownership/deletion, capacity recovery, result limits, implicit-commit warnings, and unknown commit outcomes. H2 is test-scoped and is not a supported production target. These tests do not establish compatibility with live MySQL, Oracle, SQL Server, PostgreSQL, or StarRocks servers.

Stdio integration tests launch separate JVMs with real stdin/stdout pipes and a test-only H2 adapter. They verify tool discovery and parameter binding, hidden session context, commit/rollback, HTTP auth bypass, startup with an occupied HTTP port, protocol-only stdout, stderr diagnostics, and process exit/pool cleanup on EOF. An independent JDBC connection checks rollback after application shutdown. Additional checks cover EOF before initialization, input read failure, repeated shutdown signals, and failed database startup.

After starting the server, use Codex's `/mcp` command to inspect the tool list, then try `db_list_tables`, `db_describe_table`, `db_query`, and the Redis tools with a least-privilege account. For each target database, verify a parameterized SELECT, metadata in the expected schema, and permitted DML/DDL in a disposable test schema. Use `SELECT 1 FROM dual` for Oracle and `SELECT 1` for the other engines. Real data access requires reachable services and credentials.
