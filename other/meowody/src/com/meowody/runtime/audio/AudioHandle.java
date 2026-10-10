package com.meowody.runtime.audio;

public final class AudioHandle {
    private final AudioCenter center;
    final long id;
    final String channel;
    final AudioPlayer player;
    final Runnable callback;
    VoiceStatus status;
    float volume;
    boolean paused;
    float appliedVolume = Float.NaN;
    boolean appliedPause, hasAppliedPause;
    AudioHandle(AudioCenter center, long id, String channel, AudioPlayer player, AudioPlayOptions options, Runnable callback) {
        this.center = center; this.id = id; this.channel = channel; this.player = player; volume = options.volume; this.callback = callback;
        status = player == null ? VoiceStatus.FAILED : VoiceStatus.PLAYING;
    }
    public long id() { return id; }
    public VoiceStatus status() { return status; }
    public boolean isFinished() { return status == VoiceStatus.FINISHED || status == VoiceStatus.STOPPED || status == VoiceStatus.FAILED; }
    public void stop() { if (!isFinished()) { status = VoiceStatus.STOPPED; player.stop(); } }
    public void pause() { if (!isFinished()) { paused = true; center.apply(this); } }
    public void resume() { if (!isFinished()) { paused = false; center.apply(this); } }
    public boolean setVolume(float value) { if (isFinished() || !AudioCenter.validVolume(value)) return false; volume = value; center.apply(this); return true; }
    public float positionSeconds() { return isFinished() ? 0 : player.position(); }
    public boolean seek(float seconds) { return !isFinished() && Float.isFinite(seconds) && seconds >= 0 && player.seek(seconds); }
}
