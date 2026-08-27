package com.hypirion.beckon;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** Dispatches signal callbacks without putting application work on the signal thread. */
final class SignalDispatcher {
    private static volatile String mode = "synchronous";
    private static volatile ExecutorService executor;
    private static final Map<String, SerialExecutor> serialExecutors =
        new ConcurrentHashMap<String, SerialExecutor>();

    private SignalDispatcher() {}

    static void configure(String newMode, ExecutorService newExecutor) {
        mode = newMode;
        executor = newExecutor;
        serialExecutors.clear();
    }

    static void dispatch(String signame, Runnable[] callbacks) {
        if ("synchronous".equals(mode)) {
            runSerially(callbacks);
        } else if ("serial".equals(mode)) {
            String key = signame == null ? "" : signame;
            SerialExecutor serial = serialExecutors.get(key);
            if (serial == null) {
                SerialExecutor candidate = new SerialExecutor(executor);
                SerialExecutor existing = serialExecutors.putIfAbsent(key, candidate);
                serial = existing == null ? candidate : existing;
            }
            final Runnable[] copy = callbacks;
            serial.execute(new Runnable() {
                public void run() { runSerially(copy); }
            });
        } else {
            for (final Runnable callback : callbacks) {
                try {
                    executor.execute(new Runnable() {
                        public void run() {
                            try {
                                callback.run();
                            } catch (Exception ignored) {
                                // Match the historical behavior: callback exceptions do not escape beckon.
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

    private static void runSerially(Runnable[] callbacks) {
        for (Runnable callback : callbacks) {
            try {
                callback.run();
            } catch (Exception ignored) {
                break;
            }
        }
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
