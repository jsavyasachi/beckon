package com.hypirion.beckon;

import sun.misc.Signal;
import sun.misc.SignalHandler;

import java.util.List;
import java.util.concurrent.ExecutorService;

import clojure.lang.Seqable;
import clojure.lang.ISeq;

public class SignalFolder implements SignalHandler {
    final String signame;
    final Seqable originalList;
    final private Runnable[] fns;
    private final SignalHandler chained;

    public SignalFolder(Seqable funs) {
        this(null, funs, null);
    }

    public SignalFolder(String signame, Seqable funs) {
        this(signame, funs, null);
    }

    public SignalFolder(Seqable funs, SignalHandler chained) {
        this(null, funs, chained);
    }

    public SignalFolder(String signame, Seqable funs, SignalHandler chained) {
        this.signame = signame;
        ISeq seq = funs.seq();
        // seq may be null
        if (seq == null) {
            fns = new Runnable[0];
        }
        else {
            fns = new Runnable[seq.count()];
            for (int i = 0; i < fns.length; i++) {
                fns[i] = (Runnable) seq.first();
                seq = seq.next();
            }
        }
        originalList = funs;
        this.chained = chained;
    }

    public static void configureDispatch(String mode, ExecutorService executor) {
        SignalDispatcher.configure(mode, executor);
    }

    public static void configureErrors(String mode, clojure.lang.IFn callback) {
        SignalDispatcher.configureErrors(mode, callback);
    }

    public static void shutdownDispatch() {
        SignalDispatcher.shutdown();
    }

    public void handle(Signal sig) {
        SignalDispatcher.dispatch(signame, fns);
        // The chained handler is the disposition that was installed before
        // beckon took over, so it runs after our callbacks are dispatched.
        // Under the default synchronous policy that means after they have all
        // run; under an asynchronous policy dispatch only enqueues them, so the
        // chained handler may run before they complete.
        if (chained != null) {
            chained.handle(sig);
        }
    }
}
