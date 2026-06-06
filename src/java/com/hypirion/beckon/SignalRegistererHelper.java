package com.hypirion.beckon;

import clojure.lang.Seqable;

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

    private static final SignalBackend BACKEND = select();

    private static SignalBackend select() {
        String choice = System.getProperty("beckon.signal.backend", "sunmisc");
        if ("sunmisc".equalsIgnoreCase(choice)) {
            return new SunMiscSignalBackend();
        }
        if ("ffm".equalsIgnoreCase(choice)) {
            try {
                return (SignalBackend)
                    Class.forName("com.hypirion.beckon.FfmSignalfdBackend")
                         .getDeclaredConstructor().newInstance();
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
                    "beckon FFM signal backend requires Linux and JDK 22+; "
                    + "it is not available here.", e);
            }
        }
        throw new IllegalArgumentException(
            "Unknown beckon.signal.backend: " + choice
            + " (expected \"sunmisc\" or \"ffm\")");
    }

    static synchronized void register(String signame, Seqable fns) {
        BACKEND.register(signame, fns);
    }

    static synchronized void resetDefaultHandler(String signame)
        throws SignalHandlerNotFoundException {
        BACKEND.reset(signame);
    }

    static synchronized void resetAll() throws SignalHandlerNotFoundException {
        BACKEND.resetAll();
    }

    static synchronized Seqable getHandlerSeq(String signame) {
        return BACKEND.currentRunnables(signame);
    }

    static void raise(String signame) {
        BACKEND.raise(signame);
    }
}
