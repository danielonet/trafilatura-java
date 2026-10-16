package io.github.danielonet.trafilatura.compat;

/** Python's {@code SystemExit}: raised by {@code sys.exit()} and argparse errors. */
public class SystemExit extends RuntimeException {
    public final int code;
    /** Message of {@code sys.exit("message")}, printed to stderr by the entry point; null otherwise. */
    public final String text;

    public SystemExit(int code) {
        super("exit code " + code);
        this.code = code;
        this.text = null;
    }

    /** {@code sys.exit("message")}: the message goes to stderr and the exit code is 1. */
    public SystemExit(String message) {
        super(message);
        this.code = 1;
        this.text = message;
    }
}
