package dev.pocket.control;

public final class ControlException extends Exception {
    public enum Code {
        NOT_READY, PERMISSION_REQUIRED, SCREEN_OFF, LOCKED, STALE_OBSERVATION,
        INVALID_ARGUMENT, UNSUPPORTED, AMBIGUOUS_TARGET, ACTION_REJECTED, RESULT_UNKNOWN
    }
    public final Code code;
    public ControlException(Code code, String message) { super(message); this.code = code; }
}
