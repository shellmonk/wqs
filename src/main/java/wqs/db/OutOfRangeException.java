package wqs.db;

/** A row whose month is outside the allowed partition range (see {@link PartitionProperties}). */
public class OutOfRangeException extends RuntimeException {

    public OutOfRangeException(String message) {
        super(message);
    }
}
