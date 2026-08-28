package com.hypirion.beckon;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import clojure.lang.IFn;

/** Dispatches signal callbacks without putting application work on the signal thread. */
final class SignalDispatcher {
    private static volatile String mode = "synchronous";
    private static volatile ExecutorService executor;
    private static final Map<String, SerialExecutor> serialExecutors =
        new ConcurrentHashMap<String, SerialExecutor>();
    private static volatile String errorMode = "default";
    private static volatile IFn errorCallback;
    private static volatile long lifecycle;

    private SignalDispatcher() {}

    static void configure(String newMode, ExecutorService newExecutor) {
        mode = newMode;
        executor = newExecutor;
        serialExecutors.clear();
    }

    static void configureErrors(String newMode, IFn callback) {
        errorMode = newMode;
        errorCallback = callback;
    }

    static void shutdown() {
        mode = "synchronous";
        executor = null;
        serialExecutors.clear();
        errorMode = "default";
        errorCallback = null;
        lifecycle++;
    }

    static void dispatch(String signame, Runnable[] callbacks) {
        if ("synchronous".equals(mode)) {
            runSerially(signame, callbacks);
        } else if ("serial".equals(mode)) {
            String key = signame == null ? "" : signame;
            SerialExecutor serial = serialExecutors.get(key);
            if (serial == null) {
                SerialExecutor candidate = new SerialExecutor(executor);
                SerialExecutor existing = serialExecutors.putIfAbsent(key, candidate);
                serial = existing == null ? candidate : existing;
            }
            final Runnable[] copy = callbacks;
            final long generation = lifecycle;
            serial.execute(new Runnable() {
                public void run() {
                    if (generation == lifecycle) runSerially(signame, copy);
                }
            });
        } else {
            final long generation = lifecycle;
            for (final Runnable callback : callbacks) {
                try {
                    executor.execute(new Runnable() {
                        public void run() {
                            if (generation != lifecycle) return;
                            try {
                                callback.run();
                            } catch (Exception error) {
                                handleError(signame, error, false);
                            }
                        }
                    });
                } catch (RejectedExecutionException rejected) {
                    if (!"bounded".equals(mode)) {
                        throw rejected;
                    }
                    // Bounded dispatch is non-blocking: a full executor drops this callback.
                }
            }
        }
    }

    private static void runSerially(String signame, Runnable[] callbacks) {
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Exception error) {
                if (!handleError(signame, error, true)) break;
            }
        }
    }

    private static boolean handleError(String signame, Exception error, boolean synchronous) {
        String selected = "default".equals(errorMode)
            ? (synchronous ? "stop" : "continue") : errorMode;
        if ("log".equals(selected)) {
            error.printStackTrace(System.err);
        }
        if (errorCallback != null) errorCallback.invoke(error);
        if ("rethrow".equals(selected)) throw new RuntimeException(error);
        return !"stop".equals(selected);
    }

    private static final class SerialExecutor implements java.util.concurrent.Executor {
        private final ExecutorService backend;
        private final ArrayDeque<Runnable> queue = new ArrayDeque<Runnable>();
        private Runnable active;

        SerialExecutor(ExecutorService backend) {
            this.backend = backend;
        }

        public synchronized void execute(final Runnable command) {
            queue.add(new Runnable() {
                public void run() {
                    try {
                        command.run();
                    } finally {
                        scheduleNext();
                    }
                }
            });
            if (active == null) {
                scheduleNext();
            }
        }

        private synchronized void scheduleNext() {
            if ((active = queue.poll()) != null) {
                backend.execute(active);
            }
        }
    }
}
