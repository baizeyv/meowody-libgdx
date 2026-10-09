package com.meowody.runtime.save;

public record SaveStatus(SaveError error, String message) { public boolean ok() { return error == SaveError.NONE; } }
