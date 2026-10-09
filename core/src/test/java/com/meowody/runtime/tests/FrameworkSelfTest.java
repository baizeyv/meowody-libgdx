package com.meowody.runtime.tests;

import com.meowody.runtime.coroutine.CoroutineCenter;
import com.meowody.runtime.coroutine.CoroutineHandle;
import com.meowody.runtime.event.EventCenter;
import com.meowody.runtime.event.EventSubscription;
import com.meowody.runtime.pool.PoolCenter;
import com.meowody.runtime.time.TimeCenter;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free logic checks. Run with :core:frameworkTest. */
public final class FrameworkSelfTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        TimeCenter time = new TimeCenter();
        CoroutineCenter tasks = new CoroutineCenter();
        AtomicInteger calls = new AtomicInteger();
        CoroutineHandle delayed = tasks.delay(1, false, calls::incrementAndGet);
        tasks.delay(.5f, true, calls::incrementAndGet);
        time.setScale(0);
        time.update(.5f);
        tasks.update(time);
        check(calls.get() == 1 && !delayed.isDone(), "Unscaled task must run during pause");
        time.setScale(2);
        time.update(.5f);
        tasks.update(time);
        check(calls.get() == 2 && delayed.isDone(), "Scaled delay must finish");
        CoroutineHandle cancelled = tasks.delay(0, true, calls::incrementAndGet);
        cancelled.cancel();
        tasks.update(time);
        check(calls.get() == 2 && tasks.size() == 0, "Cancellation must suppress task");
        tasks.start(delta -> { tasks.delay(0, true, calls::incrementAndGet); return true; }, true);
        tasks.update(time);
        check(calls.get() == 2, "Tasks spawned during update must wait until next frame");
        tasks.update(time);
        check(calls.get() == 3, "Spawned task must run next frame");
        tasks.delay(0, true, () -> { throw new IllegalStateException("expected test failure"); });
        tasks.delay(0, true, calls::incrementAndGet);
        tasks.update(time);
        check(calls.get() == 4 && tasks.size() == 0, "Failed tasks must not block others");

        EventCenter events = new EventCenter();
        EventSubscription subscription = events.on(Integer.class, value -> calls.addAndGet(value));
        events.emit(2);
        subscription.close();
        subscription.close();
        events.emit(2);
        check(calls.get() == 6 && events.listenerCount() == 0, "Unsubscribe must be idempotent");

        PoolCenter pools = new PoolCenter();
        pools.register("strings", StringBuilder.class, StringBuilder::new, value -> value.setLength(0), 2);
        StringBuilder first = pools.obtain("strings", StringBuilder.class);
        first.append("dirty");
        pools.free(first);
        StringBuilder reused = pools.obtain("strings", StringBuilder.class);
        check(reused == first && reused.length() == 0, "Pool must reuse and reset plain Java objects");
        pools.free(reused);
        boolean rejected = false;
        try { pools.free(reused); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Double free must be rejected");
        pools.clear();
        CenterParitySelfTest.run();
        System.out.println("Framework logic checks passed");
    }
}
