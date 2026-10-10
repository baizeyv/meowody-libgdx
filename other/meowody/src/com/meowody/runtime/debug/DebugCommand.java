package com.meowody.runtime.debug;

import java.util.List;
import java.util.function.Function;

public record DebugCommand(String name, String description, String usage, Function<DebugCommandContext, DebugCommandResult> execute, Function<DebugCommandContext, List<String>> complete) { }
