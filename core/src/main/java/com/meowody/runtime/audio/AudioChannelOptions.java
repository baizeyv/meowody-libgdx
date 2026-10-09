package com.meowody.runtime.audio;

public final class AudioChannelOptions {
    public float volume = 1;
    public boolean muted, paused;
    public int maxVoices = 16;
    public boolean stealOldest = true;
    public String bus = "Master";
    public AudioChannelOptions copy() { AudioChannelOptions o = new AudioChannelOptions(); o.volume = volume; o.muted = muted; o.paused = paused; o.maxVoices = maxVoices; o.stealOldest = stealOldest; o.bus = bus; return o; }
}
