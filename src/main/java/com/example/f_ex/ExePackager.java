package com.example.f_ex;

import javafx.application.Platform;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class ExePackager {
    private final Consumer<String> log;
    private final Consumer<String> statusUpdate;

    ExePackager(Consumer<String> log, Consumer<String> statusUpdate) {
        this.log = log;
        this.statusUpdate = statusUpdate;
    }

    void packageAsExe(Path projectRoot, ProjectDetector.ProjectType type) {
        if (projectRoot == null) {
            log.accept("No project root set");
            return;
        }

        if (type == ProjectDetector.ProjectType.GRADLE) {
            packageGradleAsExe(projectRoot);
        } else if (type == ProjectDetector.ProjectType.MAVEN) {
            packageMavenAsExe(projectRoot);
        } else {
            log.accept("EXE packaging supported only for Gradle/Maven projects");
        }
    }

    private void packageGradleAsExe(Path projectRoot) {
        log.accept("Building fat JAR with all dependencies...");
        if (!buildFatJar(projectRoot)) {
            log.accept("Fat JAR build failed. Trying regular JAR...");
            if (!buildProject(projectRoot, ProjectDetector.ProjectType.GRADLE)) {
                log.accept("Build failed. Cannot create EXE.");
                return;
            }
        }

        Path jarFile = findFatJar(projectRoot);
        if (jarFile == null) {
            jarFile = findJarFile(projectRoot, ProjectDetector.ProjectType.GRADLE);
        }
        if (jarFile == null) {
            log.accept("JAR file not found after build.");
            return;
        }

        String mainClass = findMainClass(projectRoot, ProjectDetector.ProjectType.GRADLE);
        if (mainClass == null || mainClass.isBlank()) {
            log.accept("Main class not found. Cannot create EXE.");
            return;
        }

        String appName = projectRoot.getFileName() != null ? 
            projectRoot.getFileName().toString() : "app";
        Path outputDir = projectRoot.resolve("dist");

        log.accept("Packaging as EXE: " + jarFile);
        log.accept("Main class: " + mainClass);
        log.accept("Output: " + outputDir);

        runJpackageWithFatJar(jarFile, appName, outputDir, mainClass);
    }

    private void packageMavenAsExe(Path projectRoot) {
        Path jarFile = findJarFile(projectRoot, ProjectDetector.ProjectType.MAVEN);
        if (jarFile == null) {
            log.accept("JAR file not found. Building project first...");
            if (!buildProject(projectRoot, ProjectDetector.ProjectType.MAVEN)) {
                log.accept("Build failed. Cannot create EXE.");
                return;
            }
            jarFile = findJarFile(projectRoot, ProjectDetector.ProjectType.MAVEN);
            if (jarFile == null) {
                log.accept("JAR file still not found after build.");
                return;
            }
        }

        String mainClass = findMainClass(projectRoot, ProjectDetector.ProjectType.MAVEN);
        if (mainClass == null || mainClass.isBlank()) {
            log.accept("Main class not found. Cannot create EXE.");
            return;
        }

        String appName = projectRoot.getFileName() != null ? 
            projectRoot.getFileName().toString() : "app";
        Path outputDir = projectRoot.resolve("dist");

        log.accept("Packaging as EXE: " + jarFile);
        log.accept("Main class: " + mainClass);
        log.accept("Output: " + outputDir);

        runJpackage(jarFile, appName, outputDir, mainClass);
    }

    private boolean buildFatJar(Path projectRoot) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();
        Path gradlewBat = projectRoot.resolve("gradlew.bat");
        Path gradlew = projectRoot.resolve("gradlew");
        if (isWindows && Files.exists(gradlewBat)) {
            cmd.addAll(List.of("cmd.exe", "/c", gradlewBat.toString(), "shadowJar"));
        } else if (Files.exists(gradlew)) {
            cmd.addAll(List.of(gradlew.toString(), "shadowJar"));
        } else {
            return false;
        }

        log.accept("$ " + String.join(" ", cmd));
        statusUpdate.accept("Building fat JAR...");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String l = line;
                    Platform.runLater(() -> log.accept(l));
                }
            }

            int code = p.waitFor();
            Platform.runLater(() -> {
                log.accept("Fat JAR build finished with exit code: " + code);
                statusUpdate.accept(code == 0 ? "Fat JAR build completed" : "Fat JAR build failed");
            });
            return code == 0;
        } catch (Exception e) {
            Platform.runLater(() -> log.accept("Fat JAR build failed: " + e.getMessage()));
            return false;
        }
    }

    private Path findFatJar(Path projectRoot) {
        Path libs = projectRoot.resolve("build").resolve("libs");
        if (Files.isDirectory(libs)) {
            try {
                return Files.list(libs)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".jar") && 
                               (name.contains("all") || name.contains("fat") || name.contains("uber") || 
                                name.contains("shadow") || name.contains("-all"));
                    })
                    .findFirst()
                    .orElse(null);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Path findJarFile(Path projectRoot, ProjectDetector.ProjectType type) {
        if (type == ProjectDetector.ProjectType.GRADLE) {
            Path libs = projectRoot.resolve("build").resolve("libs");
            if (Files.isDirectory(libs)) {
                try {
                    return Files.list(libs)
                        .filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-sources.jar"))
                        .findFirst()
                        .orElse(null);
                } catch (Exception ignored) {}
            }
        } else if (type == ProjectDetector.ProjectType.MAVEN) {
            Path target = projectRoot.resolve("target");
            if (Files.isDirectory(target)) {
                try {
                    return Files.list(target)
                        .filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-sources.jar") && !p.toString().endsWith("-javadoc.jar"))
                        .findFirst()
                        .orElse(null);
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private boolean buildProject(Path projectRoot, ProjectDetector.ProjectType type) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();

        if (type == ProjectDetector.ProjectType.GRADLE) {
            Path gradlewBat = projectRoot.resolve("gradlew.bat");
            Path gradlew = projectRoot.resolve("gradlew");
            if (isWindows && Files.exists(gradlewBat)) {
                cmd.addAll(List.of("cmd.exe", "/c", gradlewBat.toString(), "build"));
            } else if (Files.exists(gradlew)) {
                cmd.addAll(List.of(gradlew.toString(), "build"));
            } else {
                log.accept("Gradle wrapper not found");
                return false;
            }
        } else if (type == ProjectDetector.ProjectType.MAVEN) {
            Path mvnwBat = projectRoot.resolve("mvnw.cmd");
            Path mvnw = projectRoot.resolve("mvnw");
            if (isWindows && Files.exists(mvnwBat)) {
                cmd.addAll(List.of("cmd.exe", "/c", mvnwBat.toString(), "package"));
            } else if (Files.exists(mvnw)) {
                cmd.addAll(List.of(mvnw.toString(), "package"));
            } else if (isWindows) {
                cmd.addAll(List.of("cmd.exe", "/c", "mvn.cmd", "package"));
            } else {
                cmd.addAll(List.of("mvn", "package"));
            }
        } else {
            log.accept("Cannot build: unsupported project type");
            return false;
        }

        log.accept("$ " + String.join(" ", cmd));
        statusUpdate.accept("Building project...");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String l = line;
                    Platform.runLater(() -> log.accept(l));
                }
            }

            int code = p.waitFor();
            Platform.runLater(() -> {
                log.accept("Build finished with exit code: " + code);
                statusUpdate.accept(code == 0 ? "Build completed" : "Build failed");
            });
            return code == 0;
        } catch (Exception e) {
            Platform.runLater(() -> log.accept("Build failed: " + e.getMessage()));
            return false;
        }
    }

    private String findMainClass(Path projectRoot, ProjectDetector.ProjectType type) {
        if (type == ProjectDetector.ProjectType.GRADLE) {
            Path buildGradle = projectRoot.resolve("build.gradle");
            Path buildGradleKts = projectRoot.resolve("build.gradle.kts");
            try {
                Path file = Files.exists(buildGradleKts) ? buildGradleKts : buildGradle;
                if (Files.exists(file)) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile("mainClass\\.set\\(\"([^\"]+)\"\\)");
                    java.util.regex.Matcher m = p.matcher(content);
                    if (m.find()) return m.group(1);
                    p = java.util.regex.Pattern.compile("mainClass\\s*=\\s*\"([^\"]+)\"");
                    m = p.matcher(content);
                    if (m.find()) return m.group(1);
                }
            } catch (Exception ignored) {}
        } else if (type == ProjectDetector.ProjectType.MAVEN) {
            Path pom = projectRoot.resolve("pom.xml");
            try {
                if (Files.exists(pom)) {
                    String content = Files.readString(pom, StandardCharsets.UTF_8);
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile("<mainClass>([^<]+)</mainClass>");
                    java.util.regex.Matcher m = p.matcher(content);
                    if (m.find()) return m.group(1).trim();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void runJpackageWithRuntime(Path runtimeImage, String appName, Path outputDir, String mainClass) {
        String javaHome = System.getProperty("java.home");
        Path jpackageExe = Path.of(javaHome).resolve("bin").resolve("jpackage.exe");
        if (!Files.exists(jpackageExe)) {
            jpackageExe = Path.of(javaHome).resolve("bin").resolve("jpackage");
        }
        if (!Files.exists(jpackageExe)) {
            log.accept("jpackage not found in: " + javaHome);
            log.accept("Make sure you're using JDK 17+ with jpackage");
            return;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();
        if (isWindows) {
            cmd.addAll(List.of("cmd.exe", "/c", jpackageExe.toString()));
        } else {
            cmd.add(jpackageExe.toString());
        }

        String moduleName = "com.example.f_ex";
        cmd.addAll(List.of(
            "--name", appName,
            "--module", moduleName + "/" + mainClass,
            "--runtime-image", runtimeImage.toString(),
            "--type", "app-image",
            "--dest", outputDir.toString(),
            "--win-console"
        ));

        log.accept("$ " + String.join(" ", cmd));
        statusUpdate.accept("Running jpackage...");

        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(runtimeImage.getParent().toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();

                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String l = line;
                        Platform.runLater(() -> log.accept(l));
                    }
                }

                int code = p.waitFor();
                Platform.runLater(() -> {
                    if (code == 0) {
                        Path exe = outputDir.resolve(appName).resolve(appName + ".exe");
                        if (Files.exists(exe)) {
                            log.accept("EXE created: " + exe);
                        } else {
                            log.accept("Package created in: " + outputDir.resolve(appName));
                        }
                        statusUpdate.accept("EXE packaging completed");
                    } else {
                        log.accept("jpackage failed with exit code: " + code);
                        statusUpdate.accept("EXE packaging failed");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    log.accept("jpackage failed: " + e.getMessage());
                    statusUpdate.accept("EXE packaging failed");
                });
            }
        }, "jpackage-runner");
        t.setDaemon(true);
        t.start();
    }

    private void runJpackageWithFatJar(Path jarFile, String appName, Path outputDir, String mainClass) {
        String javaHome = System.getProperty("java.home");
        Path jpackageExe = Path.of(javaHome).resolve("bin").resolve("jpackage.exe");
        if (!Files.exists(jpackageExe)) {
            jpackageExe = Path.of(javaHome).resolve("bin").resolve("jpackage");
        }
        if (!Files.exists(jpackageExe)) {
            log.accept("jpackage not found in: " + javaHome);
            log.accept("Make sure you're using JDK 17+ with jpackage");
            return;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();
        if (isWindows) {
            cmd.addAll(List.of("cmd.exe", "/c", jpackageExe.toString()));
        } else {
            cmd.add(jpackageExe.toString());
        }

        cmd.addAll(List.of(
            "--input", jarFile.getParent().toString(),
            "--name", appName,
            "--main-jar", jarFile.getFileName().toString(),
            "--main-class", mainClass,
            "--type", "app-image",
            "--dest", outputDir.toString(),
            "--win-console",
            "--java-options", "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--java-options", "--add-opens=java.base/java.util=ALL-UNNAMED"
        ));

        log.accept("$ " + String.join(" ", cmd));
        statusUpdate.accept("Running jpackage...");

        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(jarFile.getParent().toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();

                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String l = line;
                        Platform.runLater(() -> log.accept(l));
                    }
                }

                int code = p.waitFor();
                Platform.runLater(() -> {
                    if (code == 0) {
                        Path exe = outputDir.resolve(appName).resolve(appName + ".exe");
                        if (Files.exists(exe)) {
                            log.accept("EXE created: " + exe);
                        } else {
                            log.accept("Package created in: " + outputDir.resolve(appName));
                        }
                        statusUpdate.accept("EXE packaging completed");
                    } else {
                        log.accept("jpackage failed with exit code: " + code);
                        statusUpdate.accept("EXE packaging failed");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    log.accept("jpackage failed: " + e.getMessage());
                    statusUpdate.accept("EXE packaging failed");
                });
            }
        }, "jpackage-runner");
        t.setDaemon(true);
        t.start();
    }

    private void runJpackageWithModules(Path jarFile, String appName, Path outputDir, String mainClass) {
        String javaHome = System.getProperty("java.home");
        Path jpackageExe = Path.of(javaHome).resolve("bin").resolve("jpackage.exe");
        if (!Files.exists(jpackageExe)) {
            jpackageExe = Path.of(javaHome).resolve("bin").resolve("jpackage");
        }
        if (!Files.exists(jpackageExe)) {
            log.accept("jpackage not found in: " + javaHome);
            log.accept("Make sure you're using JDK 17+ with jpackage");
            return;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();
        if (isWindows) {
            cmd.addAll(List.of("cmd.exe", "/c", jpackageExe.toString()));
        } else {
            cmd.add(jpackageExe.toString());
        }

        Path libsDir = jarFile.getParent();
        String moduleName = "com.example.f_ex";
        cmd.addAll(List.of(
            "--name", appName,
            "--module-path", libsDir.toString(),
            "--module", moduleName + "/" + mainClass,
            "--type", "app-image",
            "--dest", outputDir.toString(),
            "--win-console"
        ));

        log.accept("$ " + String.join(" ", cmd));
        statusUpdate.accept("Running jpackage...");

        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(jarFile.getParent().toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();

                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String l = line;
                        Platform.runLater(() -> log.accept(l));
                    }
                }

                int code = p.waitFor();
                Platform.runLater(() -> {
                    if (code == 0) {
                        Path exe = outputDir.resolve(appName).resolve(appName + ".exe");
                        if (Files.exists(exe)) {
                            log.accept("EXE created: " + exe);
                        } else {
                            log.accept("Package created in: " + outputDir.resolve(appName));
                        }
                        statusUpdate.accept("EXE packaging completed");
                    } else {
                        log.accept("jpackage failed with exit code: " + code);
                        statusUpdate.accept("EXE packaging failed");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    log.accept("jpackage failed: " + e.getMessage());
                    statusUpdate.accept("EXE packaging failed");
                });
            }
        }, "jpackage-runner");
        t.setDaemon(true);
        t.start();
    }

    private void runJpackage(Path jarFile, String appName, Path outputDir, String mainClass) {
        String javaHome = System.getProperty("java.home");
        Path jpackageExe = Path.of(javaHome).resolve("bin").resolve("jpackage.exe");
        if (!Files.exists(jpackageExe)) {
            jpackageExe = Path.of(javaHome).resolve("bin").resolve("jpackage");
        }
        if (!Files.exists(jpackageExe)) {
            log.accept("jpackage not found in: " + javaHome);
            log.accept("Make sure you're using JDK 17+ with jpackage");
            return;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();
        if (isWindows) {
            cmd.addAll(List.of("cmd.exe", "/c", jpackageExe.toString()));
        } else {
            cmd.add(jpackageExe.toString());
        }

        cmd.addAll(List.of(
            "--input", jarFile.getParent().toString(),
            "--name", appName,
            "--main-jar", jarFile.getFileName().toString(),
            "--main-class", mainClass,
            "--type", "app-image",
            "--dest", outputDir.toString(),
            "--win-console"
        ));

        log.accept("$ " + String.join(" ", cmd));
        statusUpdate.accept("Running jpackage...");

        Thread t = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.directory(jarFile.getParent().toFile());
                pb.redirectErrorStream(true);
                Process p = pb.start();

                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String l = line;
                        Platform.runLater(() -> log.accept(l));
                    }
                }

                int code = p.waitFor();
                Platform.runLater(() -> {
                    if (code == 0) {
                        Path exe = outputDir.resolve(appName).resolve(appName + ".exe");
                        if (Files.exists(exe)) {
                            log.accept("EXE created: " + exe);
                        } else {
                            log.accept("Package created in: " + outputDir.resolve(appName));
                        }
                        statusUpdate.accept("EXE packaging completed");
                    } else {
                        log.accept("jpackage failed with exit code: " + code);
                        statusUpdate.accept("EXE packaging failed");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    log.accept("jpackage failed: " + e.getMessage());
                    statusUpdate.accept("EXE packaging failed");
                });
            }
        }, "jpackage-runner");
        t.setDaemon(true);
        t.start();
    }
}
