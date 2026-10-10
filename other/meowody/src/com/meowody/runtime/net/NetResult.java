package com.meowody.runtime.net;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public record NetResult(NetError error, int statusCode, Map<String, List<String>> headers, byte[] body, String message) {
    public boolean ok() { return error == NetError.NONE; }
    public String text() { return new String(body, StandardCharsets.UTF_8); }
}
