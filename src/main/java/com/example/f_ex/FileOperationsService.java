package com.example.f_ex;

import java.nio.file.Files;
import java.nio.file.Path;

final class FileOperationsService {
    Path renameFile(Path file, String newName) throws Exception {
        if (file == null) throw new IllegalArgumentException("file");
        if (newName == null) throw new IllegalArgumentException("newName");
        String name = newName.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Empty name");
        if (name.contains("/") || name.contains("\\") || name.contains(":")) throw new IllegalArgumentException("Invalid name");

        Path parent = file.getParent();
        if (parent == null) throw new IllegalArgumentException("No parent");
        Path target = parent.resolve(name);
        if (Files.exists(target)) throw new IllegalArgumentException("Target exists");
        return Files.move(file, target);
    }
}

