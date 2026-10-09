package com.meowody.runtime.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.utils.Disposable;
import com.meowody.runtime.log.LogCenter;
import com.meowody.runtime.resource.ResourceCenter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Logical Music/SFX/UI/Voice channels, independent voices, handles and volume fades. */
public final class AudioCenter implements Disposable {

    /** Platform audio adapter: required because libGDX Sound and Music expose different controls. */

    private static final class Channel {
        final AudioChannelOptions options;
        double elapsed, duration;
        float from, target;
        Channel(AudioChannelOptions options) { this.options = options.copy(); }
    }
    private static final class Bus { float volume = 1; boolean muted; }
    private static final class MusicPlayer implements AudioPlayer {
        final Music music;
        boolean started, paused;
        MusicPlayer(Music music) { this.music = music; }
        public void play(float from, boolean loop) {
            music.setLooping(loop); music.play(); music.setPosition(from); started = true;
        }
        public void volume(float value) { music.setVolume(value); }
        public void paused(boolean value) {
            if (value == paused) return;
            paused = value;
            if (value) music.pause(); else if (started) music.play();
        }
        public boolean playing() { return paused || music.isPlaying(); }
        public float position() { return music.getPosition(); }
        public boolean seek(float value) { music.setPosition(value); return true; }
        public void stop() { music.stop(); }
        public void dispose() { music.dispose(); }
    }
    private final ResourceCenter resources;
    private final Map<String, Channel> channels = new LinkedHashMap<>();
    private final Map<String, Bus> buses = new LinkedHashMap<>();
    private final Map<Long, AudioHandle> voices = new LinkedHashMap<>();
    private long nextId = 1;
    private boolean closed, updating, globallyPaused, globallyMuted;
    private AudioHandle background;
    private java.util.function.Function<com.badlogic.gdx.files.FileHandle, AudioPlayer> playerFactory = file -> new MusicPlayer(Gdx.audio.newMusic(file));
    public void setPlayerFactory(java.util.function.Function<com.badlogic.gdx.files.FileHandle, AudioPlayer> factory) {
        if (closed) throw new IllegalStateException("Audio center closed");
        playerFactory = java.util.Objects.requireNonNull(factory);
    }

