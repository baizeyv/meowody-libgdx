package com.meowody.runtime.save;

public record ModelLoadResult(SaveError error, String message, int savedVersion, int currentVersion, boolean migrated) { public boolean ok() { return error == SaveError.NONE; } }
