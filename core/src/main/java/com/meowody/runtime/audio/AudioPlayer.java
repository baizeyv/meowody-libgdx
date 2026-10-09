package com.meowody.runtime.audio;

import com.badlogic.gdx.utils.Disposable;

public interface AudioPlayer extends Disposable {
    void play(float fromSeconds, boolean looping);
    void volume(float value);
    void paused(boolean value);
    boolean playing();
    float position();
    boolean seek(float seconds);
    void stop();
}
