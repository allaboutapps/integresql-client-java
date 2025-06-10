package at.allaboutapps.integresql.exception;

public class TemplateAlreadyInitializedException extends IntegresqlException {
    public TemplateAlreadyInitializedException(String message) {
        super(message, 423);
    }

    public TemplateAlreadyInitializedException(String message, Throwable cause) {
        super(message, 423, cause);
    }
}
