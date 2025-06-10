package at.allaboutapps.integresql.exception;

public class ManagerNotReadyException  extends IntegresqlException{
    public ManagerNotReadyException(String message) {
        super(message, 503);
    }

    public ManagerNotReadyException(String message, Throwable cause) {
        super(message, 503, cause);
    }
}
