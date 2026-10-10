package com.meowody.runtime.tests;

import com.meowody.runtime.audio.AudioCenter;
import com.meowody.runtime.audio.AudioHandle;
import com.meowody.runtime.audio.AudioPlayOptions;
import com.meowody.runtime.audio.AudioPlayer;
import com.meowody.runtime.coroutine.CoroutineCenter;
import com.meowody.runtime.download.DownloadCenter;
import com.meowody.runtime.event.EventCenter;
import com.meowody.runtime.net.NetCenter;
import com.meowody.runtime.time.TimeCenter;

import java.lang.management.ManagementFactory;

/** CPU-thread allocation comparison; excludes rendering and worker threads. */
public final class RuntimePerformanceCheck {
    public static void main(String[] args) {
        TimeCenter time = new TimeCenter();
        CoroutineCenter coroutine = new CoroutineCenter();
        NetCenter net = new NetCenter();
        DownloadCenter download = new DownloadCenter(new com.badlogic.gdx.files.FileHandle("meowody/build/performance-downloads"));
        AudioCenter audio = new AudioCenter(null);
        try {
            checkCallbacks(time, coroutine, audio);
            Runnable frame = () -> {
                time.update(1f / 60);
                coroutine.update(time);
                net.update();
                download.update();
                audio.update(1d / 60);
            };
            measure("idle", frame);
            for (int i = 0; i < 1000; i++) coroutine.start(delta -> false, false);
            measure("1000 coroutines", frame);
        } finally {
            audio.dispose(); download.dispose(); net.dispose(); time.dispose(); coroutine.clear();
        }
    }

    private static void checkCallbacks(TimeCenter time, CoroutineCenter coroutine, AudioCenter audio) {
        EventCenter events = new EventCenter();
        int[] delivered = {0};
        events.on(Integer.class, value -> {
            delivered[0]++;
            events.on(Integer.class, next -> delivered[0]++);
        });
        events.emit(1);
        check(delivered[0] == 1, "New listener ran during current delivery");
        events.emit(1);
        check(delivered[0] == 3, "New listener was lost");
        events.clear();
        events.on(Integer.class, value -> events.clear(Integer.class));
        events.on(Integer.class, value -> { throw new AssertionError("Cleared listener ran"); });
        events.emit(1);
        check(events.listenerCount() == 0, "Cleared listeners were retained");
        int[] calls = {0};
        coroutine.start(delta -> {
            coroutine.clear();
            coroutine.start(next -> { calls[0]++; return true; }, false);
            coroutine.update(time);
            return true;
        }, false);
        coroutine.start(delta -> { throw new AssertionError("Cleared task ran"); }, false);
        coroutine.update(time);
        check(calls[0] == 0, "Spawned task ran in same frame");
        coroutine.update(time);
        check(calls[0] == 1 && coroutine.empty(), "Spawned task was lost");

        Player first = new Player(), second = new Player();
        AudioHandle handle = audio.play("SFX", () -> first, new AudioPlayOptions(), () -> {
            audio.play("SFX", () -> second, new AudioPlayOptions(), null);
            audio.update(0);
        });
        for (int i = 0; i < 10; i++) audio.update(1d / 60);
        check(first.volumeCalls == 1 && first.pauseCalls == 1, "Unchanged audio state was reapplied");
        handle.setVolume(.5f);
        handle.pause();
        audio.update(0);
        check(first.volume == .5f && first.paused, "Audio controls were not applied");
        handle.resume();
        first.playing = false;
        audio.update(0);
        check(first.disposed && second.playing, "Completion callback failed to start a voice");
        audio.stopAll();
        audio.update(0);
        check(second.disposed && audio.activeCount("SFX") == 0, "Completed voices were retained");
        System.out.println("Runtime callback and audio checks passed");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Player implements AudioPlayer {
        int volumeCalls, pauseCalls;
        float volume;
        boolean playing, paused, disposed;
        public void play(float from, boolean loop) { playing = true; }
        public void volume(float value) { volume = value; volumeCalls++; }
        public void paused(boolean value) { paused = value; pauseCalls++; }
        public boolean playing() { return playing; }
        public float position() { return 0; }
        public boolean seek(float value) { return true; }
        public void stop() { playing = false; }
        public void dispose() { disposed = true; }
    }

    private static void measure(String label, Runnable frame) {
        for (int i = 0; i < 20000; i++) frame.run();
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().getId();
        long allocated = bean.getThreadAllocatedBytes(thread), start = System.nanoTime();
        int frames = 20000;
        for (int i = 0; i < frames; i++) frame.run();
        long elapsed = System.nanoTime() - start;
        System.out.printf(java.util.Locale.ROOT, "%s: %.1f bytes/frame, %.2f us/frame%n", label,
            (bean.getThreadAllocatedBytes(thread) - allocated) / (double) frames, elapsed / (double) frames / 1000);
    }
}
