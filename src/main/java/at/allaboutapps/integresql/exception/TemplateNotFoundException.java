package at.allaboutapps.integresql.exception;

public class TemplateNotFoundException extends IntegresqlException{
    public TemplateNotFoundException(String message) {
        super(message, 404);
    }

    public TemplateNotFoundException(String message, Throwable cause) {
        super(message, 404, cause);
    }
}
