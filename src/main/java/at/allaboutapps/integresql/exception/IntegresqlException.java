package at.allaboutapps.integresql.exception;

/**
 * Base exception for errors related to the IntegreSQL client or API interactions.
 */
public class IntegresqlException extends RuntimeException {

    private final int statusCode;

    public IntegresqlException(String message) {
        super(message);
        this.statusCode = -1;
    }

    public IntegresqlException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
    }

    public IntegresqlException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public IntegresqlException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    /**
     * Gets the HTTP status code associated with this exception, if available.
     * @return the HTTP status code, or -1 if not set.
     */
    public int getStatusCode() {
        return statusCode;
    }
}