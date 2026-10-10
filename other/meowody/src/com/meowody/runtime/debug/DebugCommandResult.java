package com.meowody.runtime.debug;

public record DebugCommandResult(boolean ok, String text) {
    public static DebugCommandResult success(String text) { return new DebugCommandResult(true, text); }
    public static DebugCommandResult failure(String text) { return new DebugCommandResult(false, text); }
}
