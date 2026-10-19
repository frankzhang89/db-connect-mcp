# db-connect-mcp — Agent Skill

An MCP server that gives an agent read and write access to **one current JDBC database** (MySQL, Oracle, Microsoft SQL Server, PostgreSQL, or StarRocks) and **one Redis instance**, through 14 tools. The server holds all database and Redis credentials; the agent needs only the MCP endpoint and the shared Bearer token. The JDBC target can be replaced at runtime without restarting the server. `db_execute` adds explicit, session-owned transactions for agents that must inspect their changes before committing.

See [README.md](README.md) for deployment, configuration, and dialect details.

## Connect

- Endpoint: `http://<host>:8283/db-mcp` (Streamable HTTP).
- Send `Authorization: Bearer <token>` on **every** request, including `initialize`, `tools/list`, and every tool call. A session ID or URL query parameter never substitutes for the header. Missing or invalid credentials return HTTP `401` with `WWW-Authenticate: Bearer`, before any tool runs.
- There are no per-user roles: the token grants access to **all** tools, including writes and data-source switching.
- One server process has **one global JDBC target** and **one global Redis database**, shared by every client. `db_switch_datasource` and `redis_select_db` are not per-session.

## Tool catalog

### Database — read

| Tool | Parameters | Behavior |
| --- | --- | --- |
| `db_list_tables` | — | Lists tables and views in the configured schema, at most 500; includes catalog/schema when the driver reports them. |
| `db_describe_table` | `tableName`, `schemaName?` | Column names, types, sizes, nullability, and primary key. `schemaName` overrides metadata discovery for this call only. |
| `db_query` | `sql`, `parameters?`, `maxRows?` | One read-only `SELECT` (`WITH ... SELECT` allowed). Binds values via `?` placeholders. At most 100 rows, 10 s timeout, 4000 chars per cell (longer values are truncated and flagged). |

Database tools return the `datasourceId` they actually used. The ID is diagnostic only; it does not pin later calls to that database.

### Database — write

| Tool | Parameters | Behavior |
| --- | --- | --- |
| `db_execute_dml` | `sql`, `parameters?` | One `INSERT` / `UPDATE` / `DELETE` / `MERGE` / `UPSERT`. Returns `affectedRows`; `INSERT` on MySQL and SQL Server also returns up to 50 `generatedKeys`. |
| `db_execute_ddl` | `sql` | One `CREATE` / `ALTER` / `DROP` / `TRUNCATE` / `RENAME`. Destructive; may be disabled server-side (`dbmcp.write.allow-ddl=false`). |
| `db_execute` | `sql`, `transactionId?` | Explicit transaction: BEGIN returns an ID; queries/DML/TRUNCATE scripts and COMMIT/ROLLBACK require that ID. Detailed workflow below. |

### Connection

| Tool | Parameters | Behavior |
| --- | --- | --- |
| `db_switch_datasource` | `jdbcUrl`, `username`, `password`, `schema?` | Validates a candidate connection, then switches the JDBC target for **all** clients. Memory-only; restart restores the YAML target. Redis is unaffected. |

### Redis — read (all operate on the currently selected database)

| Tool | Parameters | Behavior |
| --- | --- | --- |
| `redis_scan_keys` | `pattern?`, `cursor?`, `count?` | Cursor-based `SCAN` (never `KEYS`). Start with `cursor: "0"`, then feed back `nextCursor` until it is `"0"` again. |
| `redis_inspect_key` | `key` | Type, TTL, and size; returns `exists: false` for missing keys. |
| `redis_read_key` | `key`, `maxItems?` | Reads up to 100 elements from a string / hash / list / set / sorted set. |

### Redis — write

