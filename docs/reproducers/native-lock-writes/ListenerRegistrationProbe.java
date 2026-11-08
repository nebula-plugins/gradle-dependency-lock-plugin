/*
 * Copyright 2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

// Diagnostic reflection forces registration contention; this is not plugin implementation code.
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.build.BuildModelLifecycleListener;
import org.gradle.internal.event.DefaultListenerManager;
import org.gradle.internal.service.scopes.Scope;

public class ListenerRegistrationProbe {
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    public static final class Listener implements BuildModelLifecycleListener {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void beforeModelDiscarded(GradleInternal model, boolean failed) {
            calls.incrementAndGet();
        }
    }

    private static int probe(boolean contended) throws Exception {
        DefaultListenerManager manager = new DefaultListenerManager(Scope.Build.class);
        BuildModelLifecycleListener broadcaster = manager.getBroadcaster(BuildModelLifecycleListener.class);
        Listener listener = new Listener();
        if (contended) {
            Map<?, ?> broadcasters = (Map<?, ?>) field(manager, "broadcasters");
            Object broadcast = broadcasters.get(BuildModelLifecycleListener.class);
            ReentrantLock lock = (ReentrantLock) field(broadcast, "broadcasterLock");
            AtomicReference<Throwable> error = new AtomicReference<>();
            lock.lock();
            try {
                Thread registering = new Thread(() -> {
                    try {
                        manager.addListener(listener);
                    } catch (Throwable failure) {
                        error.set(failure);
                    }
                });
                registering.start();
                registering.join(5000);
                if (registering.isAlive()) throw new AssertionError("Registration did not finish");
                if (error.get() != null) throw new AssertionError("Registration failed", error.get());
            } finally {
                lock.unlock();
            }
        } else {
            manager.addListener(listener);
        }
        broadcaster.beforeModelDiscarded(null, false);
        int first = listener.calls.get();
        broadcaster.beforeModelDiscarded(null, false);
        System.out.printf("contended=%s first_event_calls=%d second_event_total_calls=%d%n", contended, first, listener.calls.get());
        return first;
    }

    public static void main(String[] args) throws Exception {
        if (probe(false) != 1) throw new AssertionError("Uncontended control failed");
        if (probe(true) != 1) {
            System.err.println("Listener registration finished before the event, but the first lifecycle notification was omitted.");
            System.exit(1);
        }
    }
}
