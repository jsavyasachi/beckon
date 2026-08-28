package com.hypirion.beckon;

import clojure.lang.Seqable;
import sun.misc.SignalHandler;
import java.util.Collections;
import java.util.Set;

/**
 * Pluggable backend for OS signal handling. Every JDK- or OS-specific signal
 * operation lives behind this interface; the atom-of-Runnables model in
 * {@link SignalAtoms} and beckon.clj is backend-agnostic.
 *
 * <p>The default implementation is {@link SunMiscSignalBackend}. Alternative
 * backends are selected at startup via the {@code beckon.signal.backend} system
 * property (see {@link SignalRegistererHelper}).
 */
public interface SignalBackend {

    default boolean signalSupported(String signame) {
        try {
            currentRunnables(signame);
            return true;
        } catch (RuntimeException unsupported) {
            return false;
        }
    }

    default Set<String> supportedSignals() {
        return Collections.emptySet();
    }

    /** Install (or replace) the handler for {@code signame} to run the given Runnables. */
    void register(String signame, Seqable runnables);

    /** Restore {@code signame} to the disposition it had before beckon touched it. */
    void reset(String signame) throws SignalHandlerNotFoundException;

    /** Restore every signal beckon has managed. */
    void resetAll() throws SignalHandlerNotFoundException;

    /** The Runnables currently handling {@code signame}, used to seed its signal-atom. */
    Seqable currentRunnables(String signame);

    /** Raise {@code signame} in the current process. */
    void raise(String signame);

    default SignalHandler currentHandler(String signame) {
        throw new UnsupportedOperationException("signal dispositions are not supported by this backend");
    }

    default void setDefaultHandler(String signame) {
        throw new UnsupportedOperationException("signal dispositions are not supported by this backend");
    }

    default void setIgnoredHandler(String signame) {
        throw new UnsupportedOperationException("signal dispositions are not supported by this backend");
    }

    default void chainHandler(String signame, SignalHandler handler) {
        throw new UnsupportedOperationException("signal dispositions are not supported by this backend");
    }

    default void restoreHandler(String signame) {
        throw new UnsupportedOperationException("signal dispositions are not supported by this backend");
    }
}
