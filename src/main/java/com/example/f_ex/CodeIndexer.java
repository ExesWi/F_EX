package com.example.f_ex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Индексатор кода для быстрого поиска классов, методов, переменных
 */
public class CodeIndexer {
    private final Path projectRoot;
    private final Map<String, List<CodeElement>> index = new ConcurrentHashMap<>();
    private static final Set<String> HIDDEN_DIRS = Set.of(
        "build", ".gradle", ".idea", ".git", "out", "bin", 
        ".vscode", "node_modules", ".classpath", ".project"
    );
    
    // Паттерны для поиска элементов кода (упрощенные)
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "\\b(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?class\\s+(\\w+)"
    );
    private static final Pattern INTERFACE_PATTERN = Pattern.compile(
        "\\b(?:public\\s+)?interface\\s+(\\w+)"
    );
    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "\\b(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?(?:\\w+\\s+)*(\\w+)\\s*\\([^)]*\\)"
    );
    
    public CodeIndexer(Path projectRoot) {
        this.projectRoot = projectRoot;
    }
    
    public void indexProject() {
        index.clear();
        if (projectRoot == null || !Files.isDirectory(projectRoot)) {
            return;
        }
        
        try {
            Files.walk(projectRoot, 20)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !shouldHidePath(p.getParent()))
                    .forEach(this::indexFile);
        } catch (IOException e) {
            // Игнорируем ошибки индексации
        }
    }
    
    private void indexFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String packageName = extractPackage(content);
            
            // Индексируем классы
            Matcher classMatcher = CLASS_PATTERN.matcher(content);
            while (classMatcher.find()) {
                String className = classMatcher.group(1);
                addToIndex(className, CodeElementType.CLASS, file, packageName);
            }
            
            // Индексируем интерфейсы
            Matcher interfaceMatcher = INTERFACE_PATTERN.matcher(content);
            while (interfaceMatcher.find()) {
                String interfaceName = interfaceMatcher.group(1);
                addToIndex(interfaceName, CodeElementType.INTERFACE, file, packageName);
            }
            
            // Индексируем методы (упрощенная версия)
            Matcher methodMatcher = METHOD_PATTERN.matcher(content);
            while (methodMatcher.find()) {
                String methodName = methodMatcher.group(methodMatcher.groupCount());
                if (methodName != null && !methodName.equals("class") && !methodName.equals("interface")) {
                    addToIndex(methodName, CodeElementType.METHOD, file, packageName);
                }
            }
            
        } catch (IOException e) {
            // Игнорируем ошибки чтения
        }
    }
    
    private String extractPackage(String content) {
        Pattern pkgPattern = Pattern.compile("package\\s+([\\w.]+);");
        Matcher m = pkgPattern.matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }
    
    private void addToIndex(String name, CodeElementType type, Path file, String packageName) {
        index.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>())
                .add(new CodeElement(name, type, file, packageName));
    }
    
    public List<CodeElement> findCompletions(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>();
        }
        
        String lowerPrefix = prefix.toLowerCase();
        List<CodeElement> results = new ArrayList<>();
        
        // Ищем точные совпадения сначала
        for (Map.Entry<String, List<CodeElement>> entry : index.entrySet()) {
            if (entry.getKey().startsWith(lowerPrefix)) {
                results.addAll(entry.getValue());
            }
        }
        
        // Сортируем по типу и имени
        results.sort((a, b) -> {
            int typeCompare = a.getType().compareTo(b.getType());
            if (typeCompare != 0) return typeCompare;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        
        return results;
    }
    
    public static boolean shouldHidePath(Path path) {
        if (path == null) return true;
        String name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        return HIDDEN_DIRS.contains(name) || name.startsWith(".");
    }
    
    public enum CodeElementType {
        CLASS, INTERFACE, METHOD, VARIABLE
    }
    
    public static class CodeElement {
        private final String name;
        private final CodeElementType type;
        private final Path file;
        private final String packageName;
        
        public CodeElement(String name, CodeElementType type, Path file, String packageName) {
            this.name = name;
            this.type = type;
            this.file = file;
            this.packageName = packageName;
        }
        
        public String getName() { return name; }
        public CodeElementType getType() { return type; }
        public Path getFile() { return file; }
        public String getPackageName() { return packageName; }
        
        public String getDisplayName() {
            String typeIcon = switch (type) {
                case CLASS -> "C";
                case INTERFACE -> "I";
                case METHOD -> "M";
                case VARIABLE -> "V";
            };
            if (packageName != null && !packageName.isEmpty()) {
                return typeIcon + " " + name + " (" + packageName + ")";
            }
            return typeIcon + " " + name;
        }
    }
}
