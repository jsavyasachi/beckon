package com.hypirion.beckon;

import clojure.lang.Seqable;
import sun.misc.SignalHandler;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/**
 * Delegates all signal operations to the selected {@link SignalBackend}, while
 * preserving the static method surface that the rest of beckon
 * ({@link SignalRegisterer}, {@link SignalAtoms}) relies on. Backend choice is
 * therefore invisible to callers and to the public Clojure API.
 *
 * <p>The backend is chosen once, from the {@code beckon.signal.backend} system
 * property: {@code "sunmisc"} (default) or {@code "ffm"}. The FFM backend is
 * loaded reflectively so its JDK 22+ classes never link unless explicitly
 * requested; requesting it where it cannot run fails with a clear message.
 */
public class SignalRegistererHelper {

    private static final String[] CANDIDATE_SIGNALS = {
        "HUP", "INT", "QUIT", "ILL", "ABRT", "FPE", "KILL", "SEGV",
        "PIPE", "ALRM", "TERM", "USR1", "USR2", "CHLD", "CONT", "STOP",
        "TSTP", "TTIN", "TTOU", "URG", "XCPU", "XFSZ", "VTALRM", "PROF",
        "WINCH", "IO", "PWR", "SYS"
    };

    private static final SignalBackend BACKEND = select();

    private static SignalBackend select() {
        String choice = System.getProperty("beckon.signal.backend", "sunmisc");
        if ("sunmisc".equalsIgnoreCase(choice)) {
            return new SunMiscSignalBackend();
        }
        if ("ffm".equalsIgnoreCase(choice)) {
            String os = System.getProperty("os.name", "").toLowerCase();
            String cls =
                os.contains("linux") ? "com.hypirion.beckon.FfmSignalfdBackend"
                : (os.contains("mac") || os.contains("darwin") || os.contains("bsd"))
                  ? "com.hypirion.beckon.FfmKqueueBackend"
                  : null;
            if (cls == null) {
                throw new UnsupportedOperationException(
                    "beckon FFM signal backend supports Linux (signalfd) and "
                    + "macOS/BSD (kqueue), not " + System.getProperty("os.name"));
            }
            try {
                return (SignalBackend)
                    Class.forName(cls).getDeclaredConstructor().newInstance();
            } catch (java.lang.reflect.InvocationTargetException e) {
                // Surface the backend's own reason (e.g. wrong OS / JDK).
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                throw new UnsupportedOperationException(
                    "beckon FFM signal backend is unavailable", cause);
            } catch (ReflectiveOperationException e) {
                throw new UnsupportedOperationException(
                    "beckon FFM signal backend requires JDK 22+; "
                    + "it is not available here.", e);
            }
        }
        throw new IllegalArgumentException(
            "Unknown beckon.signal.backend: " + choice
            + " (expected \"sunmisc\" or \"ffm\")");
    }

    /** Simple class name of the active backend, for diagnostics and tests. */
    public static String backendName() {
        return BACKEND.getClass().getSimpleName();
    }

    public static String normalizeSignalName(String signame) {
        if (signame == null) {
            throw new IllegalArgumentException("signal name must be a non-empty string");
        }
        String normalized = signame.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("SIG")) normalized = normalized.substring(3);
        if (normalized.length() == 0 || !normalized.matches("[A-Z0-9]+")) {
            throw new IllegalArgumentException("invalid signal name: " + signame);
        }
        return normalized;
    }

    public static boolean signalSupported(String signame) {
        try {
            return BACKEND.signalSupported(normalizeSignalName(signame));
        } catch (IllegalArgumentException unsupported) {
            return false;
        }
    }

    public static Set<String> supportedSignals() {
        Set<String> result = new HashSet<String>();
        for (String candidate : CANDIDATE_SIGNALS) {
            if (BACKEND.signalSupported(candidate)) result.add(candidate);
        }
        return result;
    }

    public static synchronized void shutdown() throws SignalHandlerNotFoundException {
        BACKEND.resetAll();
        SignalAtoms.clear();
        SignalFolder.shutdownDispatch();
    }

    static synchronized void register(String signame, Seqable fns) {
        BACKEND.register(normalizeSignalName(signame), fns);
    }

    static synchronized void resetDefaultHandler(String signame)
        throws SignalHandlerNotFoundException {
        BACKEND.reset(normalizeSignalName(signame));
    }

    static synchronized void resetAll() throws SignalHandlerNotFoundException {
        BACKEND.resetAll();
    }

    static synchronized Seqable getHandlerSeq(String signame) {
        return BACKEND.currentRunnables(normalizeSignalName(signame));
    }

    static void raise(String signame) {
        BACKEND.raise(normalizeSignalName(signame));
    }

    static SignalHandler currentHandler(String signame) {
        return BACKEND.currentHandler(normalizeSignalName(signame));
    }

    static void setDefaultHandler(String signame) {
        BACKEND.setDefaultHandler(normalizeSignalName(signame));
    }

    static void setIgnoredHandler(String signame) {
        BACKEND.setIgnoredHandler(normalizeSignalName(signame));
    }

    static void chainHandler(String signame, SignalHandler handler) {
        BACKEND.chainHandler(normalizeSignalName(signame), handler);
    }

    static void restoreHandler(String signame) {
        BACKEND.restoreHandler(normalizeSignalName(signame));
    }
}
