package com.meowody.runtime.net;

public final class HttpResponse {
    public final int status;
    public final String body;
    HttpResponse(NetResult result) { status = result.statusCode(); body = result.text(); }
}