| Tool | Parameters | Behavior |
| --- | --- | --- |
| `redis_write_key` | `key`, `type`, `value?` / `values?` / `fields?` / `scoreMembers?`, `ttlSeconds?` | Writes one key. `type`: `string` (uses `value`), `list`/`set` (uses `values`), `hash` (uses `fields`), `zset` (uses `scoreMembers`). Lists append to the tail, hashes merge incrementally, strings replace, zsets upsert scores. |
| `redis_delete_keys` | `keys` | Deletes up to 100 keys, returns the removed count. |
| `redis_expire_key` | `key`, `ttlSeconds` | Sets a TTL; `ttlSeconds: 0` removes the expiry (PERSIST). |
| `redis_select_db` | `db` | Switches the current database (0–15 by default) for **all** subsequent Redis tools, reads and writes alike. |

## Hard constraints

**Independent SQL tools (`db_query`, `db_execute_dml`, `db_execute_ddl`)**

- Exactly one statement per call, at most 20000 characters.
- No comments of any kind (`--`, `/* */`, `#`) and no embedded semicolons. Write tools accept exactly **one trailing `;`** (SQL Server `MERGE` requires it); `db_query` accepts none.
- Bind values with `?` placeholders and the `parameters` array when using `db_query` or `db_execute_dml`; never concatenate untrusted data into SQL text. Parameters may only be strings, numbers, booleans, or `null`. `db_execute_ddl` and the new `db_execute` have no parameter-array field.
- `SELECT` must go to `db_query` only; `db_execute_dml` / `db_execute_ddl` reject it. `db_query` rejects DML/DDL.
- `db_query` also rejects `SELECT INTO`, write-bearing CTEs (`WITH ... DELETE`), locking clauses (`FOR UPDATE`, `FOR SHARE`, `FOR NO KEY UPDATE`, `FOR KEY SHARE`, `LOCK IN SHARE MODE`, `WITH (UPDLOCK)`, `WITH (XLOCK)`), and side-effect or sequence functions (`SLEEP`, `BENCHMARK`, `LOAD_FILE`, `GET_LOCK`, `pg_sleep`, `pg_advisory_*`, `nextval`, `setval`, `NEXTVAL`, `NEXT VALUE FOR`, `dblink_exec`, `lo_import`, `lo_export`).
- `db_execute_dml` rejects `RETURNING` and `OUTPUT` clauses, `LOAD DATA`, `INTO OUTFILE`, server-side `PREPARE`, and `HANDLER`.
- SQL is sent to the target database **verbatim**: there is no translation between MySQL, Oracle, T-SQL, PostgreSQL, and StarRocks syntax. Use the syntax of the database you are connected to, and qualify objects explicitly (`schema.table`). T-SQL `[brackets]` and PostgreSQL array subscripts (`arr[1]`) are both accepted.

**Runtime switching**

- `db_switch_datasource` requires `jdbcUrl`, `username`, and `password`; an empty password is valid. Supply the full target every time — omitted `schema` uses the new connection's default, never the previous target's value.
- Only the five supported URL prefixes are accepted. Validation happens against a separate candidate pool while the current database stays usable; a failed or timed-out switch leaves the current target untouched. Repeating the same URL still creates a fresh pool, so changed credentials take effect.
- Operations already in flight and explicit transactions retain their original database; independent calls and new BEGIN calls use the new one. Switches are serialized, with a 15 s total / 5 s validation deadline by default. Calls with an existing transactionId stay on that transaction's original target.
- Failures return a redacted tool error with a code such as `INVALID_ARGUMENT`, `AUTHENTICATION_FAILED`, `CONNECTION_FAILED`, `VALIDATION_FAILED`, `VALIDATION_TIMEOUT`, `SERVICE_CLOSED`, or `SWITCH_INTERRUPTED`. No driver message or credential is returned.
- Supplied database credentials may be recorded in the MCP client's tool history; the server cannot control that.

**Redis**

- Keys are at most 512 characters; each value/member is at most 4000 characters (longer writes are rejected, longer reads are truncated).
- At most 100 elements per read, write, or delete call.
- `SELECT` is global state: after `redis_select_db`, every Redis tool targets the new database. Track which database you are on; results silently come from the current one.
- The TTL set by `redis_write_key` is a separate command after the write, not an atomic transaction: a rejected TTL does not undo data already written. A zset score must be a finite number.

