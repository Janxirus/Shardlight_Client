package dev.shardlight.client.error;

import dev.shardlight.client.i18n.I18n;

import java.util.Arrays;

/**
 * A failure the user can understand and act on. {@code messageKey} names a localized message
 * (see {@code i18n/*.properties}); {@code args} fill its placeholders. {@link #getMessage()} is the
 * English text for logs, {@link #userMessage()} the localized text for the UI.
 */
public class LauncherException extends Exception {
    private final ErrorCode code;
    private final String messageKey;
    private final transient Object[] args;

    public LauncherException(ErrorCode code, String messageKey, Object... args) {
        this(code, null, messageKey, args);
    }

    public LauncherException(ErrorCode code, Throwable cause, String messageKey, Object... args) {
        super(code + ": " + I18n.english(messageKey, args), cause);
        this.code = code;
        this.messageKey = messageKey;
        this.args = args == null ? new Object[0] : args.clone();
    }

    public ErrorCode code() {
        return code;
    }

    public String messageKey() {
        return messageKey;
    }

    public Object[] args() {
        return args.clone();
    }

    /** the localized, user-facing message */
    public String userMessage() {
        return I18n.tr(messageKey, args);
    }

    public boolean isCancelled() {
        return code == ErrorCode.CANCELLED;
    }

    public static LauncherException cancelled() {
        return new LauncherException(ErrorCode.CANCELLED, "error.cancelled");
    }

    public static LauncherException io(Throwable cause, String what) {
        return new LauncherException(ErrorCode.IO, cause, "error.io", what, cause == null ? "" : String.valueOf(cause.getMessage()));
    }

    public static LauncherException internal(String detail) {
        return new LauncherException(ErrorCode.INTERNAL, "error.internal", detail);
    }

    @Override
    public String toString() {
        return "LauncherException{" + code + ", " + messageKey + ", " + Arrays.toString(args) + "}";
    }
}
