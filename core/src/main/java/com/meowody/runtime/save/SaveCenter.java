package com.meowody.runtime.save;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Preferences;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.meowody.runtime.core.Storage;
import com.meowody.runtime.log.LogCenter;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Partitioned, validated, atomic saves; writes merge per partition and frame. */
public final class SaveCenter implements Disposable {

    private static final byte[] MAGIC = "MWJSAV01".getBytes(StandardCharsets.US_ASCII);
    private final Json json = new Json();
    private final Map<String, Map<String, String>> pending = new LinkedHashMap<>();
    private final Map<String, Long> lastFrames = new LinkedHashMap<>();
    private FileHandle directory;
    private SaveOptions options;
    private Preferences legacy;
    private long commits;
    private boolean closed, scheduled;
    private Runnable flushScheduler;
    private SaveStatus lastFlush = okStatus();
    private static SaveStatus okStatus() { return new SaveStatus(SaveError.NONE, ""); }
    public SaveCenter(String namespace) {
        this(namespace, Storage.directory(namespace).child("saves"));
    }
    public SaveCenter(String namespace, FileHandle directory) {
        this(directory, new SaveOptions());
        legacy = Gdx.app.getPreferences(namespace);
    }
    public SaveCenter(FileHandle directory, SaveOptions options) {
        json.setTypeName(null);
        SaveStatus status = configure(directory, options);
        if (!status.ok()) throw new IllegalArgumentException(status.message());
    }
    public SaveStatus configure(FileHandle directory, SaveOptions options) {
        if (closed) return new SaveStatus(SaveError.CLOSED, "Save center closed");
        if (directory == null || options == null || options.maxFileBytes < 1) return new SaveStatus(SaveError.INVALID_ARGUMENT, "Invalid directory/options");
        if (options.encryptionKey == null || (options.encryptionKey.length != 0 && options.encryptionKey.length != 32)) return new SaveStatus(SaveError.INVALID_KEY, "AES key must contain exactly 32 bytes");
        SaveStatus flushed = flushPending(); if (!flushed.ok()) return flushed;
        this.directory = directory; this.options = options.copy(); lastFrames.clear(); return okStatus();
    }
    private SaveStatus validate(String part, String slot) {
        if (closed) return new SaveStatus(SaveError.CLOSED, "Save center closed");
        if (part == null || !part.matches("[A-Za-z0-9_-]{1,64}") || slot == null || slot.isEmpty() || slot.length() > 128 || slot.indexOf('\0') >= 0)
            return new SaveStatus(SaveError.INVALID_ARGUMENT, "Invalid partition or slot");
        return okStatus();
    }
    private static String key(String part) { return part.toLowerCase(java.util.Locale.ROOT); }
    public String partPath(String part) { return directory.child(key(part) + ".sav").path(); }
    public String directory() { return directory.path(); }
    private SaveResult<Map<String, String>> readPart(String part, boolean disk) {
        SaveStatus status = validate(part, "default");
        if (!status.ok()) return new SaveResult<>(status.error(), status.message(), new LinkedHashMap<>(), false);
        if (!disk && pending.containsKey(key(part))) return new SaveResult<>(SaveError.NONE, "", new LinkedHashMap<>(pending.get(key(part))), false);
        Path path = new FileHandle(partPath(part)).file().toPath();
        if (!Files.exists(path)) return new SaveResult<>(SaveError.MISSING, "Partition missing", new LinkedHashMap<>(), false);
        try {
            if (Files.size(path) > options.maxFileBytes + 128) return new SaveResult<>(SaveError.CORRUPT, "File exceeds limit", new LinkedHashMap<>(), false);
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length < 41 || !Arrays.equals(MAGIC, Arrays.copyOf(bytes, 8))) return new SaveResult<>(SaveError.CORRUPT, "Invalid save header", new LinkedHashMap<>(), false);
            int encrypted = bytes[8];
            if (encrypted != 0 && encrypted != 1) return new SaveResult<>(SaveError.CORRUPT, "Invalid encryption flag", new LinkedHashMap<>(), false);
            byte[] payload = Arrays.copyOfRange(bytes, 41, bytes.length);
            if (encrypted == 1) {
                if (options.encryptionKey.length != 32 || payload.length < 28) return new SaveResult<>(SaveError.INVALID_KEY, "Encryption key required", new LinkedHashMap<>(), false);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(options.encryptionKey, "AES"), new GCMParameterSpec(128, Arrays.copyOf(payload, 12)));
                cipher.updateAAD(Arrays.copyOf(bytes, 41));
                payload = cipher.doFinal(Arrays.copyOfRange(payload, 12, payload.length));
            }
            if (payload.length > options.maxFileBytes || !MessageDigest.isEqual(Arrays.copyOfRange(bytes, 9, 41), MessageDigest.getInstance("SHA-256").digest(payload)))
                return new SaveResult<>(SaveError.CORRUPT, "Checksum mismatch", new LinkedHashMap<>(), false);
            JsonValue root = new JsonReader().parse(new String(payload, StandardCharsets.UTF_8));
            if (!root.isObject()) throw new IllegalArgumentException("Partition must be object");
            Map<String, String> slots = new LinkedHashMap<>();
            for (JsonValue value : root) {
                if (!value.isString() || slots.containsKey(value.name)) throw new IllegalArgumentException("Invalid slot container");
                new JsonReader().parse(value.asString());
                slots.put(value.name, value.asString());
            }
            return new SaveResult<>(SaveError.NONE, "", slots, false);
        } catch (javax.crypto.AEADBadTagException error) { return new SaveResult<>(SaveError.INVALID_KEY, "Wrong key or damaged encrypted save", new LinkedHashMap<>(), false); }
        catch (java.io.IOException error) { return new SaveResult<>(SaveError.IO, error.toString(), new LinkedHashMap<>(), false); }
        catch (Exception error) { return new SaveResult<>(SaveError.CORRUPT, error.toString(), new LinkedHashMap<>(), false); }
    }
    private SaveStatus stage(String part, Map<String, String> slots) {
        if (encode(slots).getBytes(StandardCharsets.UTF_8).length > options.maxFileBytes)
            return new SaveStatus(SaveError.INVALID_ARGUMENT, "Partition exceeds file limit");
        pending.put(key(part), new LinkedHashMap<>(slots));
        if (!options.deferWrites) return flushPending();
        requestFlush(); return okStatus();
    }
    public SaveStatus save(String part, String slot, Object value) {
        SaveStatus status = validate(part, slot); if (!status.ok()) return status;
        try {
            checkData(value, new java.util.IdentityHashMap<>(), 0);
            String encoded = encode(value);
            SaveResult<Map<String, String>> existing = readPart(part, false);
            if (!existing.ok() && existing.error() != SaveError.MISSING) return new SaveStatus(existing.error(), existing.message());
            existing.data().put(slot, encoded); return stage(part, existing.data());
        } catch (Exception error) { return new SaveStatus(SaveError.UNSUPPORTED_DATA, error.toString()); }
    }
    public <T> SaveResult<T> load(String part, String slot, Class<T> type, T defaults) {
        SaveStatus status = validate(part, slot);
        if (!status.ok()) return new SaveResult<>(status.error(), status.message(), defaults, true);
        SaveResult<Map<String, String>> partition = readPart(part, false);
        String encoded = partition.data().get(slot);
        if (partition.ok() && encoded != null) {
            try { return new SaveResult<>(SaveError.NONE, "", json.fromJson(type, encoded), false); }
            catch (Exception error) { return new SaveResult<>(SaveError.CORRUPT, error.toString(), defaults, true); }
        }
        SaveError error = partition.ok() ? SaveError.MISSING : partition.error();
        if (error == SaveError.MISSING && part.equals("default") && legacy != null && legacy.contains(slot)) {
            try { return new SaveResult<>(SaveError.NONE, "", json.fromJson(type, legacy.getString(slot)), false); }
            catch (Exception ignored) { error = SaveError.CORRUPT; }
        }
        return new SaveResult<>(error, partition.message(), defaults, true);
    }
    public SaveResult<Map<String, Object>> inspect(String part, boolean disk) {
        SaveResult<Map<String, String>> result = readPart(part, disk);
        Map<String, Object> values = new LinkedHashMap<>();
        if (result.ok()) result.data().forEach((slot, value) -> values.put(slot, tree(new JsonReader().parse(value))));
        return new SaveResult<>(result.error(), result.message(), values, false);
    }
    public boolean hasSlot(String part, String slot) { SaveResult<Map<String, String>> result = readPart(part, false); return result.ok() && result.data().containsKey(slot); }
    public Set<String> listSlots(String part) { return new TreeSet<>(readPart(part, false).data().keySet()); }
    public Set<String> listParts() {
        Set<String> names = new TreeSet<>(pending.keySet());
        if (!closed && directory.exists()) for (FileHandle file : directory.list(".sav")) names.add(file.nameWithoutExtension());
        return names;
    }
    public SaveStatus eraseSlot(String part, String slot) {
        SaveStatus valid = validate(part, slot); if (!valid.ok()) return valid;
        SaveResult<Map<String, String>> result = readPart(part, false);
        if (!result.ok()) return new SaveStatus(result.error(), result.message());
        result.data().remove(slot); return stage(part, result.data());
    }
    public SaveStatus resetPart(String part) { SaveStatus valid = validate(part, "default"); return valid.ok() ? stage(part, new LinkedHashMap<>()) : valid; }
    public boolean isPending(String part) { return pending.containsKey(key(part)); }
    public int pendingCount() { return pending.size(); }
    public long commitCount() { return commits; }
    public SaveStatus lastFlushStatus() { return lastFlush; }
    public void setFlushScheduler(Runnable scheduler) { flushScheduler = scheduler; }
    public void requestFlush() { if (!closed && !pending.isEmpty() && !scheduled && flushScheduler != null) { scheduled = true; flushScheduler.run(); } }
    public SaveStatus flushPending() { return flush(false, 0); }
    public SaveStatus flushFrame(long frame) { return flush(true, frame); }
    private SaveStatus flush(boolean perFrame, long frame) {
        if (closed) return new SaveStatus(SaveError.CLOSED, "Save center closed");
        scheduled = false; SaveStatus result = okStatus();
        for (String part : new ArrayList<>(pending.keySet())) {
            if (perFrame && java.util.Objects.equals(lastFrames.get(part), frame)) continue;
            if (perFrame) lastFrames.put(part, frame);
            SaveStatus status = writePart(part, pending.get(part));
            if (status.ok()) { pending.remove(part); lastFrames.put(part, frame); commits++; }
            else result = status;
        }
        lastFlush = result; return result;
    }
    private SaveStatus writePart(String part, Map<String, String> slots) {
        Path temporary = null;
        try {
            byte[] payload = encode(slots).getBytes(StandardCharsets.UTF_8);
            if (payload.length > options.maxFileBytes) return new SaveStatus(SaveError.UNSUPPORTED_DATA, "Partition exceeds limit");
            byte[] header = new byte[41]; System.arraycopy(MAGIC, 0, header, 0, 8);
            header[8] = (byte) (options.encryptionKey.length == 32 ? 1 : 0);
            System.arraycopy(MessageDigest.getInstance("SHA-256").digest(payload), 0, header, 9, 32);
            if (header[8] == 1) {
                byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(options.encryptionKey, "AES"), new GCMParameterSpec(128, nonce)); cipher.updateAAD(header);
                ByteArrayOutputStream body = new ByteArrayOutputStream(); body.write(nonce); body.write(cipher.doFinal(payload)); payload = body.toByteArray();
            }
            Path destination = new FileHandle(partPath(part)).file().toPath().toAbsolutePath();
            Files.createDirectories(destination.getParent()); temporary = Files.createTempFile(destination.getParent(), ".save-", ".tmp");
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.allocate(header.length + payload.length).put(header).put(payload); data.flip();
                while (data.hasRemaining()) output.write(data); output.force(true);
            }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
            return okStatus();
        } catch (Exception error) { return new SaveStatus(SaveError.IO, error.toString()); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (java.io.IOException error) { LogCenter.warn("Save", "Cleanup failed: %s", error); } }
    }
    private static ArrayList<Field> fields(Class<?> type) {
        ArrayList<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) for (Field field : current.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            field.setAccessible(true); fields.add(field);
        }
        return fields;
    }
    private static void checkData(Object value, java.util.IdentityHashMap<Object, Boolean> visiting, int depth) throws IllegalAccessException {
        if (depth > 48) throw new IllegalArgumentException("Save exceeds nesting limit");
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>) return;
        if (value instanceof Number number) { if (!Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("Nonfinite number"); return; }
        if (value instanceof Disposable || value instanceof com.badlogic.gdx.scenes.scene2d.Actor || value instanceof Class<?> || value instanceof Runnable) throw new IllegalArgumentException("Runtime objects cannot be saved");
        if (visiting.put(value, true) != null) throw new IllegalArgumentException("Cyclic save data");
        try {
            if (value instanceof Map<?, ?> map) { for (Map.Entry<?, ?> entry : map.entrySet()) { if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Map keys must be strings"); checkData(entry.getValue(), visiting, depth + 1); } }
            else if (value instanceof Iterable<?> list) { for (Object item : list) checkData(item, visiting, depth + 1); }
            else if (value.getClass().isArray()) { for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) checkData(java.lang.reflect.Array.get(value, i), visiting, depth + 1); }
            else { if (value.getClass().getName().startsWith("java.")) throw new IllegalArgumentException("Unsupported Java object"); for (Field field : fields(value.getClass())) checkData(field.get(value), visiting, depth + 1); }
        } finally { visiting.remove(value); }
    }
    private Map<String, Object> capture(Object target) throws IllegalAccessException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Field field : fields(target.getClass())) result.put(field.getName(), field.get(target));
        return result;
    }
    public SaveStatus saveObject(String part, String slot, Object target) {
        if (target == null) return new SaveStatus(SaveError.INVALID_ARGUMENT, "Null object");
        try { return save(part, slot, Map.of("class", target.getClass().getName(), "properties", capture(target))); }
        catch (Exception error) { return new SaveStatus(SaveError.UNSUPPORTED_DATA, error.toString()); }
    }
    private record Assignment(Object target, Field field, Object value) { }
    private void prepare(Object target, JsonValue properties, ArrayList<Assignment> assignments, int depth) throws Exception {
        if (target == null || !properties.isObject() || depth > 32) throw new IllegalArgumentException("Invalid object schema/defaults");
        for (Field field : fields(target.getClass())) {
            JsonValue value = properties.get(field.getName()); if (value == null) continue;
            Class<?> type = field.getType(); Object initial = field.get(target);
            if (value.isNull()) { if (type.isPrimitive()) throw new IllegalArgumentException("Null primitive"); assignments.add(new Assignment(target, field, null)); continue; }
            if ((type.isPrimitive() && type != boolean.class && type != char.class) || Number.class.isAssignableFrom(type)) { if (!value.isNumber()) throw new IllegalArgumentException("Numeric schema mismatch"); }
            if ((type == boolean.class || type == Boolean.class) && !value.isBoolean()) throw new IllegalArgumentException("Boolean schema mismatch");
            if ((type == String.class || type == char.class || type == Character.class || type.isEnum()) && !value.isString()) throw new IllegalArgumentException("String schema mismatch");
            if (!type.isPrimitive() && !type.getName().startsWith("java.") && !type.isArray() && !type.isEnum()) {
                prepare(initial, value, assignments, depth + 1);
            } else assignments.add(new Assignment(target, field, json.readValue(type, value)));
        }
    }
    private SaveStatus importFields(Object target, JsonValue properties) {
        try {
            ArrayList<Assignment> assignments = new ArrayList<>(); prepare(target, properties, assignments, 0);
            for (Assignment assignment : assignments) assignment.field().set(assignment.target(), assignment.value());
            return okStatus();
        } catch (Exception error) { return new SaveStatus(SaveError.CORRUPT, "Object validation failed: " + error); }
    }
    public SaveStatus loadInto(String part, String slot, Object initial) {
        if (initial == null) return new SaveStatus(SaveError.INVALID_ARGUMENT, "Null defaults");
        SaveResult<JsonValue> result = rawSlot(part, slot); if (!result.ok()) return new SaveStatus(result.error(), result.message());
        if (!result.data().isObject() || !initial.getClass().getName().equals(result.data().getString("class", ""))) return new SaveStatus(SaveError.CORRUPT, "Object class mismatch");
        JsonValue properties = result.data().get("properties");
        return properties == null ? new SaveStatus(SaveError.CORRUPT, "Missing properties") : importFields(initial, properties);
    }
    private SaveResult<JsonValue> rawSlot(String part, String slot) {
        SaveStatus valid = validate(part, slot); if (!valid.ok()) return new SaveResult<>(valid.error(), valid.message(), null, false);
        SaveResult<Map<String, String>> partition = readPart(part, false);
        if (!partition.ok()) return new SaveResult<>(partition.error(), partition.message(), null, false);
        String encoded = partition.data().get(slot);
        return encoded == null ? new SaveResult<>(SaveError.MISSING, "Slot missing", null, false) : new SaveResult<>(SaveError.NONE, "", new JsonReader().parse(encoded), false);
    }
    public SaveStatus saveModel(String part, String slot, SaveModel model) {
        if (model == null || model.saveVersion() < 0) return new SaveStatus(SaveError.INVALID_ARGUMENT, "Invalid model version");
        try { return save(part, slot, Map.of("__version", model.saveVersion(), "fields", capture(model))); }
        catch (Exception error) { return new SaveStatus(SaveError.UNSUPPORTED_DATA, error.toString()); }
    }
    @SuppressWarnings("unchecked")
    public ModelLoadResult loadModel(String part, String slot, SaveModel model) {
        int current = model.saveVersion(); SaveResult<JsonValue> result = rawSlot(part, slot);
        if (!result.ok()) return new ModelLoadResult(result.error(), result.message(), 0, current, false);
        try {
            JsonValue version = result.data().get("__version");
            if (current < 0 || version == null || !version.isLong() || version.asLong() < 0 || version.asLong() > Integer.MAX_VALUE) return new ModelLoadResult(SaveError.CORRUPT, "Invalid version", 0, current, false);
            int saved = version.asInt();
            if (saved > current) return new ModelLoadResult(SaveError.UNSUPPORTED_VERSION, "Save version newer than model", saved, current, false);
            Map<String, Object> values = (Map<String, Object>) tree(result.data().get("fields"));
            for (int step = saved; step < current; step++) {
                SaveStatus status = model.migrateSave(step, values);
                if (!status.ok() || values.containsKey("__version")) return new ModelLoadResult(SaveError.MIGRATION_FAILED, status.message(), saved, current, false);
            }
            checkData(values, new java.util.IdentityHashMap<>(), 0);
            SaveStatus status = importFields(model, new JsonReader().parse(encode(values)));
            return new ModelLoadResult(status.error(), status.message(), saved, current, status.ok() && saved != current);
        } catch (Exception error) { return new ModelLoadResult(SaveError.MIGRATION_FAILED, error.toString(), 0, current, false); }
    }
    private static Object tree(JsonValue value) {
        if (value == null || value.isNull()) return null;
        if (value.isObject()) { Map<String, Object> map = new LinkedHashMap<>(); for (JsonValue child : value) map.put(child.name, tree(child)); return map; }
        if (value.isArray()) { ArrayList<Object> list = new ArrayList<>(); for (JsonValue child : value) list.add(tree(child)); return list; }
        if (value.isBoolean()) return value.asBoolean(); if (value.isLong()) return value.asLong(); if (value.isDouble()) return value.asDouble(); return value.asString();
    }
    /** Plain JSON values, avoiding Json's polymorphic primitive wrappers inside Java Maps. */
    static String encode(Object value) {
        try { return jsonValue(value).toJson(com.badlogic.gdx.utils.JsonWriter.OutputType.json); }
        catch (IllegalAccessException error) { throw new IllegalArgumentException(error); }
    }
    private static JsonValue jsonValue(Object value) throws IllegalAccessException {
        if (value == null) return new JsonValue(JsonValue.ValueType.nullValue);
        if (value instanceof String || value instanceof Character || value instanceof Enum<?>) return new JsonValue(value.toString());
        if (value instanceof Boolean booleanValue) return new JsonValue(booleanValue);
        if (value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal) return new JsonValue(((Number) value).doubleValue());
        if (value instanceof Number number) return new JsonValue(number.longValue());
        JsonValue root;
        if (value instanceof Map<?, ?> map) {
            root = new JsonValue(JsonValue.ValueType.object);
            for (Map.Entry<?, ?> entry : map.entrySet()) root.addChild((String) entry.getKey(), jsonValue(entry.getValue()));
        } else if (value instanceof Iterable<?> list) {
            root = new JsonValue(JsonValue.ValueType.array);
            for (Object child : list) root.addChild(jsonValue(child));
        } else if (value.getClass().isArray()) {
            root = new JsonValue(JsonValue.ValueType.array);
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) root.addChild(jsonValue(java.lang.reflect.Array.get(value, i)));
        } else {
            root = new JsonValue(JsonValue.ValueType.object);
            for (Field field : fields(value.getClass())) root.addChild(field.getName(), jsonValue(field.get(value)));
        }
        return root;
    }
    public <T> void save(String slot, T value) { SaveStatus status = save("default", slot, value); if (!status.ok()) throw new IllegalStateException(status.message()); }
    public <T> T load(String slot, Class<T> type, T fallback) { return load("default", slot, type, fallback).data(); }
    public boolean contains(String slot) { return hasSlot("default", slot) || (legacy != null && legacy.contains(slot)); }
    public Set<String> slots() { return listSlots("default"); }
    public void delete(String slot) { if (hasSlot("default", slot)) eraseSlot("default", slot); if (legacy != null) { legacy.remove(slot); legacy.flush(); } }
    public boolean isClosed() { return closed; }
    public SaveStatus shutdown() { if (closed) return lastFlush; SaveStatus status = flushPending(); closed = true; flushScheduler = null; return status; }
    public void dispose() { SaveStatus status = shutdown(); if (!status.ok()) LogCenter.error("Save", "Flush failed: %s", status.message()); }
}