## Agent-controlled transactions: `db_execute`

### Choose the right execution mode

Use `db_execute` for **change data → query the changed data → decide COMMIT or ROLLBACK**. Use the independent tools for standalone reads or immediately committed DML. Do not mix the modes when verifying uncommitted work: `db_query` uses another connection and cannot inspect your transaction's uncommitted changes; `db_execute_dml` commits independently and cannot be undone by rolling back your transaction. Redis operations never join a JDBC transaction.

The tool exposes exactly two fields:

| Field | Meaning |
| --- | --- |
| `sql` | Required. A standalone transaction command, one query/DML/TRUNCATE statement, or a mixed semicolon-separated script. |
| `transactionId` | Omit only for BEGIN. Otherwise pass the exact ID returned by the server; never invent or reuse an ended ID. |

The current MCP session is injected by the server. Keep that session across calls and send the Bearer header every time. IDs are bound to their creating session, not to an agent name. Multiple agents in the same session must each open their own transaction and keep their IDs separate. Sharing an ID within that session intentionally shares one connection and transaction. Calls on one ID are serialized; different IDs may run concurrently, subject to pool capacity and database locks.

### Opening and finishing a transaction

1. Inspect the schema and confirm the intended global data source before opening a transaction.
2. Call `db_execute` with `{"sql":"BEGIN"}` and **omit** `transactionId`. `START TRANSACTION`, `BEGIN WORK`, and `BEGIN TRANSACTION` are accepted aliases. An optional trailing semicolon is accepted.
3. Check `success` and save the returned `transactionId` and `datasourceId`. Opening returns `state: "ACTIVE"`, `isolation: "READ_COMMITTED"`, `truncateRollbackSupported`, `idleTimeoutSeconds`, and `maxLifetimeSeconds`. Unsupported databases, pool exhaustion, and capacity limits can prevent opening.
4. Pass that exact ID on every query, DML, or script. Qualify objects as needed. Inspect affected-row counts and query results; the tool does not decide whether those results satisfy your business intent.
5. If results are correct, send standalone `COMMIT` with the ID. If results are wrong, send standalone `ROLLBACK` with the ID. A business validation mismatch does not poison the transaction: you may issue corrective SQL and query again before deciding.
6. Check the completion response. `COMMITTED` or `ROLLED_BACK` ends and invalidates the handle. Check `implicitCommitPossible` and `rollbackScope` before claiming that every change was rolled back.

Never send COMMIT inside a DML script or use BEGIN with an existing ID. Ordinary SQL without an ID is rejected instead of automatically committed. Nested transactions, savepoints, `ROLLBACK TO`, chained commits, and user-supplied isolation/session-setting commands are not supported. The server chooses READ COMMITTED at BEGIN; it is not a stable snapshot across multiple queries. No automatic business commit occurs.

### Complete example: modify, inspect, and decide

Assume `account(id, balance)` exists and use the actual schema name when needed. This example uses MySQL/PostgreSQL locking syntax; choose the target engine's syntax on Oracle or SQL Server.

```json
{"sql":"BEGIN"}
```

Example opening response (the real ID is generated by the server):

```json
{"success":true,"transactionId":"tx-11111111-1111-4111-8111-111111111111","datasourceId":"ds-example","state":"ACTIVE","isolation":"READ_COMMITTED","truncateRollbackSupported":false,"implicitCommitPossible":false,"rollbackScope":"FULL_TRANSACTION","idleTimeoutSeconds":120,"maxLifetimeSeconds":600}
```

Use the returned ID, not the illustrative one, on all later calls:

```json
{"transactionId":"tx-11111111-1111-4111-8111-111111111111","sql":"SELECT id,balance FROM account WHERE id IN (1,2) FOR UPDATE"}
```

Check that both accounts exist and that the source has sufficient funds. Then:

```json
{"transactionId":"tx-11111111-1111-4111-8111-111111111111","sql":"UPDATE account SET balance=balance-100 WHERE id=1; UPDATE account SET balance=balance+100 WHERE id=2; SELECT id,balance FROM account WHERE id IN (1,2)"}
```

