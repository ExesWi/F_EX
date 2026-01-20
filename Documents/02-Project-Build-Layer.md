# Детальная документация: Project & Build Layer

## 📋 Содержание

1. [Задачи слоя](#задачи-слоя)
2. [Граница слоя и кто его использует](#граница-слоя-и-кто-его-использует)
3. [`ProjectDetector` — определение типа проекта](#projectdetector--определение-типа-проекта)
4. [`ProjectModelResolver` — source roots + classpath](#projectmodelresolver--source-roots--classpath)
5. [`JavaProjectRunner` — запуск Plain Java проектов](#javaprojectrunner--запуск-plain-java-проектов)
6. [`IntelliJProjectRunner` — запуск IntelliJ IDEA проектов](#intellijprojectrunner--запуск-intellij-idea-проектов)
7. [Взаимодействие с Debugger (JDWP + jdb)](#взаимодействие-с-debugger-jdwp--jdb)
8. [Где это подключено в UI](#где-это-подключено-в-ui)
9. [Ограничения и точки улучшения](#ограничения-и-точки-улучшения)

---

## Задачи слоя

**Project & Build Layer** отвечает за всё, что связано с *пониманием проекта* и *запуском/сборкой* кода:

- **Определить тип проекта** (Gradle/Maven/Plain Java/IntelliJ IDEA).
- **Найти “реальный root”** (если пользователь открыл родительскую папку, а проект лежит глубже).
- **Собрать модель проекта для анализа**: \(sourceRoots, classpath\) — чтобы `javac` диагностика и навигация работали ближе к “как в IDEA”.
- **Запустить проект**:
  - Plain Java: вручную `javac` + `java`
  - IntelliJ: вручную `javac` + `java`, но с classpath из `.idea/libraries` / `.iml`
  - Gradle/Maven: через wrapper/CLI (это “Build Tools” часть, вызов из контроллера).
- **Поддержать debug-run**: запуск с JDWP параметрами (порт 5005, `suspend=y`) — чтобы дальше подключался `DebugSession` (jdb).

---

## Граница слоя и кто его использует

Этот слой — “backend” для IDE, но без базы данных.

- **Вход**: команды пользователя из `IdeController` (open project, run, debug, build).
- **Выход**:
  - логи в консоль IDE (`logCallback`)
  - статус (`statusUpdate`)
  - `Process` (для интерактивной консоли и для дебага)
  - `ProjectModel` (sourceRoots + classpath) для диагностики `javac`

---

## `ProjectDetector` — определение типа проекта

Файл: `src/main/java/com/example/f_ex/ProjectDetector.java`

### Роль

**Быстро и “достаточно правильно”** понять, что за проект открыл пользователь.

### Типы проектов

`ProjectType`:
- `GRADLE`
- `MAVEN`
- `JAVA`
- `INTELLIJ_IDEA`
- `UNKNOWN`

### Основной алгоритм `detectProjectType(Path root)`

Порядок проверки важен — он отражает приоритет:

1. **Gradle**: ищет `gradlew(.bat)`, `build.gradle`, `build.gradle.kts`
2. **Maven**: ищет `pom.xml`
3. **Поиск Maven/Gradle в подпапках** (ограниченная глубина)
4. **IntelliJ IDEA**: есть `.idea/` или `.iml` (walk до глубины 2)
5. **Plain Java**: есть `.java` файлы в стандартных местах (`src/main/java`, `src`, `src/java`, root), и ещё ограниченный рекурсивный поиск
6. Иначе `UNKNOWN`

### Авто-поиск проекта в подпапках

Методы:
- `findGradleProject(startDir)` — рекурсивно до глубины 3, игнорируя `HIDDEN_DIRS`
- `findMavenProject(startDir)` — аналогично
- `findJavaProject(startDir)` — ищет папку с `src/main/java`

### Почему есть `HIDDEN_DIRS`

Это ускорение + UX:
- не лезем в `build/`, `.git/`, `node_modules/` и т.д.
- это влияет и на детект, и на индексатор, и на дерево проекта

---

## `ProjectModelResolver` — source roots + classpath

Файл: `src/main/java/com/example/f_ex/ProjectModelResolver.java`

### Роль

Дать IDE **модель проекта**:
- `sourceRoots`: откуда брать исходники (для `-sourcepath`, навигации, индексации)
- `classpath`: какие jar’ы и output dirs нужны `javac`/`java` (для диагностики и компиляции)

### Контракт

`ProjectModelResolver.ProjectModel`:
- `List<Path> sourceRoots`
- `List<Path> classpath`

`resolve(projectRoot, type)` выбирает стратегию:

- `GRADLE` → `resolveGradle()`
- `MAVEN` → `resolveMaven()`
- `JAVA` → `resolvePlainJava()`
- `INTELLIJ_IDEA` → `resolveIntelliJ()` (сейчас fallback)

### `resolvePlainJava(Path root)`

Это “best-effort” для проектов без build-системы:
- если есть `src/main/java` → это sourceRoot
- иначе если есть `src` → это sourceRoot
- иначе корнем считаем `root`
- classpath пустой (нет зависимостей)

### `resolveIntelliJ(Path root)`

Сейчас возвращает `resolvePlainJava(root)`.

Почему так:
- IntelliJ зависимости в проекте реально лежат в `.idea` / `.iml` и парсятся **раннером** (`IntelliJProjectRunner`) для запуска
- для диагностики `javac` IDE пока использует хотя бы source roots

### `resolveGradle(Path root)`

Идея: не пытаться угадывать classpath вручную, а попросить Gradle сам сказать.

Подход:
1. Определяется wrapper:
   - Windows: `cmd.exe /c gradlew.bat`
   - Unix: `./gradlew`
   - если wrapper не найден → fallback в `resolvePlainJava`
2. Создается **временный init script** (Gradle), который печатает:
   - `IDE_SRCS=` список srcDirs `sourceSets.main.java.srcDirs`
   - `IDE_CP=` объединение `compileClasspath + runtimeClasspath` (уникальный список)
3. Выполняется Gradle:
   - `-q -I <tmpInit> idePrintModel`
4. Парсится stdout:
   - строки, начинающиеся на `IDE_SRCS=` и `IDE_CP=`
5. Возвращается `ProjectModel`:
   - sourceRoots из `IDE_SRCS`
   - classpath из `IDE_CP`

Это ключевая штука для качества диагностики: `javac` начинает видеть зависимости так же, как видит их Gradle.

### `resolveMaven(Path root)`

Подход:
1. Выбирается команда Maven:
   - wrapper `mvnw.cmd` / `mvnw`
   - иначе системный `mvn`/`mvn.cmd`
2. Запускается:
   - `dependency:build-classpath`
   - с `-Dmdep.pathSeparator=<OS_separator>`
   - scope compile
3. Вывод Maven обычно содержит classpath одной длинной строкой → парсим по `pathSeparator`
4. sourceRoots берутся как в `resolvePlainJava(root)` (минимальная модель)

### `runAndCapture(cmd, dir)`

Общая инфраструктура:
- запускает внешний процесс
- читает stdout в UTF‑8
- возвращает текст целиком

Важно: resolver **не делает UI** — это чисто инфраструктурный сбор данных.

---

## `JavaProjectRunner` — запуск Plain Java проектов

Файл: `src/main/java/com/example/f_ex/JavaProjectRunner.java`

### Роль

Запуск проектов **без Gradle/Maven**, “как человек сделал бы вручную”:
- найти main class
- скомпилировать `javac`
- запустить `java`
- включить UTF‑8 для нормальной русской консоли
- поддержать debug-run с JDWP

### Контракт и зависимости

Конструкторы принимают:
- `Consumer<String> logCallback` — писать вывод в консоль IDE
- `Runnable statusUpdate` — дергать UI статус
- опционально `Consumer<Process> processCallback` — отдать `Process`, чтобы IDE могла писать в stdin (интерактивная консоль)

### `run(Path projectRoot)`

Логика защиты от “не то открыл”:
- если внутри найден Maven/Gradle проект (`ProjectDetector.findMavenProject/findGradleProject`) → просит открыть правильный root или использовать build tool
- если в корне есть `pom.xml` или `build.gradle(.kts)` → аналогично

Дальше:
1. `findMainClassAnywhere()` ищет `.java` с `public static void main` (до глубины 10)
2. `findSourceRoot()` пытается определить source root по стандартным путям и по тому, где лежит main файл
3. `checkUsesJavaFX()` — если видит `import javafx` → предупреждает про JavaFX и пытается компилировать (если есть JavaFX SDK)
4. `compileAndRun(..., debug=false)`

### `runFile(...)` и `debugFile(...)`

- `runFile` — запуск конкретного выбранного файла (если это main)
- `debugFile` — компиляция+запуск с JDWP:
  - `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005`
  - важно: `suspend=y` → процесс ждёт дебаггер

### `compileAndRun(...)`

1. Создаёт output dirs:
   - `build/classes`
2. Собирает список `.java` файлов (walk по `sourceRoot`)
3. Формирует `javac` команду:
   - `-encoding UTF-8`
   - `-d build/classes`
   - `-sourcepath <sourceRoot>`
   - при JavaFX: `--module-path <javafx lib> --add-modules javafx.controls,javafx.fxml`
4. После успешной компиляции формирует `java` команду:
   - `-Dfile.encoding=UTF-8 -Dconsole.encoding=UTF-8`
   - при debug: JDWP параметры
   - `-cp build/classes`
   - при JavaFX: module-path + add-modules
5. Запускает процесс через `runCommand(...)`

### UTF‑8 и Windows

`runCommand(...)` добавляет в environment:
- `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dconsole.encoding=UTF-8`
- на Windows ещё `PYTHONIOENCODING=utf-8` (на всякий случай, если дергаются python-скрипты)

И читает вывод процесса в `StandardCharsets.UTF_8`, прокидывая строки в UI через `Platform.runLater`.

---

## `IntelliJProjectRunner` — запуск IntelliJ IDEA проектов

Файл: `src/main/java/com/example/f_ex/IntelliJProjectRunner.java`

### Роль

Запуск Java проекта, который в реальности “живёт” как IntelliJ конфигурация:
- зависимости берём из `.idea/libraries/*.xml` или `.iml`
- дальше компилируем `javac` с `-cp <classes + libs>`
- запускаем `java` с этим же classpath

### `findLibraries(projectRoot)`

1. Пытается прочитать `.idea/libraries/*.xml`
2. Парсит строки вида:
   - `jar://C:/path/to/something.jar!?`
3. Если `.idea/libraries` нет или ничего не найдено → fallback: `findLibrariesFromIml()`
4. `findLibrariesFromIml()`:
   - ищет `.iml` до глубины 3
   - парсит `jar://...!?` аналогично

Результат: `List<Path> libraries`

### Компиляция/запуск

Очень похоже на `JavaProjectRunner`, но есть ключевое отличие:

- classpath формируется так:
  - `build/classes` + все найденные jars из libraries
  - строка: `String classpathStr = String.join(File.pathSeparator, classpath)`

`javac` получает:
- `-cp classpathStr`

`java` получает:
- `-cp classpathStr`

Debug-run:
- тот же JDWP порт 5005, `suspend=y`

### JavaFX

Логика такая же: если проект использует JavaFX — пытается найти JavaFX SDK (`JAVA_FX_HOME` или типовые пути) и добавить `--module-path` + `--add-modules`.

---

## Взаимодействие с Debugger (JDWP + jdb)

Project & Build Layer отвечает только за **запуск процесса в debug-режиме** (JDWP).

Дальше подключается Debugger слой:
- `DebugSession` запускает `jdb` и коннектится по `com.sun.jdi.SocketAttach` к `localhost:5005`.

Схема:

1. `JavaProjectRunner.debugFile(...)` или `IntelliJProjectRunner.debugFile(...)`
2. Запускается `java` с `-agentlib:jdwp=...suspend=y,address=5005`
3. IDE видит процесс и затем инициирует `DebugSession.connectSocket("localhost", 5005)`

---

## Где это подключено в UI

Точки входа из `IdeController`:

- **Открытие проекта**:
  - `setProjectRoot(...)` → `ProjectDetector.detectProjectType(...)`
  - `ProjectModelResolver.resolve(...)` в отдельном потоке
- **Запуск**:
  - для Gradle: `runGradleIn(..., "run")`
  - для Plain/IntelliJ: раннеры (`JavaProjectRunner` / `IntelliJProjectRunner`)
- **Debug**:
  - запуск в debug mode (JDWP) → затем `DebugSession`

---

## Ограничения и точки улучшения

### 1) Детект main class

Сейчас main class определяется простым `contains("public static void main")`.
Плюсы: быстро и просто.
Минусы:
- false positives в комментариях/строках
- не понимает несколько main’ов и “правильный выбор”

Улучшение:
- переиспользовать логику “outside strings/comments” как в rename service
- хранить все main targets и приоритизировать (например, по имени/пакету/Run config)

### 2) IntelliJ зависимости

Парсинг `.idea`/`.iml` сейчас “приблизительный” (regex `jar://...!?`).
Улучшение:
- полноценный парсер IntelliJ model (classpath order, output dirs, module deps)
- кэширование результата

### 3) Gradle/Maven classpath

`ProjectModelResolver` уже делает хороший шаг, но:
- Gradle init script учитывает только java plugin и sourceSets.main
- Maven берёт compile scope и только classpath line

Улучшение:
- поддержать multi-module
- учитывать test sourceSets
- учитывать annotation processors

### 4) JavaFX без build system

Plain Java + JavaFX без Gradle/Maven — сложно.
Сейчас требуется `JAVA_FX_HOME`.
Улучшение:
- UI настройка пути к JavaFX SDK (в Settings)
- автоскачивание JavaFX SDK (опционально)

---

**Версия:** 1.0  
**Дата:** 2026-01-20  
**Файл:** `Documents/02-Project-Build-Layer.md`

