package com.hypirion.beckon;

import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

import clojure.lang.PersistentHashSet;
import clojure.lang.Seqable;

/**
 * {@link SignalBackend} backed by {@code sun.misc.Signal} /
 * {@code sun.misc.SignalHandler}. This is the default backend: it works on
 * JDK 8+ with no extra JVM flags. The JDK marks these classes "internal
 * proprietary"; this class (together with {@link SignalFolder}) is the single
 * point in beckon that depends on them.
 */
public class SunMiscSignalBackend implements SignalBackend {

    /** Original handler for each signal beckon has taken over, for later restore. */
    private final Map<String, SignalHandler> originalHandlers =
        new HashMap<String, SignalHandler>();

    private static boolean reserved(String signame) {
        return "USR2".equals(signame);
    }

    private static void rejectReserved(String signame) {
        if (reserved(signame)) {
            throw new IllegalArgumentException(
                "signal " + signame + " is reserved by HotSpot; use beckon's chained handler");
        }
    }

    private SignalHandler setHandler(String signame, Seqable fns) {
        Signal sig = new Signal(signame);
        SignalFolder folder = new SignalFolder(signame, fns);
        return Signal.handle(sig, folder);
    }

    @Override
    public synchronized void register(String signame, Seqable fns) {
        SignalHandler old = setHandler(signame, fns);
        if (!originalHandlers.containsKey(signame)) {
            originalHandlers.put(signame, old);
        }
    }

    @Override
    public synchronized void reset(String signame)
        throws SignalHandlerNotFoundException {
        if (originalHandlers.containsKey(signame)) {
            SignalHandler original = originalHandlers.get(signame);
            Signal sig = new Signal(signame);
            Signal.handle(sig, original);
            originalHandlers.remove(signame);
            SignalAtoms.getSignalAtom(signame).reset(currentRunnables(signame));
            // The atom's watch re-registers our folder; re-install the original.
            Signal.handle(sig, original);
        }
    }

    @Override
    public synchronized void resetAll() throws SignalHandlerNotFoundException {
        // Copy the key set so we can reset (which mutates the map) while looping.
        List<String> signames = new ArrayList<String>(originalHandlers.keySet());
        for (String signame : signames) {
            reset(signame);
        }
    }

    @Override
    public synchronized Seqable currentRunnables(String signame) {
        Signal sig = new Signal(signame);
        // No direct getter exists; double-handle to read the current handler.
        SignalHandler current = Signal.handle(sig, SignalHandler.SIG_DFL);
        Signal.handle(sig, current);
        if (current instanceof SignalFolder) {
            return ((SignalFolder) current).originalList;
        } else {
            Runnable wrapped = new RunnableSignalHandler(sig, current);
            return PersistentHashSet.create(wrapped);
        }
    }

    @Override
    public synchronized SignalHandler currentHandler(String signame) {
        rejectReserved(signame);
        Signal sig = new Signal(signame);
        SignalHandler current = Signal.handle(sig, SignalHandler.SIG_DFL);
        try {
            return current;
        } finally {
            Signal.handle(sig, current);
        }
    }

    @Override
    public synchronized void setDefaultHandler(String signame) {
        rejectReserved(signame);
        remember(signame, Signal.handle(new Signal(signame), SignalHandler.SIG_DFL));
    }

    @Override
    public synchronized void setIgnoredHandler(String signame) {
        rejectReserved(signame);
        remember(signame, Signal.handle(new Signal(signame), SignalHandler.SIG_IGN));
    }

    @Override
    public synchronized void chainHandler(String signame, SignalHandler handler) {
        if (handler == null) throw new NullPointerException("handler");
        rejectReserved(signame);
        Signal sig = new Signal(signame);
        SignalHandler old = Signal.handle(sig, SignalHandler.SIG_DFL);
        Signal.handle(sig, old);
        remember(signame, old);
        Seqable runnables = old instanceof SignalFolder
            ? ((SignalFolder) old).originalList
            : clojure.lang.PersistentHashSet.EMPTY;
        Signal.handle(sig, new SignalFolder(runnables, handler));
    }

    private void remember(String signame, SignalHandler old) {
        if (!originalHandlers.containsKey(signame)) {
            originalHandlers.put(signame, old);
        }
    }

    @Override
    public synchronized void restoreHandler(String signame) {
        SignalHandler original = originalHandlers.remove(signame);
        if (original != null) {
            Signal.handle(new Signal(signame), original);
        }
    }

    @Override
    public void raise(String signame) {
        Signal sig = new Signal(signame);
        Signal.raise(sig);
    }

    /**
     * Wraps a non-beckon {@code SignalHandler} as a Runnable so it can live in a
     * signal-atom alongside Clojure functions without breaking {@code swap!}.
     */
    private static class RunnableSignalHandler implements Runnable {
        private final Signal sig;
        private final SignalHandler handler;

        RunnableSignalHandler(Signal sig, SignalHandler handler) {
            this.sig = sig;
            this.handler = handler;
        }

        @Override
        public void run() {
            handler.handle(sig);
        }
    }
}