Verify each update affected exactly one row and the balances changed as intended. The SELECT runs on the same connection and sees your uncommitted writes. If correct:

```json
{"transactionId":"tx-11111111-1111-4111-8111-111111111111","sql":"COMMIT"}
```

If any check is wrong, use this **instead**:

```json
{"transactionId":"tx-11111111-1111-4111-8111-111111111111","sql":"ROLLBACK"}
```

Do not issue both commands as a normal sequence: the first successful completion ends the transaction. If a tool response is lost, do not blindly repeat a mutation, BEGIN, or COMMIT. Verify state independently; an HTTP/client timeout does not prove the statement was cancelled.

### Accepted SQL and scripts

- Queries and CTEs, including supported data-modifying CTEs; INSERT, UPDATE, DELETE, MERGE, UPSERT; locking reads; parser-supported RETURNING/OUTPUT; and **TRUNCATE** are allowed.
- Other DDL is rejected, including CREATE, ALTER, DROP, and RENAME. SELECT INTO creating a table is rejected, including inside nested queries. CALL/EXEC, anonymous procedure blocks, server-side PREPARE/dynamic SQL, transaction/session setting commands, and external SQL execution helpers are not accepted.
- The complete script is parsed and validated before the first statement executes. If a late statement is forbidden, none of that call's earlier statements execute. This does not undo earlier successful calls in the transaction.
- A valid script executes sequentially on the retained connection. An execution error stops all remaining statements and marks the transaction `ROLLBACK_ONLY`. Earlier statements may have modified data; explicitly roll back.
- Comments outside quoted literals are rejected. Semicolons inside supported quoted strings or identifiers do not separate statements. One trailing separator is accepted; empty statements are rejected. The original statement text is retained for execution, apart from outer separators and SQL Server MERGE's required terminator.
- This tool has **no `parameters` array** and no client-supplied `maxRows`. Use trusted SQL literals, escape values according to the target dialect, and never interpolate untrusted text. `?` placeholders cannot be bound here; choose an independent parameterized tool when a shared transaction is unnecessary.
- Use target-native syntax; there is no cross-database SQL translation. JSqlParser may reject some valid vendor syntax, including unsupported OUTPUT/TRUNCATE variants. Do not bypass rejection through stored procedures or dynamic SQL. Existing functions/triggers can have indirect or external effects, and sequence increments may not roll back; SQL filtering is not a sandbox for arbitrary database code.

### TRUNCATE: permitted, with database-specific rollback behavior

`TRUNCATE` is allowed by this tool even though other DDL is blocked. This exception is independent of the old `dbmcp.write.allow-ddl` setting.

| Target | Behavior relevant to this tool |
| --- | --- |
| MySQL | TRUNCATE causes an implicit commit and cannot be rolled back. Earlier DML can become permanent before your explicit COMMIT. |
| Oracle | TRUNCATE cannot be rolled back; implicit DDL commits can make prior DML permanent, including when the truncate attempt fails. |
| PostgreSQL | TRUNCATE is transactional for table data, but takes strong locks and has engine-specific concurrency behavior. |
| SQL Server | TRUNCATE can be rolled back within a supported transaction; engine/product restrictions still apply. |
| StarRocks | Explicit cross-call transactions are currently rejected at BEGIN. Use independent tools only with their documented semantics. |

Before truncating, inspect `databaseType` and `truncateRollbackSupported`. On MySQL/Oracle, use DELETE if you need the ability to undo all changes. If you choose TRUNCATE, understand that it breaks atomicity from BEGIN. After an attempt, the handle stays usable, but responses keep:

```json
{"implicitCommitPossible":true,"rollbackScope":"SINCE_LAST_IMPLICIT_COMMIT","warnings":["TRUNCATE on this database may implicitly commit earlier writes and cannot be rolled back. ROLLBACK only undoes work after the last implicit commit."]}
```

