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

    public SignalFolder(Seqable funs) {
        this(null, funs);
    }

    public SignalFolder(String signame, Seqable funs) {
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
    }

    public static void configureDispatch(String mode, ExecutorService executor) {
        SignalDispatcher.configure(mode, executor);
    }

    public void handle(Signal sig) {
        SignalDispatcher.dispatch(signame, fns);
    }
}
