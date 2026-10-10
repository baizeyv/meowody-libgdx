package com.meowody.runtime.core;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

/** Maps Godot's project user:// storage to private app files / desktop user data. */
public final class Storage {
    private Storage() { }
    public static FileHandle directory(String namespace) {
        if (!namespace.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid storage namespace");
        if (Gdx.app.getType() == Application.ApplicationType.Desktop)
            return Gdx.files.absolute(new java.io.File(System.getProperty("user.home"), ".meowody/" + namespace).getPath());
        return Gdx.files.local("meowody/" + namespace);
    }
}
