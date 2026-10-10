package com.meowody.runtime.download;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DownloadOptions {
    public boolean resume = true, useCache = true;
    public int maxRetries = 2, maxRedirects = 5;
    public double timeoutSeconds = 30, retryDelaySeconds = 1;
    public long maxBytes = 512L * 1024 * 1024;
    public String expectedSha256 = "";
    public Map<String, String> headers = new LinkedHashMap<>();
    public DownloadOptions copy() { DownloadOptions o = new DownloadOptions(); o.resume = resume; o.useCache = useCache; o.maxRetries = maxRetries; o.maxRedirects = maxRedirects; o.timeoutSeconds = timeoutSeconds; o.retryDelaySeconds = retryDelaySeconds; o.maxBytes = maxBytes; o.expectedSha256 = expectedSha256; o.headers = new LinkedHashMap<>(headers); return o; }
    String signature() { return resume + ":" + useCache + ":" + maxRetries + ":" + maxRedirects + ":" + timeoutSeconds + ":" + retryDelaySeconds + ":" + maxBytes + ":" + expectedSha256.toLowerCase(java.util.Locale.ROOT) + ":" + new java.util.TreeMap<>(headers); }
}
