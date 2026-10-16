package com.example.dbconnectmcp.database;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/** Public error codes without driver messages, chained causes, or credentials. */
public class DatabaseSwitchException extends IllegalStateException {

    public enum Code { INVALID_ARGUMENT, AUTHENTICATION_FAILED, CONNECTION_FAILED, VALIDATION_FAILED,
        VALIDATION_TIMEOUT, SERVICE_CLOSED, SWITCH_INTERRUPTED }

    private final Code code;

    public DatabaseSwitchException(Code code) {
        super(code.name() + ": " + switch (code) {
            case INVALID_ARGUMENT -> "Invalid JDBC connection settings.";
            case AUTHENTICATION_FAILED -> "Database authentication failed.";
            case CONNECTION_FAILED -> "Could not connect to the database.";
            case VALIDATION_FAILED -> "Database connection or schema validation failed.";
            case VALIDATION_TIMEOUT -> "Database validation or switch wait timed out.";
            case SERVICE_CLOSED -> "The database service is shutting down.";
            case SWITCH_INTERRUPTED -> "The database switch was interrupted.";
        });
        this.code = code;
    }

    public Code code() { return code; }

    static DatabaseSwitchException from(SQLException error) {
        String state = error.getSQLState();
        if ((state != null && state.startsWith("28")) || error.getErrorCode() == 1017
                || error.getErrorCode() == 1045 || error.getErrorCode() == 18456) {
            return new DatabaseSwitchException(Code.AUTHENTICATION_FAILED);
        }
        if (error instanceof SQLTimeoutException || "HYT00".equals(state) || "HYT01".equals(state)) {
            return new DatabaseSwitchException(Code.VALIDATION_TIMEOUT);
        }
        return new DatabaseSwitchException(Code.CONNECTION_FAILED);
    }
}
