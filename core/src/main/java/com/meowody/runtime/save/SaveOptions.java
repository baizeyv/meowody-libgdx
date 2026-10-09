package com.meowody.runtime.save;

public final class SaveOptions {
    public boolean deferWrites = true;
    public byte[] encryptionKey = new byte[0];
    public long maxFileBytes = 64L * 1024 * 1024;
    public SaveOptions copy() { SaveOptions o = new SaveOptions(); o.deferWrites = deferWrites; o.encryptionKey = encryptionKey.clone(); o.maxFileBytes = maxFileBytes; return o; }
}
