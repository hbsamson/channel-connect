package com.svi.channelconnect.messenger.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Retains exception types and stack locations while excluding messages and payloads. */
public final class SafeExceptionLog {
    private SafeExceptionLog() {
    }

    public static Throwable sanitized(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return sanitized(failure, visited);
    }

    private static Throwable sanitized(Throwable failure, Set<Throwable> visited) {
        if (failure == null || !visited.add(failure)) {
            return null;
        }
        Throwable safe = new RuntimeException(failure.getClass().getName(),
                sanitized(failure.getCause(), visited));
        safe.setStackTrace(failure.getStackTrace());
        return safe;
    }
}
