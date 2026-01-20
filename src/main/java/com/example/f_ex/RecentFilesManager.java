package com.example.f_ex;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class RecentFilesManager {
    private final int limit;
    private final LinkedHashSet<Path> recent = new LinkedHashSet<>();

    RecentFilesManager(int limit) {
        this.limit = Math.max(5, limit);
    }

    void markOpened(Path file) {
        if (file == null) return;
        recent.remove(file);
        recent.add(file);
        while (recent.size() > limit) {
            Path first = recent.iterator().next();
            recent.remove(first);
        }
    }

    List<Path> list() {
        List<Path> out = new ArrayList<>(recent);
        java.util.Collections.reverse(out);
        return out;
    }
}

