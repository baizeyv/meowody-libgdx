package com.meowody.runtime;

import com.badlogic.gdx.Screen;
import com.meowody.runtime.base.BaseGame;

/** {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms. */
public class Meowody extends BaseGame {
    @Override
    protected Screen createFirstScreen() {
        return new FirstScreen();
    }
}
