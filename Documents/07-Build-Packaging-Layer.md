# Детальная документация: Build & Packaging Layer

## 📋 Содержание

1. [Обзор слоя](#обзор-слоя)
2. [ExePackager - Упаковка в EXE](#exepackager---упаковка-в-exe)
3. [Gradle Build Integration](#gradle-build-integration)
4. [Maven Build Integration](#maven-build-integration)
5. [jpackage - Создание нативных пакетов](#jpackage---создание-нативных-пакетов)
6. [Fat JAR Strategy](#fat-jar-strategy)
7. [Сценарии использования](#сценарии-использования)
8. [Ограничения и улучшения](#ограничения-и-улучшения)

---

## Обзор слоя

**Build & Packaging Layer** предоставляет возможность **создания нативных исполняемых файлов** (`.exe` для Windows) из Java проектов.

### Возможности:

- ✅ **Gradle Projects** - упаковка Gradle проектов в EXE
- ✅ **Maven Projects** - упаковка Maven проектов в EXE
- ✅ **Fat JAR** - создание JAR со всеми зависимостями
- ✅ **jpackage Integration** - использование JDK `jpackage` для создания нативных пакетов
- ✅ **Automatic Main Class Detection** - автоматическое определение главного класса

### Компоненты слоя:

```
Build & Packaging Layer
├── ExePackager.java      # Основной класс упаковки
└── build.gradle.kts      # Конфигурация shadowJar
```

### Технологии:

- **jpackage** - утилита JDK для создания нативных пакетов
- **shadowJar** - Gradle плагин для создания fat JAR
- **Fat JAR** - JAR со всеми зависимостями

---

## ExePackager - Упаковка в EXE

Файл: `src/main/java/com/example/f_ex/ExePackager.java`

### Назначение

**ExePackager** управляет процессом создания нативных исполняемых файлов из Java проектов.

### Структура класса

```java
final class ExePackager {
    private final Consumer<String> log;          // Логирование
    private final Consumer<String> statusUpdate; // Обновление статуса
}
```

### Основной метод

#### `packageAsExe(Path projectRoot, ProjectDetector.ProjectType type)`

**Назначение:** Главный метод для упаковки проекта в EXE.

**Алгоритм:**

```java
void packageAsExe(Path projectRoot, ProjectDetector.ProjectType type) {
    if (projectRoot == null) {
        log.accept("No project root set");
        return;
    }
    
    // Выбор стратегии в зависимости от типа проекта
    if (type == ProjectDetector.ProjectType.GRADLE) {
        packageGradleAsExe(projectRoot);
    } else if (type == ProjectDetector.ProjectType.MAVEN) {
        packageMavenAsExe(projectRoot);
    } else {
        log.accept("EXE packaging supported only for Gradle/Maven projects");
    }
}
```

**Особенности:**

- ✅ Поддерживает только Gradle и Maven проекты
- ✅ Вызывает соответствующий метод для каждого типа

---

## Gradle Build Integration

### Алгоритм `packageGradleAsExe(Path projectRoot)`

**Назначение:** Упаковывает Gradle проект в EXE.

**Алгоритм:**

```java
private void packageGradleAsExe(Path projectRoot) {
    // 1. Создание fat JAR
    log.accept("Building fat JAR with all dependencies...");
    if (!buildFatJar(projectRoot)) {
        log.accept("Fat JAR build failed. Trying regular JAR...");
        if (!buildProject(projectRoot, ProjectDetector.ProjectType.GRADLE)) {
            log.accept("Build failed. Cannot create EXE.");
            return;
        }
    }
    
    // 2. Поиск JAR файла
    Path jarFile = findFatJar(projectRoot);
    if (jarFile == null) {
        jarFile = findJarFile(projectRoot, ProjectDetector.ProjectType.GRADLE);
    }
    if (jarFile == null) {
        log.accept("JAR file not found after build.");
        return;
    }
    
    // 3. Определение главного класса
    String mainClass = findMainClass(projectRoot, ProjectDetector.ProjectType.GRADLE);
    if (mainClass == null || mainClass.isBlank()) {
        log.accept("Main class not found. Cannot create EXE.");
        return;
    }
    
    // 4. Параметры упаковки
    String appName = projectRoot.getFileName() != null ? 
        projectRoot.getFileName().toString() : "app";
    Path outputDir = projectRoot.resolve("dist");
    
    log.accept("Packaging as EXE: " + jarFile);
    log.accept("Main class: " + mainClass);
    log.accept("Output: " + outputDir);
    
    // 5. Запуск jpackage
    runJpackageWithFatJar(jarFile, appName, outputDir, mainClass);
}
```

**Шаги:**

1. **Создание fat JAR** - `buildFatJar()` (через `gradlew shadowJar`)
2. **Поиск JAR** - `findFatJar()` или `findJarFile()`
3. **Определение main class** - `findMainClass()` (из `build.gradle.kts`)
4. **Запуск jpackage** - `runJpackageWithFatJar()`

### `buildFatJar(Path projectRoot)`

**Назначение:** Создает fat JAR через Gradle `shadowJar` task.

**Алгоритм:**

```java
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
        
        // Чтение вывода
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
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
```

**Команда:**

```bash
# Windows
gradlew.bat shadowJar

# Linux/Mac
./gradlew shadowJar
```

**Особенности:**

- ✅ Использует Gradle wrapper (`gradlew`/`gradlew.bat`)
- ✅ Выполняется в фоновом потоке
- ✅ Обновляет UI через `Platform.runLater()`

### `findFatJar(Path projectRoot)`

**Назначение:** Находит fat JAR в `build/libs`.

**Алгоритм:**

```java
private Path findFatJar(Path projectRoot) {
    Path libs = projectRoot.resolve("build").resolve("libs");
    if (Files.isDirectory(libs)) {
        try {
            return Files.list(libs)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".jar") && 
                           (name.contains("all") || 
                            name.contains("fat") || 
                            name.contains("uber") || 
                            name.contains("shadow") || 
                            name.contains("-all"));
                })
                .findFirst()
                .orElse(null);
        } catch (Exception ignored) {}
    }
    return null;
}
```

**Имена fat JAR:**

- `*-all.jar`
- `*-fat.jar`
- `*-uber.jar`
- `*-shadow.jar`
- `*-all.jar`

**Пример:**

```
build/libs/F_EX-1.0-SNAPSHOT-all.jar
```

### `findJarFile(Path projectRoot, ProjectDetector.ProjectType type)`

**Назначение:** Находит обычный JAR файл.

**Алгоритм:**

```java
private Path findJarFile(Path projectRoot, ProjectDetector.ProjectType type) {
    if (type == ProjectDetector.ProjectType.GRADLE) {
        Path libs = projectRoot.resolve("build").resolve("libs");
        if (Files.isDirectory(libs)) {
            try {
                return Files.list(libs)
                    .filter(p -> p.toString().endsWith(".jar") && 
                               !p.toString().endsWith("-sources.jar"))
                    .findFirst()
                    .orElse(null);
            } catch (Exception ignored) {}
        }
    } else if (type == ProjectDetector.ProjectType.MAVEN) {
        Path target = projectRoot.resolve("target");
        if (Files.isDirectory(target)) {
            try {
                return Files.list(target)
                    .filter(p -> p.toString().endsWith(".jar") && 
                               !p.toString().endsWith("-sources.jar") && 
                               !p.toString().endsWith("-javadoc.jar"))
                    .findFirst()
                    .orElse(null);
            } catch (Exception ignored) {}
        }
    }
    return null;
}
```

**Особенности:**

- ✅ Игнорирует `-sources.jar` и `-javadoc.jar`
- ✅ Возвращает первый найденный JAR

---

## Maven Build Integration

### Алгоритм `packageMavenAsExe(Path projectRoot)`

**Назначение:** Упаковывает Maven проект в EXE.

**Алгоритм:**

```java
private void packageMavenAsExe(Path projectRoot) {
    // 1. Поиск JAR файла
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
    
    // 2. Определение главного класса
    String mainClass = findMainClass(projectRoot, ProjectDetector.ProjectType.MAVEN);
    if (mainClass == null || mainClass.isBlank()) {
        log.accept("Main class not found. Cannot create EXE.");
        return;
    }
    
    // 3. Параметры упаковки
    String appName = projectRoot.getFileName() != null ? 
        projectRoot.getFileName().toString() : "app";
    Path outputDir = projectRoot.resolve("dist");
    
    log.accept("Packaging as EXE: " + jarFile);
    log.accept("Main class: " + mainClass);
    log.accept("Output: " + outputDir);
    
    // 4. Запуск jpackage
    runJpackage(jarFile, appName, outputDir, mainClass);
}
```

**Особенности:**

- ✅ Сначала ищет существующий JAR
- ✅ Если не найден - собирает проект через `mvn package`
- ✅ Использует `runJpackage()` (без fat JAR, так как Maven не использует shadowJar)

---

## jpackage - Создание нативных пакетов

### Назначение

**jpackage** - утилита JDK 14+ для создания нативных пакетов приложений.

### Методы jpackage

#### `runJpackageWithFatJar(Path jarFile, String appName, Path outputDir, String mainClass)`

**Назначение:** Запускает jpackage с fat JAR (для Gradle).

**Алгоритм:**

```java
private void runJpackageWithFatJar(Path jarFile, String appName, Path outputDir, String mainClass) {
    // 1. Поиск jpackage
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
    
    // 2. Формирование команды
    boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
    List<String> cmd = new ArrayList<>();
    if (isWindows) {
        cmd.addAll(List.of("cmd.exe", "/c", jpackageExe.toString()));
    } else {
        cmd.add(jpackageExe.toString());
    }
    
    cmd.addAll(List.of(
        "--input", jarFile.getParent().toString(),      // Директория с JAR
        "--name", appName,                              // Имя приложения
        "--main-jar", jarFile.getFileName().toString(), // Имя JAR файла
        "--main-class", mainClass,                      // Главный класс
        "--type", "app-image",                          // Тип пакета
        "--dest", outputDir.toString(),                 // Выходная директория
        "--win-console",                                // Консоль для Windows
        "--java-options", "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--java-options", "--add-opens=java.base/java.util=ALL-UNNAMED"
    ));
    
    // 3. Запуск в фоновом потоке
    Thread t = new Thread(() -> {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(jarFile.getParent().toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            
            // Чтение вывода
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
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
```

**Параметры jpackage:**

- `--input` - директория с JAR файлом
- `--name` - имя приложения
- `--main-jar` - имя JAR файла
- `--main-class` - главный класс
- `--type` - тип пакета (`app-image` для директории, `exe` для установщика)
- `--dest` - выходная директория
- `--win-console` - показывать консоль (для отладки)
- `--java-options` - опции JVM

**Особенности:**

- ✅ Использует fat JAR (все зависимости включены)
- ✅ Добавляет `--add-opens` для JavaFX (если нужно)
- ✅ Выполняется в фоновом потоке

#### `runJpackage(Path jarFile, String appName, Path outputDir, String mainClass)`

**Назначение:** Запускает jpackage с обычным JAR (для Maven).

**Алгоритм:**

Аналогичен `runJpackageWithFatJar()`, но **без** `--java-options` для `--add-opens` (так как Maven проекты обычно не используют JavaFX или используют другой подход).

---

## Fat JAR Strategy

### Назначение

**Fat JAR** (также известный как "uber JAR" или "shaded JAR") - это JAR файл, содержащий все зависимости проекта.

### Почему Fat JAR?

**Проблема:**

- Обычный JAR содержит только код проекта
- Зависимости должны быть в classpath
- jpackage требует все зависимости для создания нативного пакета

**Решение:**

- Fat JAR содержит все зависимости внутри
- jpackage может создать нативный пакет из одного файла
- Не нужно указывать classpath

### Gradle shadowJar Configuration

**Файл:** `build.gradle.kts`

```kotlin
plugins {
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

tasks.shadowJar {
    archiveClassifier.set("all")
    mergeServiceFiles()
    manifest {
        attributes(mapOf("Main-Class" to "com.example.f_ex.Launcher"))
    }
    configurations = listOf(project.configurations.runtimeClasspath.get())
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

**Параметры:**

- `archiveClassifier.set("all")` - добавляет суффикс `-all` к имени JAR
- `mergeServiceFiles()` - объединяет service files (для SPI)
- `manifest` - устанавливает `Main-Class`
- `configurations` - включает все зависимости из `runtimeClasspath`
- `duplicatesStrategy.EXCLUDE` - исключает дубликаты

**Результат:**

```
build/libs/F_EX-1.0-SNAPSHOT-all.jar
```

### Структура Fat JAR

```
F_EX-1.0-SNAPSHOT-all.jar
├── META-INF/
│   ├── MANIFEST.MF
│   └── services/ (merged service files)
├── com/example/f_ex/
│   └── *.class (project classes)
├── org/fxmisc/richtext/
│   └── *.class (RichTextFX)
├── org/openjfx/
│   └── *.class (JavaFX)
└── ... (all other dependencies)
```

---

## Определение главного класса

### `findMainClass(Path projectRoot, ProjectDetector.ProjectType type)`

**Назначение:** Определяет главный класс из конфигурации проекта.

**Алгоритм для Gradle:**

```java
if (type == ProjectDetector.ProjectType.GRADLE) {
    Path buildGradle = projectRoot.resolve("build.gradle");
    Path buildGradleKts = projectRoot.resolve("build.gradle.kts");
    try {
        Path file = Files.exists(buildGradleKts) ? buildGradleKts : buildGradle;
        if (Files.exists(file)) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            
            // Паттерн 1: mainClass.set("...")
            Pattern p = Pattern.compile("mainClass\\.set\\(\"([^\"]+)\"\\)");
            Matcher m = p.matcher(content);
            if (m.find()) return m.group(1);
            
            // Паттерн 2: mainClass = "..."
            p = Pattern.compile("mainClass\\s*=\\s*\"([^\"]+)\"");
            m = p.matcher(content);
            if (m.find()) return m.group(1);
        }
    } catch (Exception ignored) {}
}
```

**Примеры:**

```kotlin
// build.gradle.kts
application {
    mainClass.set("com.example.f_ex.Launcher")
}

// или
mainClass = "com.example.f_ex.Launcher"
```

**Алгоритм для Maven:**

```java
else if (type == ProjectDetector.ProjectType.MAVEN) {
    Path pom = projectRoot.resolve("pom.xml");
    try {
        if (Files.exists(pom)) {
            String content = Files.readString(pom, StandardCharsets.UTF_8);
            Pattern p = Pattern.compile("<mainClass>([^<]+)</mainClass>");
            Matcher m = p.matcher(content);
            if (m.find()) return m.group(1).trim();
        }
    } catch (Exception ignored) {}
}
```

**Пример:**

```xml
<!-- pom.xml -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <configuration>
        <transformers>
            <transformer>
                <mainClass>com.example.f_ex.Launcher</mainClass>
            </transformer>
        </transformers>
    </configuration>
</plugin>
```

---

## Сценарии использования

### Сценарий 1: Упаковка Gradle проекта

```
User: Presses "Package as EXE"
    │
    ▼
onPackageAsExe()
    │
    └─→ exePackager.packageAsExe(projectRoot, GRADLE)
        │
        └─→ packageGradleAsExe(projectRoot)
            │
            ├─→ buildFatJar()
            │   └─→ gradlew shadowJar
            │       └─→ Creates build/libs/F_EX-1.0-SNAPSHOT-all.jar
            │
            ├─→ findFatJar()
            │   └─→ Returns build/libs/F_EX-1.0-SNAPSHOT-all.jar
            │
            ├─→ findMainClass()
            │   └─→ Parses build.gradle.kts
            │       └─→ Returns "com.example.f_ex.Launcher"
            │
            └─→ runJpackageWithFatJar()
                │
                └─→ jpackage --input build/libs \
                    --name F_EX \
                    --main-jar F_EX-1.0-SNAPSHOT-all.jar \
                    --main-class com.example.f_ex.Launcher \
                    --type app-image \
                    --dest dist
                    │
                    └─→ Creates dist/F_EX/F_EX.exe
```

### Сценарий 2: Упаковка Maven проекта

```
User: Presses "Package as EXE"
    │
    ▼
onPackageAsExe()
    │
    └─→ exePackager.packageAsExe(projectRoot, MAVEN)
        │
        └─→ packageMavenAsExe(projectRoot)
            │
            ├─→ findJarFile()
            │   └─→ Not found
            │
            ├─→ buildProject()
            │   └─→ mvn package
            │       └─→ Creates target/app-1.0.jar
            │
            ├─→ findJarFile()
            │   └─→ Returns target/app-1.0.jar
            │
            ├─→ findMainClass()
            │   └─→ Parses pom.xml
            │       └─→ Returns "com.example.app.Main"
            │
            └─→ runJpackage()
                │
                └─→ jpackage --input target \
                    --name app \
                    --main-jar app-1.0.jar \
                    --main-class com.example.app.Main \
                    --type app-image \
                    --dest dist
                    │
                    └─→ Creates dist/app/app.exe
```

---

## Ограничения и улучшения

### Текущие ограничения

#### 1. Только Gradle/Maven

**Ограничение:**

- ❌ Не поддерживает Plain Java проекты
- ❌ Не поддерживает IntelliJ IDEA проекты

**Улучшения:**

- Добавить поддержку Plain Java (сборка через javac)
- Добавить поддержку IntelliJ IDEA проектов

#### 2. Fat JAR только для Gradle

**Ограничение:**

- ❌ Maven проекты не используют fat JAR
- ❌ Может не работать для проектов с большим количеством зависимостей

**Улучшения:**

- Добавить поддержку Maven Shade Plugin для fat JAR
- Оптимизация размера fat JAR

#### 3. Только Windows EXE

**Ограничение:**

- ❌ Создает только `.exe` для Windows
- ❌ Не создает пакеты для Linux/Mac

**Улучшения:**

- Определение платформы и создание соответствующих пакетов
- `.deb` для Linux, `.dmg` для Mac

#### 4. Нет настройки иконки

**Ограничение:**

- ❌ Использует иконку по умолчанию
- ❌ Нет возможности указать свою иконку

**Улучшения:**

- Добавить параметр для иконки приложения
- Автоматический поиск иконки в проекте

### Планируемые улучшения

1. **Поддержка других типов проектов:**
   - Plain Java
   - IntelliJ IDEA

2. **Улучшенная обработка зависимостей:**
   - Maven Shade Plugin
   - Оптимизация размера

3. **Кроссплатформенная упаковка:**
   - Linux `.deb`/`.rpm`
   - Mac `.dmg`
   - Windows `.msi`

4. **Кастомизация:**
   - Иконка приложения
   - Описание приложения
   - Версия

---

## Резюме

### Ключевые особенности Build & Packaging Layer:

1. ✅ **Gradle Integration** - поддержка Gradle проектов с fat JAR
2. ✅ **Maven Integration** - поддержка Maven проектов
3. ✅ **jpackage Integration** - использование JDK jpackage для создания нативных пакетов
4. ✅ **Automatic Main Class Detection** - автоматическое определение главного класса
5. ✅ **Fat JAR Strategy** - создание JAR со всеми зависимостями

### Технические детали:

- **jpackage** - утилита JDK 14+ для создания нативных пакетов
- **shadowJar** - Gradle плагин для создания fat JAR
- **Fat JAR** - JAR со всеми зависимостями для упрощения упаковки

### Производительность:

- ✅ Выполнение в фоновых потоках
- ✅ Обновление UI через `Platform.runLater()`
- ✅ Логирование процесса упаковки

---

**Версия:** 1.0  
**Дата:** 2026-01-20  
**Файл:** `Documents/07-Build-Packaging-Layer.md`
