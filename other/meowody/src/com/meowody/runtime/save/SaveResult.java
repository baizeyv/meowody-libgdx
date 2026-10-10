package com.meowody.runtime.save;

public record SaveResult<T>(SaveError error, String message, T data, boolean usedDefault) { public boolean ok() { return error == SaveError.NONE; } }
