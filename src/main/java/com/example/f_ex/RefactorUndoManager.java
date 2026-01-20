package com.example.f_ex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

final class RefactorUndoManager {
    private Map<Path, String> lastBackup = null;

    void rememberBackup(RefactorRenameService.RenamePlan plan) {
        if (plan == null || plan.changes == null || plan.changes.isEmpty()) {
            lastBackup = null;
            return;
        }
        LinkedHashMap<Path, String> b = new LinkedHashMap<>();
        for (RefactorRenameService.FileChange ch : plan.changes) {
            if (ch == null || ch.file == null) continue;
            b.put(ch.file, ch.before);
        }
        lastBackup = b.isEmpty() ? null : b;
    }

    boolean canUndo() {
        return lastBackup != null && !lastBackup.isEmpty();
    }

    int undo() {
        if (!canUndo()) return 0;
        int restored = 0;
        for (Map.Entry<Path, String> e : lastBackup.entrySet()) {
            try {
                Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                restored++;
            } catch (Exception ignored) {
            }
        }
        lastBackup = null;
        return restored;
    }
}

