package com.example.f_ex;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class ProjectModelResolver {
    static final class ProjectModel {
        final List<Path> sourceRoots;
        final List<Path> classpath;

        ProjectModel(List<Path> sourceRoots, List<Path> classpath) {
            this.sourceRoots = sourceRoots;
            this.classpath = classpath;
        }
    }

    ProjectModel resolve(Path projectRoot, ProjectDetector.ProjectType type) {
        if (projectRoot == null) return new ProjectModel(List.of(), List.of());

        return switch (type) {
            case INTELLIJ_IDEA -> resolveIntelliJ(projectRoot);
            case GRADLE -> resolveGradle(projectRoot);
            case MAVEN -> resolveMaven(projectRoot);
            case JAVA -> resolvePlainJava(projectRoot);
            default -> new ProjectModel(List.of(), List.of());
        };
    }

    private ProjectModel resolvePlainJava(Path root) {
        List<Path> src = new ArrayList<>();
        Path a = root.resolve("src").resolve("main").resolve("java");
        if (Files.isDirectory(a)) src.add(a);
        Path b = root.resolve("src");
        if (Files.isDirectory(b) && src.isEmpty()) src.add(b);
        if (src.isEmpty()) src.add(root);
        return new ProjectModel(src, List.of());
    }

    private ProjectModel resolveIntelliJ(Path root) {
        // Libraries already parsed in IntelliJProjectRunner at runtime; for diagnostics we at least use src roots.
        return resolvePlainJava(root);
    }

    private ProjectModel resolveGradle(Path root) {
        Path gradlewBat = root.resolve("gradlew.bat");
        Path gradlew = root.resolve("gradlew");
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> cmd = new ArrayList<>();
        if (isWindows && Files.exists(gradlewBat)) cmd.addAll(List.of("cmd.exe", "/c", gradlewBat.toString()));
        else if (Files.exists(gradlew)) cmd.add(gradlew.toString());
        else return resolvePlainJava(root);

        // init script prints main compile classpath + runtime classpath + source roots
        String initScript = """
                allprojects {
                  tasks.register("idePrintModel") {
                    doLast {
                      def hasJava = project.extensions.findByName("java") != null
                      if (!hasJava) return
                      def ss = project.sourceSets
                      def srcs = ss.main.java.srcDirs.collect { it.absolutePath }.findAll { it != null }
                      println("IDE_SRCS=" + srcs.join(File.pathSeparator))
                      def cp = (ss.main.compileClasspath + ss.main.runtimeClasspath).files.collect { it.absolutePath }.unique()
                      println("IDE_CP=" + cp.join(File.pathSeparator))
                    }
                  }
                }
                """;

        try {
            Path tmp = Files.createTempFile("f_ex_ide_init", ".gradle");
            Files.writeString(tmp, initScript, StandardCharsets.UTF_8);

            List<String> full = new ArrayList<>(cmd);
            full.addAll(List.of("-q", "-I", tmp.toString(), "idePrintModel"));
            String out = runAndCapture(full, root);
            return parseModelFromToolOutput(out, root);
        } catch (Exception e) {
            return resolvePlainJava(root);
        }
    }

    private ProjectModel resolveMaven(Path root) {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        Path mvnwBat = root.resolve("mvnw.cmd");
        Path mvnw = root.resolve("mvnw");

        List<String> cmd = new ArrayList<>();
        if (isWindows && Files.exists(mvnwBat)) cmd.addAll(List.of("cmd.exe", "/c", mvnwBat.toString()));
        else if (Files.exists(mvnw)) cmd.add(mvnw.toString());
        else if (isWindows) cmd.addAll(List.of("cmd.exe", "/c", "mvn.cmd"));
        else cmd.add("mvn");

        // dependency:build-classpath prints cp; also add target/classes for already-compiled
        List<String> full = new ArrayList<>(cmd);
        full.addAll(List.of(
                "-q",
                "-DincludeScope=compile",
                "-Dmdep.pathSeparator=" + java.io.File.pathSeparator,
                "dependency:build-classpath"
        ));
        try {
            String out = runAndCapture(full, root);
            List<Path> src = resolvePlainJava(root).sourceRoots;
            List<Path> cp = new ArrayList<>();
            for (String line : out.split("\\R")) {
                String l = line.trim();
                if (l.isEmpty()) continue;
                // maven may output the classpath as a single long line
                if (l.contains(java.io.File.pathSeparator)) {
                    for (String part : l.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                        if (!part.isBlank()) cp.add(Path.of(part.trim()));
                    }
                    break;
                }
            }
            return new ProjectModel(src, cp);
        } catch (Exception e) {
            return resolvePlainJava(root);
        }
    }

    private static String runAndCapture(List<String> cmd, Path dir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        p.waitFor();
        return out.toString();
    }

    private static ProjectModel parseModelFromToolOutput(String out, Path root) {
        String srcs = null;
        String cp = null;
        for (String line : out.split("\\R")) {
            if (line.startsWith("IDE_SRCS=")) srcs = line.substring("IDE_SRCS=".length()).trim();
            if (line.startsWith("IDE_CP=")) cp = line.substring("IDE_CP=".length()).trim();
        }
        List<Path> sourceRoots = new ArrayList<>();
        if (srcs != null && !srcs.isBlank()) {
            for (String s : srcs.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (!s.isBlank()) sourceRoots.add(Path.of(s.trim()));
            }
        }
        if (sourceRoots.isEmpty()) sourceRoots.addAll(new ProjectModelResolver().resolvePlainJava(root).sourceRoots);

        List<Path> classpath = new ArrayList<>();
        if (cp != null && !cp.isBlank()) {
            for (String s : cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (!s.isBlank()) classpath.add(Path.of(s.trim()));
            }
        }
        classpath = classpath.stream().filter(Objects::nonNull).distinct().toList();
        return new ProjectModel(sourceRoots, classpath);
    }
}

