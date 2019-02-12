package tool.lock.redis.exception;

public class FrequentOperationException extends RuntimeException {
    public FrequentOperationException() {
        super("Frequent operation, please try again later");
    }

    public FrequentOperationException(String message) {
        super(message);
    }

    public FrequentOperationException(String message, Throwable cause) {
        super(message, cause);
    }

    public FrequentOperationException(Throwable cause) {
        super(cause);
    }
}