package com.meowody.runtime.debug;

import java.util.List;

public record DebugCommandContext(String line, List<String> args) { }
