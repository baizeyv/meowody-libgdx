package com.meowody.runtime.net;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NetRequestOptions {
    public String url;
    public String method = "GET";
    public Map<String, String> headers = new LinkedHashMap<>();
    public byte[] body = new byte[0];
    public double timeoutSeconds = 15;
    public long maxResponseBytes = 8 * 1024 * 1024;
    public NetRequestOptions copy() { NetRequestOptions o = new NetRequestOptions(); o.url = url; o.method = method; o.headers = new LinkedHashMap<>(headers); o.body = body.clone(); o.timeoutSeconds = timeoutSeconds; o.maxResponseBytes = maxResponseBytes; return o; }
}
