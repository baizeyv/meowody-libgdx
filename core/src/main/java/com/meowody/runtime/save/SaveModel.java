package com.meowody.runtime.save;

import java.util.Map;

public interface SaveModel {
    int saveVersion();
    default SaveStatus migrateSave(int fromVersion, Map<String, Object> fields) { return new SaveStatus(SaveError.MIGRATION_FAILED, "No migration from " + fromVersion); }
}
