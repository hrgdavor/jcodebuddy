package hr.hrg.jcodebuddy.tool;

/** The result of applying or rejecting one file proposal. */
public record ApplyResult(Status status, String message) {

    public enum Status { APPLIED, REVERTED, STALE, MISSING, REFUSED }

    public boolean ok() {
        return status == Status.APPLIED || status == Status.REVERTED;
    }

    public static ApplyResult applied(String message) {
        return new ApplyResult(Status.APPLIED, message);
    }

    public static ApplyResult reverted(String message) {
        return new ApplyResult(Status.REVERTED, message);
    }

    public static ApplyResult stale(String message) {
        return new ApplyResult(Status.STALE, message);
    }

    public static ApplyResult missing(String message) {
        return new ApplyResult(Status.MISSING, message);
    }

    public static ApplyResult refused(String message) {
        return new ApplyResult(Status.REFUSED, message);
    }
}