    public AudioCenter(ResourceCenter resources) {
        this.resources = resources;
        buses.put("Master", new Bus());
        for (String name : new String[] {"Music", "SFX", "UI", "Voice"}) createChannel(name, new AudioChannelOptions());
    }
    static boolean validVolume(float value) { return Float.isFinite(value) && value >= 0 && value <= 1; }
    public boolean createChannel(String name, AudioChannelOptions options) {
        if (closed || name == null || name.isEmpty() || channels.containsKey(name) || !validVolume(options.volume) || options.maxVoices < 1 || !buses.containsKey(options.bus)) return false;
        channels.put(name, new Channel(options)); return true;
    }
    public boolean hasChannel(String name) { return channels.containsKey(name); }
    public boolean createBus(String name) { if (closed || name == null || name.isEmpty() || buses.containsKey(name)) return false; buses.put(name, new Bus()); return true; }
    public boolean setBusVolume(String name, float value) { Bus bus = buses.get(name); if (closed || bus == null || !validVolume(value)) return false; bus.volume = value; new ArrayList<>(voices.values()).forEach(this::apply); return true; }
    public boolean setBusMuted(String name, boolean value) { Bus bus = buses.get(name); if (closed || bus == null) return false; bus.muted = value; new ArrayList<>(voices.values()).forEach(this::apply); return true; }
    public Set<String> channels() { return Set.copyOf(channels.keySet()); }
    public AudioChannelOptions channelOptions(String name) { Channel channel = channels.get(name); return channel == null ? new AudioChannelOptions() : channel.options.copy(); }
    public boolean removeChannel(String name) { if (closed || !hasChannel(name)) return false; stopChannel(name); channels.remove(name); return true; }
    public boolean setVolume(String name, float value) {
        Channel channel = channels.get(name);
        if (closed || channel == null || !validVolume(value)) return false;
        channel.duration = 0; channel.options.volume = value; applyChannel(name); return true;
    }
    public boolean setMuted(String name, boolean value) {
        Channel channel = channels.get(name); if (closed || channel == null) return false;
        channel.options.muted = value; applyChannel(name); return true;
    }
    public boolean setPaused(String name, boolean value) {
        Channel channel = channels.get(name); if (closed || channel == null) return false;
        channel.options.paused = value; applyChannel(name); return true;
    }
    public boolean fadeVolume(String name, float target, double seconds) {
        Channel channel = channels.get(name);
        if (closed || channel == null || !validVolume(target) || !Double.isFinite(seconds) || seconds < 0) return false;
        if (seconds == 0) return setVolume(name, target);
        channel.from = channel.options.volume; channel.target = target; channel.elapsed = 0; channel.duration = seconds; return true;
    }
    public AudioHandle play(String channel, String path, AudioPlayOptions options, Runnable finished) {
        return play(channel, () -> playerFactory.apply(resources.resolveFile(path)), options, finished);
    }
    public AudioHandle play(String name, Supplier<AudioPlayer> factory, AudioPlayOptions options, Runnable finished) {
        Channel channel = channels.get(name);
        AudioHandle failed = new AudioHandle(this, 0, name, null, options, null);
        if (closed || channel == null || factory == null || !validVolume(options.volume) || !Float.isFinite(options.fromSeconds) || options.fromSeconds < 0) return failed;
        if (activeCount(name) >= channel.options.maxVoices) {
            if (!channel.options.stealOldest) return failed;
        }
        AudioPlayer player = null;
        try {
            player = factory.get();
            if (player == null) return failed;
            player.play(options.fromSeconds, options.looping);
            if (activeCount(name) >= channel.options.maxVoices)
                for (AudioHandle handle : voices.values()) if (handle.channel.equals(name) && !handle.isFinished()) { handle.stop(); break; }
            AudioHandle handle = new AudioHandle(this, nextId++, name, player, options, finished);
            voices.put(handle.id, handle); apply(handle); return handle;
        } catch (RuntimeException error) { if (player != null) player.dispose(); LogCenter.error("Audio", error, "Cannot play voice in %s", name); return failed; }
    }
    void apply(AudioHandle handle) {
        Channel channel = channels.get(handle.channel); if (channel == null || handle.isFinished()) return;
        Bus bus = buses.get(channel.options.bus), master = buses.get("Master");
        float gain = bus == master ? master.volume : bus.volume * master.volume;
        handle.player.volume(globallyMuted || channel.options.muted || bus.muted || master.muted ? 0 : channel.options.volume * handle.volume * gain);
        boolean paused = globallyPaused || channel.options.paused || handle.paused;
        handle.player.paused(paused); handle.status = paused ? VoiceStatus.PAUSED : VoiceStatus.PLAYING;
    }
    private void applyChannel(String name) { for (AudioHandle handle : new ArrayList<>(voices.values())) if (handle.channel.equals(name)) apply(handle); }
    public int activeCount(String name) { return (int) voices.values().stream().filter(h -> h.channel.equals(name) && !h.isFinished()).count(); }
    public void stopChannel(String name) { for (AudioHandle h : voices.values()) if (h.channel.equals(name)) h.stop(); }
    public void stopAll() { voices.values().forEach(AudioHandle::stop); }
    public void update(double delta) {
        if (closed || updating) return; updating = true;
        try {
            if (!Double.isFinite(delta) || delta < 0) delta = 0;
            for (Channel channel : channels.values()) if (channel.duration > 0) {
                channel.elapsed = Math.min(channel.duration, channel.elapsed + delta);
                channel.options.volume = channel.from + (channel.target - channel.from) * (float) (channel.elapsed / channel.duration);
                if (channel.elapsed == channel.duration) channel.duration = 0;
            }
            ArrayList<AudioHandle> done = new ArrayList<>();
            for (AudioHandle handle : new ArrayList<>(voices.values())) {
                apply(handle);
                if (!handle.isFinished() && handle.status != VoiceStatus.PAUSED && !handle.player.playing()) handle.status = VoiceStatus.FINISHED;
                if (handle.isFinished()) { voices.remove(handle.id); handle.player.dispose(); done.add(handle); }
            }
            for (AudioHandle handle : done) {
                if (closed) break;
                if (handle.status == VoiceStatus.FINISHED && handle.callback != null) {
                    try { handle.callback.run(); } catch (RuntimeException error) { LogCenter.error("Audio", error, "Completion callback failed"); }
                }
            }
        } finally { updating = false; }
    }
    public long playSound(String path) { return play("SFX", path, new AudioPlayOptions(), null).id(); }
    public void playMusic(String path, boolean loop) { stopMusic(); AudioPlayOptions options = new AudioPlayOptions(); options.looping = loop; background = play("Music", path, options, null); }
    public void stopMusic() { if (background != null) background.stop(); background = null; }
    public void stopSounds() { stopChannel("SFX"); }
    public void setSoundVolume(float value) { if (!setVolume("SFX", value)) throw new IllegalArgumentException("Invalid sound volume"); }
    public void setMusicVolume(float value) { if (!setVolume("Music", value)) throw new IllegalArgumentException("Invalid music volume"); }
    public float soundVolume() { return channelOptions("SFX").volume; }
    public float musicVolume() { return channelOptions("Music").volume; }
    public void setMuted(boolean value) { globallyMuted = value; new ArrayList<>(voices.values()).forEach(this::apply); }
    public boolean isMuted() { return globallyMuted; }
    public void pause() { globallyPaused = true; new ArrayList<>(voices.values()).forEach(this::apply); }
    public void resume() { globallyPaused = false; new ArrayList<>(voices.values()).forEach(this::apply); }
    public boolean isClosed() { return closed; }
    public void shutdown() { if (closed) return; closed = true; stopAll(); for (AudioHandle h : voices.values()) h.player.dispose(); voices.clear(); channels.clear(); buses.clear(); }
    public void dispose() { shutdown(); }
}
