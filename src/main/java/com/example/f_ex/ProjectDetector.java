package com.example.f_ex;

import java.io.IOException;
import java.nio.file.*;
import java.util.Set;

public class ProjectDetector {
    public enum ProjectType {
        GRADLE, MAVEN, JAVA, INTELLIJ_IDEA, UNKNOWN
    }
    
    private static final Set<String> HIDDEN_DIRS = Set.of(
        "build", ".gradle", ".idea", ".git", "out", "bin", 
        ".vscode", "node_modules", ".classpath", ".project"
    );
    
    public static ProjectType detectProjectType(Path root) {
        // Проверяем Gradle
        if (Files.exists(root.resolve("gradlew.bat")) || 
            Files.exists(root.resolve("gradlew")) ||
            Files.exists(root.resolve("build.gradle")) ||
            Files.exists(root.resolve("build.gradle.kts"))) {
            return ProjectType.GRADLE;
        }
        
        // Проверяем Maven
        if (Files.exists(root.resolve("pom.xml"))) {
            return ProjectType.MAVEN;
        }
        
        // Проверяем, может быть в подпапках есть Maven/Gradle
        if (findMavenProject(root) != null) {
            return ProjectType.MAVEN;
        }
        if (findGradleProject(root) != null) {
            return ProjectType.GRADLE;
        }
        
        // Проверяем IntelliJ IDEA проект
        if (isIntelliJIDEAProject(root)) {
            return ProjectType.INTELLIJ_IDEA;
        }
        
        // Проверяем простой Java проект - ищем .java файлы в любых местах
        if (hasJavaFiles(root)) {
            return ProjectType.JAVA;
        }
        
        return ProjectType.UNKNOWN;
    }
    
    private static boolean isIntelliJIDEAProject(Path root) {
        // Проверяем наличие .idea папки или .iml файлов
        if (Files.exists(root.resolve(".idea"))) {
            return true;
        }
        
        // Ищем .iml файлы в корне или в подпапках (но не глубже 2 уровней)
        try {
            return Files.walk(root, 2)
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.toString().endsWith(".iml"));
        } catch (IOException e) {
            return false;
        }
    }
    
    private static boolean hasJavaFiles(Path root) {
        try {
            // Проверяем стандартные места
            Path[] commonPaths = {
                root.resolve("src").resolve("main").resolve("java"),
                root.resolve("src"),
                root.resolve("src").resolve("java"),
                root
            };
            
            for (Path path : commonPaths) {
                if (Files.isDirectory(path)) {
                    if (hasJavaFilesRecursive(path, 3)) {
                        return true;
                    }
                }
            }
            
            // Также проверяем корень на наличие .java файлов
            try (var stream = Files.newDirectoryStream(root)) {
                for (Path child : stream) {
                    if (Files.isRegularFile(child) && child.toString().endsWith(".java")) {
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }
    
    private static boolean hasJavaFilesRecursive(Path dir, int maxDepth) {
        if (maxDepth <= 0) return false;
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isRegularFile(child) && child.toString().endsWith(".java")) {
                    return true;
                }
                if (Files.isDirectory(child) && !shouldHidePath(child)) {
                    if (hasJavaFilesRecursive(child, maxDepth - 1)) {
                        return true;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }
    
    private static boolean shouldHidePath(Path path) {
        if (path == null) return true;
        String name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        return HIDDEN_DIRS.contains(name) || name.startsWith(".");
    }
    
    public static Path findGradleProject(Path startDir) {
        return findProjectRecursive(startDir, 3, path -> {
            Path gradlew = path.resolve("gradlew.bat");
            Path gradlewUnix = path.resolve("gradlew");
            Path buildGradle = path.resolve("build.gradle");
            Path buildGradleKts = path.resolve("build.gradle.kts");
            return Files.exists(gradlew) || Files.exists(gradlewUnix) ||
                   Files.exists(buildGradle) || Files.exists(buildGradleKts);
        });
    }
    
    public static Path findMavenProject(Path startDir) {
        return findProjectRecursive(startDir, 3, path -> Files.exists(path.resolve("pom.xml")));
    }
    
    private static Path findProjectRecursive(Path startDir, int maxDepth, java.util.function.Predicate<Path> checker) {
        if (maxDepth <= 0) return null;
        
        try (var stream = Files.newDirectoryStream(startDir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                if (shouldHidePath(child)) continue;
                
                // Проверяем текущую папку
                if (checker.test(child)) {
                    return child;
                }
                
                // Рекурсивно ищем глубже
                Path found = findProjectRecursive(child, maxDepth - 1, checker);
                if (found != null) {
                    return found;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }
    
    public static Path findJavaProject(Path startDir) {
        try (var stream = Files.newDirectoryStream(startDir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child)) continue;
                if (shouldHidePath(child)) continue;
                
                Path javaSrc = child.resolve("src").resolve("main").resolve("java");
                if (Files.isDirectory(javaSrc)) {
                    return child;
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