The flag is conservative: it is set before execution, because a failing attempt can already have committed earlier work. A later `ROLLED_BACK` response means the remaining transaction was rolled back; it never restores already committed or truncated data. `FULL_TRANSACTION` assumes transactional tables and no indirect nontransactional effects. See the official references linked in README for engine details.

### Read results and handle failures

Successful execution returns `transactionId`, `datasourceId`, `databaseType`, `state`, `implicitCommitPossible`, `rollbackScope`, and ordered `results`. Each result has zero-based `statementIndex` and `resultIndex`:

- Query/RETURNING/OUTPUT row sets contain `type: "QUERY"`, `columns`, `rows`, `rowCount`, `maxRows`, and `truncated`.
- Update counts contain `type: "UPDATE"` (or `TRUNCATE` for a truncate count) and `affectedRows`. Counts come from the driver. TRUNCATE may not report deleted rows or may produce no count entry. Generated keys are not requested automatically; query them explicitly or use supported RETURNING/OUTPUT.
- A statement can produce more than one result. Do not assume exactly one entry per statement. Truncated results do not prove that omitted rows meet your validation criteria; narrow the query or use aggregates before committing. The query row cap never limits DML's affected-row count.

Failures use MCP `isError: true`; parse the JSON text body as well as checking the protocol flag. The body contains `success: false`, `code`, `message`, and transaction metadata when available. Database errors can include a sanitized `sqlState`, the failing `statementIndex`, and results completed earlier in that call. SQL text, raw driver messages, and credentials are not echoed in error bodies.

| Code/state | Agent action |
| --- | --- |
| `SQL_REJECTED` | Nothing from this call executed. Correct the SQL; an existing transaction remains usable. |
| `TRANSACTION_REQUIRED` / `NESTED_TRANSACTION` | Fix the ID/control-command usage; no implicit transaction is created. |
| `TRANSACTION_NOT_FOUND` / `TRANSACTION_ENDED` / `TRANSACTION_EXPIRED` | Do not replay writes automatically. Determine whether the prior transaction ended or expired; IDs are never restored. |
| `TRANSACTION_LIMIT` / `BEGIN_FAILED` / `TRANSACTIONS_UNSUPPORTED` | No usable new handle was returned. Resolve capacity/support/connectivity before opening another transaction. |
| `TRANSACTION_BUSY` | Another operation owns the same ID. Avoid concurrent calls and wait for its result before deciding the next step. |
| `EXECUTION_FAILED`, state `ROLLBACK_ONLY` | Stop execution and send ROLLBACK with the same ID. COMMIT and further ordinary SQL are rejected. No automatic retry or commit occurred. |
| `ROLLBACK_REQUIRED` | Send ROLLBACK; correcting SQL cannot restore this failed transaction. |
| `COMMIT_OUTCOME_UNKNOWN` / `ROLLBACK_OUTCOME_UNKNOWN`, state `UNKNOWN` | Handle is invalidated and connection discarded. Inspect database state independently; never claim rollback or blindly repeat writes. |
| `SESSION_CLOSED` / `SERVICE_CLOSED` | The session/service cannot accept further transaction work; cleanup attempts rollback. Reconnect only after resolving the previous outcome. |

### Lifetime, isolation, switching, and cleanup

- Defaults: 120 seconds idle, 600 seconds total lifetime, 5 active transactions globally, 20 statements and 20000 SQL characters per call, 30 seconds per script. Statements also use the existing 10-second query timeout. Operators can override `dbmcp.transaction.*`; row/cell limits use `dbmcp.query.*` (100 rows and 4000 characters per cell by default).
- Retained connections consume pool capacity between calls. Per-target capacity also leaves one connection for independent tools where possible; a one-connection pool is fully occupied by one transaction. Finish promptly instead of holding locks while performing unrelated work.
- Explicit MCP session DELETE, idle/lifetime expiry, and graceful service shutdown attempt rollback and release resources. Normal HTTP request completion or an SSE disconnect does not finish your transaction. Expiry waits for the transaction's execution lock; driver timeouts/cancellation can be delayed. Hard process crashes do not return a cleanup acknowledgement. No transaction handle survives restart.
- All subsequent calls carrying an existing ID stay on the original `datasourceId`, even after a global switch. A new BEGIN uses the then-current target. Never replace an ID just because a switch occurred.
- Other sessions avoiding dirty reads cannot normally see uncommitted transactional writes, but can be blocked by locks. MySQL nontransactional tables, TRUNCATE implicit commits, triggers/functions, external effects, and sequences can break full rollback assumptions. Do not promise that an open transaction has no impact on other sessions.

