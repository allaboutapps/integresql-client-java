package at.allaboutapps.integresql.exception;

public class TestNotFoundException extends IntegresqlException{
    public TestNotFoundException(String message) {
        super(message, 404);
    }

    public TestNotFoundException(String message, Throwable cause) {
        super(message, 404, cause);
    }
}
