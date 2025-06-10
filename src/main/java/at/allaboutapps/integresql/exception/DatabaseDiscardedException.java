package at.allaboutapps.integresql.exception;

public class DatabaseDiscardedException extends IntegresqlException{
    public DatabaseDiscardedException(String message) {
        super(message, 410);
    }

    public DatabaseDiscardedException(String message, Throwable cause) {
        super(message, 410, cause);
    }
}