## Namespace and identifiers

- `db_list_tables` and `db_describe_table` use `dbmcp.database.schema` when configured, otherwise the connection's current schema. MySQL uses the database in its JDBC URL. If no current schema is available, metadata calls fail and ask for an explicit `schema` or `schemaName`.
- This setting scopes **metadata discovery only**; it does not change SQL name resolution. Qualify tables in SQL when needed.
- Unquoted names follow the driver's identifier case rules (uppercase in Oracle, lowercase in PostgreSQL). To address a case-sensitive name, pass it quoted, for example `"MixedCase"` **including the double quotes** for a quoted PostgreSQL table.

## Recommended workflow

1. **Confirm the target**: after a `db_switch_datasource` call, note the returned `databaseType` and `datasourceId`; remember the switch is global and memory-only.
2. **Explore schema**: `db_list_tables` → `db_describe_table` on tables of interest.
3. **Read data**: `db_query` with `?` placeholders; keep result sets small (`maxRows`, `LIMIT`).
4. **Change data**: for immediate commits, use `db_execute_dml` with placeholders, then verify with `db_query`. For changes that need verification before commit, use the complete `db_execute` workflow above and query with the same transactionId.
5. **Change schema**: `db_execute_ddl` only after stating the target object and intent — `DROP` and `TRUNCATE` are irreversible.
6. **Redis**: `redis_scan_keys` to locate keys → `redis_inspect_key` → `redis_read_key`; modify with `redis_write_key` / `redis_delete_keys` / `redis_expire_key`; switch databases with `redis_select_db` only when the task requires another database, and re-scan afterward.

## Examples

```json
// db_query
{ "sql": "SELECT id, name FROM users WHERE status = ? LIMIT 10", "parameters": ["active"] }

// db_execute_dml
{ "sql": "UPDATE users SET status = ? WHERE id = ?", "parameters": ["inactive", 42] }

// db_execute_ddl (confirm first — destructive)
{ "sql": "ALTER TABLE users ADD COLUMN last_login DATETIME NULL" }

// db_switch_datasource — global, memory-only
{ "jdbcUrl": "jdbc:postgresql://db-host:5432/appdb", "username": "app_user", "password": "secret", "schema": "public" }

// db_describe_table with a schema override
{ "tableName": "events", "schemaName": "reporting" }

// redis_write_key — hash with TTL
{ "key": "user:42:profile", "type": "hash", "fields": { "name": "Alice", "city": "Berlin" }, "ttlSeconds": 3600 }

// redis_write_key — list append and sorted set
{ "key": "queue:jobs", "type": "list", "values": ["job-1", "job-2"] }
{ "key": "leaderboard", "type": "zset", "scoreMembers": { "alice": 100.5, "bob": 87.0 } }

// redis_scan_keys — first page
{ "pattern": "user:*", "cursor": "0" }
```

## Error handling

- Invalid input (wrong statement type, multiple statements, oversized values, out-of-range database index, missing required argument) is rejected before execution with a descriptive `IllegalArgumentException` message — fix the call instead of retrying.
- Execution failures surface as `IllegalStateException` with the SQLState when available. For Redis, check the current database and service reachability.
- A failed `db_switch_datasource` never changes the current database; do not retry blindly — correct the URL, credentials, or schema, or check the reported error code.
- Authentication failures appear as HTTP `401` before any tool call; the token comes from server YAML and cannot be changed at runtime.
