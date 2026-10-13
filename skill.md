# db-connect-mcp — Agent Skill

An MCP server that gives an agent read and write access to one MySQL database and one Redis instance through 12 tools. The server holds all database and Redis credentials; the agent only needs the MCP endpoint (`http://<host>:<port>/db-mcp`, Streamable HTTP).

See [README.md](README.md) for deployment and configuration details.

## Tool catalog

### MySQL — read

| Tool | Parameters | Behavior |
| --- | --- | --- |
| `db_list_tables` | — | Lists tables and views, at most 500. |
| `db_describe_table` | `tableName` | Column names, types, nullability, and primary key. |
| `db_query` | `sql`, `parameters?`, `maxRows?` | One read-only `SELECT` (`WITH ... SELECT` allowed). Binds values via `?` placeholders. At most 100 rows, 10 s timeout, 4000 chars per cell (longer values are truncated and flagged). |

### MySQL — write

| Tool | Parameters | Behavior |
| --- | --- | --- |
| `db_execute_dml` | `sql`, `parameters?` | One `INSERT` / `UPDATE` / `DELETE` / `MERGE` / `UPSERT`. Returns `affectedRows`; `INSERT` also returns up to 50 `generatedKeys`. |
| `db_execute_ddl` | `sql` | One `CREATE` / `ALTER` / `DROP` / `TRUNCATE` / `RENAME`. Destructive; may be disabled server-side (`dbmcp.write.allow-ddl=false`). |

### Redis — read (all operate on the currently selected database)

| Tool | Parameters | Behavior |
| --- | --- | --- |
| `redis_scan_keys` | `pattern?`, `cursor?`, `count?` | Cursor-based `SCAN` (never `KEYS`). Start with `cursor: "0"`, then feed back `nextCursor` until it is `"0"` again. |
| `redis_inspect_key` | `key` | Type, TTL, and size; returns `exists: false` for missing keys. |
| `redis_read_key` | `key`, `maxItems?` | Reads up to 100 elements from a string / hash / list / set / sorted set. |

### Redis — write

| Tool | Parameters | Behavior |
| --- | --- | --- |
| `redis_write_key` | `key`, `type`, `value?` / `values?` / `fields?` / `scoreMembers?`, `ttlSeconds?` | Writes one key. `type`: `string` (uses `value`), `list`/`set` (uses `values`), `hash` (uses `fields`), `zset` (uses `scoreMembers`). Lists append to the tail; hashes merge incrementally. |
| `redis_delete_keys` | `keys` | Deletes up to 100 keys, returns the removed count. |
| `redis_expire_key` | `key`, `ttlSeconds` | Sets a TTL; `ttlSeconds: 0` removes the expiry (PERSIST). |
| `redis_select_db` | `db` | Switches the current database (0–15 by default) for **all** subsequent Redis tools, reads and writes alike. |

## Hard constraints

**SQL**

- Exactly one statement per call. No comments (`--`, `/* */`, `#`), no trailing `;`, at most 20000 characters.
- Always bind values with `?` placeholders and the `parameters` array; never concatenate data into SQL text.
- `SELECT` must go to `db_query` only; `db_execute_dml` / `db_execute_ddl` reject it.
- Parameters may only be strings, numbers, booleans, or `null`.

**Redis**

- Keys are at most 512 characters; each value/member is at most 4000 characters (longer writes are rejected, longer reads are truncated).
- At most 100 elements per read, write, or delete call.
- `SELECT` is global state: after `redis_select_db`, every Redis tool targets the new database. Track which database you are on; results silently come from the current one.

## Recommended workflow

1. **Explore schema**: `db_list_tables` → `db_describe_table` on tables of interest.
2. **Read data**: `db_query` with `?` placeholders; keep result sets small (`maxRows`, `LIMIT`).
3. **Change data**: `db_execute_dml` with placeholders, then verify with `db_query`.
4. **Change schema**: `db_execute_ddl` only after stating the target object and intent — `DROP` and `TRUNCATE` are irreversible.
5. **Redis**: `redis_scan_keys` to locate keys → `redis_inspect_key` → `redis_read_key`; modify with `redis_write_key` / `redis_delete_keys` / `redis_expire_key`; switch databases with `redis_select_db` only when the task requires another database.

## Examples

```json
// db_query
{ "sql": "SELECT id, name FROM users WHERE status = ? LIMIT 10", "parameters": ["active"] }

// db_execute_dml
{ "sql": "UPDATE users SET status = ? WHERE id = ?", "parameters": ["inactive", 42] }

// db_execute_ddl (confirm first — destructive)
{ "sql": "ALTER TABLE users ADD COLUMN last_login DATETIME NULL" }

// redis_write_key — hash with TTL
{ "key": "user:42:profile", "type": "hash", "fields": { "name": "Alice", "city": "Berlin" }, "ttlSeconds": 3600 }

// redis_write_key — list append and sorted set
{ "key": "queue:jobs", "type": "list", "values": ["job-1", "job-2"] }
{ "key": "leaderboard", "type": "zset", "scoreMembers": { "alice": 100.5, "bob": 87.0 } }

// redis_scan_keys — first page
{ "pattern": "user:*", "cursor": "0" }
```

## Error handling

Invalid input (wrong statement type, multiple statements, oversized values, out-of-range database index) is rejected before execution with a descriptive `IllegalArgumentException` message — fix the call instead of retrying. Execution failures surface as `IllegalStateException` with the SQLState when available; for Redis, check the current database and service reachability.
