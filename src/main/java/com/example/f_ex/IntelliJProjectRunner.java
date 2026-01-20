package com.example.f_ex;

import javafx.application.Platform;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class IntelliJProjectRunner {
    private final Consumer<String> logCallback;
    private final Runnable statusUpdate;
    private final java.util.function.Consumer<Process> processCallback;
    
    public IntelliJProjectRunner(Consumer<String> logCallback, Runnable statusUpdate) {
        this(logCallback, statusUpdate, null);
    }
    
    public IntelliJProjectRunner(Consumer<String> logCallback, Runnable statusUpdate, java.util.function.Consumer<Process> processCallback) {
        this.logCallback = logCallback;
        this.statusUpdate = statusUpdate;
        this.processCallback = processCallback;
    }
    
    public void run(Path projectRoot) {
        // Ищем библиотеки в .idea/libraries
        List<Path> libraries = findLibraries(projectRoot);
        
        // Ищем главный класс
        Path mainClass = findMainClassAnywhere(projectRoot);
        if (mainClass == null) {
            logCallback.accept("No main class found. Looking for class with 'public static void main(String[] args)'");
            return;
        }
        
        Path sourceRoot = findSourceRoot(projectRoot, mainClass);
        boolean usesJavaFX = checkUsesJavaFX(projectRoot, sourceRoot);
        
        compileAndRun(projectRoot, sourceRoot, mainClass, usesJavaFX, libraries, false);
    }
    
    public void runFile(Path projectRoot, Path javaFile) {
        // Ищем библиотеки в .idea/libraries
        List<Path> libraries = findLibraries(projectRoot);
        
        Path sourceRoot = findSourceRoot(projectRoot, javaFile);
        boolean usesJavaFX = checkUsesJavaFX(projectRoot, sourceRoot);
        
        compileAndRun(projectRoot, sourceRoot, javaFile, usesJavaFX, libraries, false);
    }

    public void debugFile(Path projectRoot, Path javaFile) {
        List<Path> libraries = findLibraries(projectRoot);
        Path sourceRoot = findSourceRoot(projectRoot, javaFile);
        boolean usesJavaFX = checkUsesJavaFX(projectRoot, sourceRoot);
        compileAndRun(projectRoot, sourceRoot, javaFile, usesJavaFX, libraries, true);
    }
    
    private List<Path> findLibraries(Path projectRoot) {
        List<Path> libs = new ArrayList<>();
        Path librariesDir = projectRoot.resolve(".idea").resolve("libraries");
        
        if (!Files.isDirectory(librariesDir)) {
            logCallback.accept("No .idea/libraries folder found. Trying to find libraries in .iml files...");
            return findLibrariesFromIml(projectRoot);
        }
        
        try {
            Files.walk(librariesDir, 1)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".xml"))
                    .forEach(xmlFile -> {
                        try {
                            String content = Files.readString(xmlFile, StandardCharsets.UTF_8);
                            // Ищем пути к jar файлам в XML
                            Pattern jarPattern = Pattern.compile("jar://([^!]+)!\\?");
                            Matcher matcher = jarPattern.matcher(content);
                            while (matcher.find()) {
                                String jarPath = matcher.group(1);
                                Path jar = Paths.get(jarPath);
                                if (Files.exists(jar)) {
                                    libs.add(jar);
                                }
                            }
                        } catch (IOException e) {
                            // Игнорируем ошибки чтения
                        }
                    });
        } catch (IOException e) {
            logCallback.accept("Failed to read libraries: " + e.getMessage());
        }
        
        if (libs.isEmpty()) {
            logCallback.accept("No libraries found in .idea/libraries. Trying .iml files...");
            return findLibrariesFromIml(projectRoot);
        }
        
        return libs;
    }
    
    private List<Path> findLibrariesFromIml(Path projectRoot) {
        List<Path> libs = new ArrayList<>();
        try {
            Files.walk(projectRoot, 3)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".iml"))
                    .forEach(imlFile -> {
                        try {
                            String content = Files.readString(imlFile, StandardCharsets.UTF_8);
                            // Ищем пути к jar файлам в .iml
                            Pattern jarPattern = Pattern.compile("jar://([^!]+)!\\?");
                            Matcher matcher = jarPattern.matcher(content);
                            while (matcher.find()) {
                                String jarPath = matcher.group(1);
                                Path jar = Paths.get(jarPath);
                                if (Files.exists(jar)) {
                                    libs.add(jar);
                                }
                            }
                        } catch (IOException e) {
                            // Игнорируем ошибки чтения
                        }
                    });
        } catch (IOException e) {
            logCallback.accept("Failed to read .iml files: " + e.getMessage());
        }
        return libs;
    }
    
    private static boolean checkUsesJavaFX(Path projectRoot, Path sourceRoot) {
        try {
            return Files.walk(sourceRoot, 10)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .anyMatch(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            return content.contains("javafx.") || 
                                   content.contains("import javafx");
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }
    
    private static Path findMainClassAnywhere(Path root) {
        try {
            return Files.walk(root, 10)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            return content.contains("public static void main") && 
                                   (content.contains("String[] args") || content.contains("String args"));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
    
    private static Path findSourceRoot(Path projectRoot, Path mainClass) {
        Path[] candidates = {
            projectRoot.resolve("src").resolve("main").resolve("java"),
            projectRoot.resolve("src"),
            projectRoot.resolve("src").resolve("java"),
            mainClass.getParent()
        };
        
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate) && mainClass.startsWith(candidate)) {
                return candidate;
            }
        }
        
        return mainClass.getParent();
    }
    
    private void compileAndRun(Path projectRoot, Path sourceRoot, Path mainClass,
                               boolean usesJavaFX, List<Path> libraries, boolean debug) {
        Path buildDir = projectRoot.resolve("build");
        Path classesDir = buildDir.resolve("classes");
        
        try {
            Files.createDirectories(classesDir);
        } catch (IOException e) {
            logCallback.accept("Failed to create build directory: " + e.getMessage());
            return;
        }
        
        Path relativePath = sourceRoot.relativize(mainClass);
        String className = relativePath.toString()
                .replace(File.separator, ".")
                .replace(".java", "");
        
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String javacCmd = isWindows ? "javac.exe" : "javac";
        String javaCmd = isWindows ? "java.exe" : "java";
        
        List<Path> javaFiles = new ArrayList<>();
        try {
            Files.walk(sourceRoot, 10)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .forEach(javaFiles::add);
        } catch (IOException e) {
            logCallback.accept("Failed to find Java files: " + e.getMessage());
            return;
        }
        
        if (javaFiles.isEmpty()) {
            logCallback.accept("No Java files found in: " + sourceRoot);
            return;
        }
        
        // Формируем classpath из библиотек
        List<String> classpath = new ArrayList<>();
        classpath.add(classesDir.toString());
        for (Path lib : libraries) {
            classpath.add(lib.toString());
        }
        String classpathStr = String.join(File.pathSeparator, classpath);
        
        List<String> compileCmd = new ArrayList<>();
        compileCmd.add(javacCmd);
        compileCmd.add("-encoding");
        compileCmd.add("UTF-8");
        compileCmd.add("-d");
        compileCmd.add(classesDir.toString());
        compileCmd.add("-sourcepath");
        compileCmd.add(sourceRoot.toString());
        compileCmd.add("-cp");
        compileCmd.add(classpathStr);
        
        // Если использует JavaFX, добавляем модули
        if (usesJavaFX) {
            String javafxPath = findJavaFXPath();
            if (javafxPath != null) {
                compileCmd.add("--module-path");
                compileCmd.add(javafxPath);
                compileCmd.add("--add-modules");
                compileCmd.add("javafx.controls,javafx.fxml");
            } else {
                logCallback.accept("Warning: JavaFX detected but not found in system.");
                logCallback.accept("Trying to find JavaFX in libraries...");
            }
        }
        
        javaFiles.forEach(p -> compileCmd.add(p.toString()));
        
        logCallback.accept("Found " + libraries.size() + " libraries from IntelliJ IDEA configuration");
        logCallback.accept("$ " + String.join(" ", compileCmd));
        statusUpdate.run();
        
        runCommand(compileCmd, projectRoot, () -> {
            List<String> runCmd = new ArrayList<>();
            runCmd.add(javaCmd);
            
            // Устанавливаем UTF-8 кодировку для правильного отображения русских букв
            runCmd.add("-Dfile.encoding=UTF-8");
            runCmd.add("-Dconsole.encoding=UTF-8");

            if (debug) {
                runCmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005");
            }
            
            runCmd.add("-cp");
            runCmd.add(classpathStr);
            
            // Если использует JavaFX, добавляем модули при запуске
            if (usesJavaFX) {
                String javafxPath = findJavaFXPath();
                if (javafxPath != null) {
                    runCmd.add("--module-path");
                    runCmd.add(javafxPath);
                    runCmd.add("--add-modules");
                    runCmd.add("javafx.controls,javafx.fxml");
                }
            }
            
            runCmd.add(className);
            
            logCallback.accept("$ " + String.join(" ", runCmd));
            if (debug) {
                logCallback.accept("[DEBUG] Waiting for debugger on port 5005 (suspend=y)");
            }
            runCommand(runCmd, projectRoot, null, processCallback);
        });
    }
    
    private static String findJavaFXPath() {
        // Проверяем переменную окружения
        String javafxHome = System.getenv("JAVA_FX_HOME");
        if (javafxHome != null) {
            Path libPath = Paths.get(javafxHome).resolve("lib");
            if (Files.isDirectory(libPath) && Files.exists(libPath.resolve("javafx.base.jar"))) {
                return libPath.toString();
            }
        }
        
        // Ищем JavaFX в стандартных местах
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path[] candidates = {
                Paths.get(javaHome).resolve("lib").resolve("javafx-sdk").resolve("lib"),
                Paths.get(javaHome).getParent().resolve("javafx-sdk").resolve("lib"),
                Paths.get(System.getProperty("user.home")).resolve("javafx-sdk").resolve("lib"),
                Paths.get("C:\\Program Files\\Java\\javafx-sdk").resolve("lib"),
                Paths.get("C:\\Java\\javafx-sdk").resolve("lib"),
                Paths.get("C:\\Program Files\\Eclipse Adoptium\\javafx-sdk").resolve("lib")
            };
            
            for (Path libPath : candidates) {
                if (Files.isDirectory(libPath) && Files.exists(libPath.resolve("javafx.base.jar"))) {
                    return libPath.toString();
                }
            }
        }
        
        return null;
    }
    
    private void runCommand(List<String> command, Path directory, Runnable onSuccess) {
        runCommand(command, directory, onSuccess, null);
    }
    
    private void runCommand(List<String> command, Path directory, Runnable onSuccess, java.util.function.Consumer<Process> processCallback) {
        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(directory.toFile());
                pb.redirectErrorStream(true);
                
                // Устанавливаем UTF-8 кодировку для процесса (Windows)
                Map<String, String> env = pb.environment();
                env.put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dconsole.encoding=UTF-8");
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    env.put("PYTHONIOENCODING", "utf-8");
                }
                
                Process p = pb.start();
                
                // Сохраняем процесс для интерактивного ввода
                if (processCallback != null) {
                    Platform.runLater(() -> processCallback.accept(p));
                }
                
                // Читаем вывод в UTF-8
                try (var reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String finalLine = line;
                        Platform.runLater(() -> logCallback.accept(finalLine));
                    }
                }
                
                int code = p.waitFor();
                Platform.runLater(() -> {
                    logCallback.accept("Process finished with exit code: " + code);
                    if (code == 0 && onSuccess != null) {
                        onSuccess.run();
                    }
                    if (processCallback != null) {
                        processCallback.accept(null); // Очищаем процесс
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    logCallback.accept("Failed: " + e.getMessage());
                    if (processCallback != null) {
                        processCallback.accept(null); // Очищаем процесс
                    }
                });
            }
        }, "intellij-project-runner");
        t.setDaemon(true);
        t.start();
    }
}
