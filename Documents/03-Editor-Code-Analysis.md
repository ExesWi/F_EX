# Детальная документация: Editor & Code Analysis Layer

## 📋 Содержание

1. [Обзор слоя](#обзор-слоя)
2. [CodeIndexer - Индексация кода](#codeindexer---индексация-кода)
3. [Подсветка синтаксиса](#подсветка-синтаксиса)
4. [Диагностика кода (javac)](#диагностика-кода-javac)
5. [Problems Panel - Панель проблем](#problems-panel---панель-проблем)
6. [Автодополнение (Code Completion)](#автодополнение-code-completion)
7. [Поиск по проекту](#поиск-по-проекту)
8. [Навигация по коду](#навигация-по-коду)
9. [Взаимодействие компонентов](#взаимодействие-компонентов)

---

## Обзор слоя

**Editor & Code Analysis Layer** отвечает за всё, что связано с **редактированием кода** и **анализом**:

- ✅ **Индексация** - построение индекса классов, методов, интерфейсов
- ✅ **Подсветка синтаксиса** - визуальное выделение элементов кода
- ✅ **Диагностика** - проверка кода через `javac` в реальном времени
- ✅ **Автодополнение** - подсказки при вводе кода
- ✅ **Поиск** - поиск текста и символов по проекту
- ✅ **Навигация** - переход к определениям и использованиям

### Компоненты слоя:

```
Editor & Code Analysis Layer
├── CodeIndexer.java              # Индексатор кода
├── IdeController (частично)      # Подсветка, диагностика, автодополнение
└── RichTextFX CodeArea           # Компонент редактора
```

---

## CodeIndexer - Индексация кода

Файл: `src/main/java/com/example/f_ex/CodeIndexer.java`

### Назначение

**CodeIndexer** строит индекс всех классов, интерфейсов и методов в проекте для быстрого поиска и автодополнения.

### Структура данных

#### Индекс

```java
private final Map<String, List<CodeElement>> index = new ConcurrentHashMap<>();
```

- **Ключ**: имя элемента в нижнем регистре (для case-insensitive поиска)
- **Значение**: список `CodeElement` с этим именем (может быть несколько классов с одинаковым именем в разных пакетах)

#### CodeElement

```java
public static class CodeElement {
    private final String name;           // Имя элемента
    private final CodeElementType type;   // Тип (CLASS, INTERFACE, METHOD, VARIABLE)
    private final Path file;             // Файл, где находится
    private final String packageName;     // Пакет
    private final int line;              // Номер строки (1-based)
}
```

### Алгоритм индексации

#### `indexProject()`

**Назначение:** Полная индексация всего проекта.

**Алгоритм:**

```java
public void indexProject() {
    index.clear();  // Очистка старого индекса
    
    // 1. Рекурсивный обход всех .java файлов
    Files.walk(projectRoot, 20)
        .filter(Files::isRegularFile)
        .filter(p -> p.toString().endsWith(".java"))
        .filter(p -> !shouldHidePath(p.getParent()))  // Игнорируем скрытые папки
        .forEach(this::indexFile);  // Индексируем каждый файл
}
```

**Особенности:**

- ✅ Глубина обхода: 20 уровней
- ✅ Игнорирует скрытые папки (`build`, `.git`, `.idea`, и т.д.)
- ✅ Только `.java` файлы
- ✅ Потокобезопасность: `ConcurrentHashMap`

#### `indexFile(Path file)`

**Назначение:** Индексация одного Java файла.

**Алгоритм:**

```java
private void indexFile(Path file) {
    // 1. Чтение содержимого файла
    String content = Files.readString(file, StandardCharsets.UTF_8);
    
    // 2. Извлечение пакета
    String packageName = extractPackage(content);
    
    // 3. Поиск классов
    Matcher classMatcher = CLASS_PATTERN.matcher(content);
    while (classMatcher.find()) {
        String className = classMatcher.group(1);
        int line = lineAt(content, classMatcher.start(1));
        addToIndex(className, CodeElementType.CLASS, file, packageName, line);
    }
    
    // 4. Поиск интерфейсов
    Matcher interfaceMatcher = INTERFACE_PATTERN.matcher(content);
    while (interfaceMatcher.find()) {
        String interfaceName = interfaceMatcher.group(1);
        int line = lineAt(content, interfaceMatcher.start(1));
        addToIndex(interfaceName, CodeElementType.INTERFACE, file, packageName, line);
    }
    
    // 5. Поиск методов
    Matcher methodMatcher = METHOD_PATTERN.matcher(content);
    while (methodMatcher.find()) {
        String methodName = methodMatcher.group(methodMatcher.groupCount());
        if (methodName != null && !methodName.equals("class") && !methodName.equals("interface")) {
            int line = lineAt(content, methodMatcher.start(methodMatcher.groupCount()));
            addToIndex(methodName, CodeElementType.METHOD, file, packageName, line);
        }
    }
}
```

### Регулярные выражения

#### CLASS_PATTERN

```java
Pattern.compile(
    "\\b(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?class\\s+(\\w+)"
)
```

**Находит:**
- `public class MyClass`
- `final class MyClass`
- `abstract class MyClass`
- `class MyClass`

**Группа 1:** имя класса

#### INTERFACE_PATTERN

```java
Pattern.compile(
    "\\b(?:public\\s+)?interface\\s+(\\w+)"
)
```

**Находит:**
- `public interface MyInterface`
- `interface MyInterface`

#### METHOD_PATTERN

```java
Pattern.compile(
    "\\b(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?(?:\\w+\\s+)*(\\w+)\\s*\\([^)]*\\)"
)
```

**Находит:**
- `public void method()`
- `private static final int method()`
- `protected String method(int x)`

**Особенности:**
- Требует модификатор доступа (`public`, `private`, `protected`)
- Игнорирует конструкторы (нет return type)
- Может давать false positives (например, `class` в `public class`)

### Вспомогательные методы

#### `extractPackage(String content)`

```java
Pattern pkgPattern = Pattern.compile("package\\s+([\\w.]+);");
Matcher m = pkgPattern.matcher(content);
if (m.find()) {
    return m.group(1);
}
return "";
```

**Находит:** `package com.example.test;` → `"com.example.test"`

#### `lineAt(String s, int offset)`

**Назначение:** Преобразует смещение символа в номер строки.

**Алгоритм:**

```java
int line = 1;
for (int i = 0; i < offset; i++) {
    if (s.charAt(i) == '\n') line++;
}
return line;
```

#### `addToIndex(...)`

```java
index.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>())
    .add(new CodeElement(name, type, file, packageName, line));
```

**Особенности:**
- Ключ в нижнем регистре для case-insensitive поиска
- Один элемент может встречаться несколько раз (разные файлы/пакеты)

### Поиск по индексу

#### `findCompletions(String prefix)`

**Назначение:** Поиск элементов, начинающихся с префикса (для автодополнения).

**Алгоритм:**

```java
public List<CodeElement> findCompletions(String prefix) {
    String lowerPrefix = prefix.toLowerCase();
    List<CodeElement> results = new ArrayList<>();
    
    // 1. Поиск всех элементов, начинающихся с префикса
    for (Map.Entry<String, List<CodeElement>> entry : index.entrySet()) {
        if (entry.getKey().startsWith(lowerPrefix)) {
            results.addAll(entry.getValue());
        }
    }
    
    // 2. Сортировка: сначала по типу, потом по имени
    results.sort((a, b) -> {
        int typeCompare = a.getType().compareTo(b.getType());
        if (typeCompare != 0) return typeCompare;
        return a.getName().compareToIgnoreCase(b.getName());
    });
    
    return results;
}
```

**Использование:**

- Автодополнение в редакторе
- Go to Class/Symbol
- Find Usages

### Производительность

**Оптимизации:**

1. ✅ **ConcurrentHashMap** - потокобезопасность без блокировок
2. ✅ **Case-insensitive ключи** - быстрый поиск
3. ✅ **Ленивая индексация** - индексируется только при открытии проекта
4. ✅ **Фильтрация скрытых папок** - меньше файлов для обработки

**Ограничения:**

- ❌ Не учитывает вложенные классы
- ❌ Не учитывает generic типы
- ❌ Простые regex - могут быть false positives
- ❌ Нет инкрементального обновления (переиндексация всего проекта)

---

## Подсветка синтаксиса

### Назначение

Визуальное выделение элементов Java кода для улучшения читаемости.

### Технология

Используется **RichTextFX** (`org.fxmisc.richtext.CodeArea`) с `StyleSpans`:

```java
StyleSpans<Collection<String>> spans = computeHighlighting(text);
area.setStyleSpans(0, spans);
```

### Регулярное выражение JAVA_SYNTAX

```java
Pattern JAVA_SYNTAX = Pattern.compile(
    "(?<KEYWORD>\\b(" + String.join("|", JAVA_KEYWORDS) + ")\\b)"
    + "|(?<PAREN>[()])"
    + "|(?<BRACE>[{}])"
    + "|(?<BRACKET>[\\[\\]])"
    + "|(?<SEMICOLON>;)"
    + "|(?<STRING>\"([^\"\\\\]|\\\\.)*\")"
    + "|(?<CHAR>'([^'\\\\]|\\\\.)*')"
    + "|(?<COMMENT>//[^\\n]*|/\\*(.|\\R)*?\\*/)",
    Pattern.MULTILINE
);
```

**Группы:**

- `KEYWORD` - ключевые слова Java (50+ слов)
- `PAREN` - круглые скобки `()`
- `BRACE` - фигурные скобки `{}`
- `BRACKET` - квадратные скобки `[]`
- `SEMICOLON` - точка с запятой `;`
- `STRING` - строковые литералы `"..."` (с поддержкой escape-последовательностей)
- `CHAR` - символьные литералы `'...'`
- `COMMENT` - комментарии `//` и `/* */`

### Алгоритм `computeHighlighting(String text)`

```java
private static StyleSpans<Collection<String>> computeHighlighting(String text) {
    Matcher matcher = JAVA_SYNTAX.matcher(text);
    int lastEnd = 0;
    StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
    
    while (matcher.find()) {
        // 1. Добавляем обычный текст до совпадения
        spans.add(Collections.emptyList(), matcher.start() - lastEnd);
        
        // 2. Определяем стиль по группе
        String style = null;
        if (matcher.group("KEYWORD") != null) style = "kw";
        else if (matcher.group("STRING") != null) style = "str";
        else if (matcher.group("COMMENT") != null) style = "cmt";
        // ... другие группы
        
        // 3. Добавляем стилизованный текст
        spans.add(
            style == null ? Collections.emptyList() : Collections.singleton(style),
            matcher.end() - matcher.start()
        );
        
        lastEnd = matcher.end();
    }
    
    // 4. Добавляем оставшийся текст
    spans.add(Collections.emptyList(), text.length() - lastEnd);
    return spans.create();
}
```

**Особенности:**

- ✅ Использует `StyleSpansBuilder` для эффективного построения
- ✅ Обрабатывает весь текст за один проход
- ✅ Поддерживает перекрывающиеся стили (через merge)

### CSS стили

Файл: `src/main/resources/com/example/f_ex/ide.css`

```css
/* Light theme */
.kw { -fx-fill: #c586c0; -fx-font-weight: bold; }
.str { -fx-fill: #ce9178; }
.cmt { -fx-fill: #6a9955; }
.paren, .brace, .bracket, .semi { -fx-fill: #d4d4d4; }

/* Dark theme */
.root.dark-theme .kw { -fx-fill: #c586c0; -fx-font-weight: bold; }
.root.dark-theme .str { -fx-fill: #ce9178; }
.root.dark-theme .cmt { -fx-fill: #6a9955; }
```

### Объединение с подсветкой проблем

#### `computeHighlightingWithProblems(String text, Path file)`

**Назначение:** Объединяет синтаксическую подсветку с подсветкой ошибок/предупреждений.

**Алгоритм:**

```java
private StyleSpans<Collection<String>> computeHighlightingWithProblems(String text, Path file) {
    // 1. Базовая синтаксическая подсветка
    StyleSpans<Collection<String>> syntax = computeHighlighting(text);
    
    if (file == null) return syntax;
    
    // 2. Получение проблем для файла
    List<Problem> probs = problemsByFile.get(file);
    if (probs == null || probs.isEmpty()) return syntax;
    
    // 3. Сбор номеров строк с ошибками/предупреждениями
    Set<Integer> errLines = new HashSet<>();
    Set<Integer> warnLines = new HashSet<>();
    for (Problem p : probs) {
        if (p.line <= 0) continue;
        if ("error".equalsIgnoreCase(p.kind)) errLines.add(p.line);
        else if ("warning".equalsIgnoreCase(p.kind)) warnLines.add(p.line);
    }
    
    if (errLines.isEmpty() && warnLines.isEmpty()) return syntax;
    
    // 4. Построение overlay для проблемных строк
    StyleSpans<Collection<String>> overlay = buildLineOverlaySpans(text, errLines, warnLines);
    
    // 5. Объединение стилей
    return mergeStyleSpans(syntax, overlay);
}
```

#### `buildLineOverlaySpans(...)`

**Назначение:** Создает стили для целых строк с ошибками/предупреждениями.

**Алгоритм:**

```java
private static StyleSpans<Collection<String>> buildLineOverlaySpans(
    String text, 
    Set<Integer> errLines, 
    Set<Integer> warnLines
) {
    Map<Integer, String> lineStyle = new HashMap<>();
    for (Integer l : warnLines) lineStyle.put(l, "warnLine");
    for (Integer l : errLines) lineStyle.put(l, "errLine");  // errLine имеет приоритет
    
    StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
    int idx = 0;
    int line = 1;
    int lineStart = 0;
    
    while (idx <= text.length()) {
        boolean isEnd = idx == text.length();
        char c = isEnd ? '\n' : text.charAt(idx);
        
        if (c == '\n' || isEnd) {
            int lineEnd = idx;
            int len = Math.max(0, lineEnd - lineStart);
            String ls = lineStyle.get(line);
            
            // Добавляем стиль для всей строки
            if (len > 0) {
                spans.add(
                    ls == null ? Collections.emptyList() : Collections.singleton(ls), 
                    len
                );
            }
            if (!isEnd) spans.add(Collections.emptyList(), 1);  // символ новой строки
            
            line++;
            lineStart = idx + 1;
        }
        idx++;
    }
    
    return spans.create();
}
```

**CSS для проблемных строк:**

```css
.errLine {
    -rtfx-background-color: rgba(255, 0, 0, 0.12);  /* Красный фон */
}

.warnLine {
    -rtfx-background-color: rgba(255, 215, 0, 0.12);  /* Желтый фон */
}
```

**Примечание:** `-rtfx-background-color` - специфичное свойство RichTextFX для фона параграфа.

#### `mergeStyleSpans(...)`

**Назначение:** Объединяет два `StyleSpans` в один.

**Алгоритм:**

```java
private static StyleSpans<Collection<String>> mergeStyleSpans(
    StyleSpans<Collection<String>> a,
    StyleSpans<Collection<String>> b
) {
    // Итерация по обоим spans одновременно
    var itA = a.iterator();
    var itB = b.iterator();
    var sa = itA.hasNext() ? itA.next() : null;
    var sb = itB.hasNext() ? itB.next() : null;
    
    int ra = sa != null ? sa.getLength() : 0;
    int rb = sb != null ? sb.getLength() : 0;
    int pos = 0;
    StyleSpansBuilder<Collection<String>> merged = new StyleSpansBuilder<>();
    
    while (sa != null || sb != null) {
        int nextPos = pos;
        
        // Определяем следующую позицию
        if (sa != null && sb != null) {
            nextPos = Math.min(pos + ra, pos + rb);
        } else if (sa != null) {
            nextPos = pos + ra;
        } else {
            nextPos = pos + rb;
        }
        
        // Объединяем стили из обоих spans
        Set<String> styles = new HashSet<>();
        if (sa != null && pos < pos + ra) {
            styles.addAll(sa.getStyle());
        }
        if (sb != null && pos < pos + rb) {
            styles.addAll(sb.getStyle());
        }
        
        merged.add(new ArrayList<>(styles), nextPos - pos);
        
        // Продвигаемся дальше
        if (sa != null && pos + ra <= nextPos) {
            sa = itA.hasNext() ? itA.next() : null;
            ra = sa != null ? sa.getLength() : 0;
        }
        if (sb != null && pos + rb <= nextPos) {
            sb = itB.hasNext() ? itB.next() : null;
            rb = sb != null ? sb.getLength() : 0;
        }
        
        pos = nextPos;
    }
    
    return merged.create();
}
```

**Результат:** Стили из обоих spans объединяются (например, `kw` + `errLine` одновременно).

### Применение подсветки

**Триггеры:**

1. **При открытии файла:**
   ```java
   area.textProperty().addListener((obs, old, newText) -> {
       applyHighlighting(area);
   });
   ```

2. **При изменении текста:**
   - Автоматически через listener

3. **При обновлении проблем:**
   ```java
   scheduleDiagnostics(file, content);
   // ... после получения проблем
   refreshHighlightingForFile(file);
   ```

---

## Диагностика кода (javac)

### Назначение

Проверка Java кода на ошибки и предупреждения через компилятор `javac` в реальном времени.

### Архитектура

```
User types in CodeArea
    │
    ▼
scheduleDiagnostics(file, content)
    │
    ├─→ [Debounce 800ms]
    │
    ▼
runDiagnosticsInBackground(file, content)
    │
    ├─→ [Background Thread]
    │
    ▼
compileWithJavacAndParseProblems(file, content)
    │
    ├─→ Create temp file
    ├─→ Build javac command
    ├─→ Execute javac
    ├─→ Parse output
    └─→ Return List<Problem>
        │
        ▼
problemsByFile.put(file, problems)
    │
    ▼
[Platform.runLater]
    │
    ├─→ updateProblemsPanel()
    └─→ refreshHighlightingForFile(file)
```

### Метод `scheduleDiagnostics(Path file, String content)`

**Назначение:** Планирует диагностику с задержкой (debounce).

**Алгоритм:**

```java
private void scheduleDiagnostics(Path file, String content) {
    if (file == null) return;
    
    // 1. Отмена предыдущего таймера
    if (diagnosticsTimer != null) {
        diagnosticsTimer.stop();
    }
    
    // 2. Создание нового таймера (800ms задержка)
    diagnosticsTimer = new PauseTransition(Duration.millis(800));
    
    // 3. Действие при срабатывании
    diagnosticsTimer.setOnFinished(e -> {
        runDiagnosticsInBackground(file, content);
    });
    
    // 4. Запуск таймера
    diagnosticsTimer.play();
}
```

**Почему debounce:**

- ✅ Пользователь печатает быстро - не нужно компилировать после каждого символа
- ✅ Экономия ресурсов (меньше процессов `javac`)
- ✅ 800ms - баланс между отзывчивостью и производительностью

### Метод `runDiagnosticsInBackground(...)`

**Назначение:** Запускает диагностику в фоновом потоке.

```java
private void runDiagnosticsInBackground(Path file, String content) {
    Thread t = new Thread(() -> {
        // 1. Компиляция и парсинг проблем
        List<Problem> problems = compileWithJavacAndParseProblems(file, content);
        
        // 2. Сохранение результатов
        problemsByFile.put(file, problems);
        
        // 3. Обновление UI (в UI thread)
        Platform.runLater(() -> {
            updateProblemsPanel();
            refreshHighlightingForFile(file);
        });
    }, "javac-diagnostics");
    t.setDaemon(true);
    t.start();
}
```

**Особенности:**

- ✅ Фоновый поток - UI остается отзывчивым
- ✅ Daemon thread - не блокирует завершение приложения
- ✅ `Platform.runLater()` - обновление UI в правильном потоке

### Метод `compileWithJavacAndParseProblems(...)`

**Назначение:** Компилирует код через `javac` и парсит вывод.

**Алгоритм:**

```java
private List<Problem> compileWithJavacAndParseProblems(Path file, String content) {
    List<Problem> result = new ArrayList<>();
    
    try {
        // 1. Создание временной директории
        Path tmpDir = Files.createTempDirectory("f_ex_javac_");
        tmpDir.toFile().deleteOnExit();
        
        // 2. Создание временного файла с содержимым редактора
        Path tmpFile = tmpDir.resolve(file.getFileName().toString());
        Files.writeString(tmpFile, content, StandardCharsets.UTF_8);
        
        // 3. Построение команды javac
        List<String> cmd = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            cmd.addAll(List.of("cmd.exe", "/c", "javac"));
        } else {
            cmd.add("javac");
        }
        
        cmd.addAll(List.of(
            "-encoding", "UTF-8",      // Кодировка исходников
            "-Xlint:all",              // Все предупреждения
            "-proc:none",               // Отключить annotation processors
            "-d", tmpDir.toString()     // Output directory
        ));
        
        // 4. Добавление sourcepath из модели проекта
        if (projectModel != null && !projectModel.sourceRoots.isEmpty()) {
            cmd.add("-sourcepath");
            cmd.add(joinPaths(projectModel.sourceRoots));
        }
        
        // 5. Добавление classpath из модели проекта
        if (projectModel != null && !projectModel.classpath.isEmpty()) {
            cmd.add("-cp");
            cmd.add(joinPaths(projectModel.classpath));
        }
        
        // 6. Добавление файла для компиляции
        cmd.add(tmpFile.toString());
        
        // 7. Выполнение javac
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        
        // 8. Чтение вывода
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        p.waitFor();
        
        // 9. Парсинг вывода
        result = parseJavacOutput(out.toString(), file);
        
    } catch (Exception ignored) {
        // Игнорируем ошибки диагностики
    }
    
    return result;
}
```

**Важные моменты:**

- ✅ Использует **временный файл** - не изменяет исходники
- ✅ Использует **sourcepath и classpath** из `ProjectModel` - видит зависимости проекта
- ✅ `-Xlint:all` - все предупреждения
- ✅ `-proc:none` - быстрее (не обрабатывает аннотации)

### Парсинг вывода javac

**Формат вывода javac:**

```
<file>:<line>: <kind>: <message>
<source line>
^
```

**Пример:**

```
Test.java:10: error: cannot find symbol
    System.out.println(x);
                       ^
  symbol:   variable x
  location: class Test
```

**Регулярное выражение:**

```java
Pattern head = Pattern.compile("^(.*?):(\\d+):\\s*(error|warning|note):\\s*(.*)$");
```

**Алгоритм парсинга:**

```java
String[] lines = out.toString().split("\\R");
for (int i = 0; i < lines.length; i++) {
    String raw = lines[i].trim();
    Matcher m = head.matcher(raw);
    if (!m.matches()) continue;
    
    // Извлечение данных
    int line = safeParseInt(m.group(2));
    String kind = m.group(3);  // error, warning, note
    String msg = m.group(4);
    
    // Сбор продолжения сообщения (если javac переносит строки)
    while (i + 1 < lines.length) {
        String next = lines[i + 1];
        String nextTrim = next.trim();
        
        if (nextTrim.isEmpty()) {
            i++;
            break;
        }
        
        // Если следующая строка - новая ошибка, останавливаемся
        if (head.matcher(nextTrim).matches()) break;
        
        // Пропускаем строки с исходником и ^
        boolean looksLikeCaret = nextTrim.chars().allMatch(ch -> ch == '^');
        if (!looksLikeCaret && !nextTrim.equals(raw)) {
            // Добавляем текст в сообщение (но не исходный код)
            if (!nextTrim.contains(";") && !nextTrim.contains("{") && !nextTrim.contains("}")) {
                msg = msg + " " + nextTrim;
            }
        }
        i++;
    }
    
    result.add(new Problem(file, line, kind, msg));
}
```

**Особенности:**

- ✅ Обрабатывает многострочные сообщения
- ✅ Игнорирует строки с исходным кодом и `^`
- ✅ Извлекает дополнительную информацию из продолжения

### Класс Problem

```java
private static final class Problem {
    private final Path file;      // Файл с проблемой
    private final int line;        // Номер строки (1-based)
    private final String kind;     // "error", "warning", "note"
    private final String message;  // Сообщение об ошибке
    
    @Override
    public String toString() {
        String fn = file != null && file.getFileName() != null 
            ? file.getFileName().toString() 
            : String.valueOf(file);
        return fn + ":" + line + "  " + kind + ": " + message;
    }
}
```

---

## Problems Panel - Панель проблем

### Назначение

Отображение всех ошибок и предупреждений проекта в удобном списке с возможностью навигации.

### Компоненты UI

- **ListView** `problemsList` - список проблем
- **Labels** `errorCountLabel`, `warningCountLabel` - счетчики в правом верхнем углу

### Метод `updateProblemsPanel()`

**Назначение:** Обновляет панель проблем и счетчики.

**Алгоритм:**

```java
private void updateProblemsPanel() {
    if (problemsList == null) return;
    
    // 1. Подсчет ошибок и предупреждений
    int err = 0;
    int warn = 0;
    List<Problem> items = new ArrayList<>();
    
    for (var entry : problemsByFile.entrySet()) {
        for (Problem p : entry.getValue()) {
            if ("error".equalsIgnoreCase(p.kind)) err++;
            else if ("warning".equalsIgnoreCase(p.kind)) warn++;
            items.add(p);
        }
    }
    
    // 2. Сортировка проблем
    items.sort((a, b) -> {
        // Сначала по типу (error < warning < note)
        int ka = "error".equalsIgnoreCase(a.kind) ? 0 
                : "warning".equalsIgnoreCase(a.kind) ? 1 : 2;
        int kb = "error".equalsIgnoreCase(b.kind) ? 0 
                : "warning".equalsIgnoreCase(b.kind) ? 1 : 2;
        if (ka != kb) return Integer.compare(ka, kb);
        
        // Потом по файлу
        String fa = a.file != null ? a.file.toString() : "";
        String fb = b.file != null ? b.file.toString() : "";
        int fc = fa.compareToIgnoreCase(fb);
        if (fc != 0) return fc;
        
        // Потом по строке
        return Integer.compare(a.line, b.line);
    });
    
    // 3. Обновление UI
    problemsList.getItems().setAll(items);
    if (errorCountLabel != null) errorCountLabel.setText(String.valueOf(err));
    if (warningCountLabel != null) warningCountLabel.setText(String.valueOf(warn));
    
    // 4. Обновление статуса
    updateStatus((err == 0 && warn == 0) ? "Ready" : ("⛔ " + err + "  ⚠ " + warn));
}
```

**Сортировка:**

1. **По типу:** ошибки → предупреждения → заметки
2. **По файлу:** алфавитно
3. **По строке:** по возрастанию

### Навигация по проблемам

**Обработчик клика:**

```java
problemsList.setOnMouseClicked(e -> {
    if (e.getClickCount() == 2) {
        Problem p = problemsList.getSelectionModel().getSelectedItem();
        if (p != null && p.file != null) {
            openFileInEditor(p.file);
            // Переход к строке (если CodeArea поддерживает)
        }
    }
});
```

**Quick Fixes:**

```java
problemsList.setContextMenu(new ContextMenu(
    new MenuItem("Quick Fix...", e -> runQuickFix(problemsList.getSelectionModel().getSelectedItem()))
));
```

---

## Автодополнение (Code Completion)

### Назначение

Автоматические подсказки при вводе кода для ускорения разработки.

### Архитектура

```
User types in CodeArea
    │
    ▼
CodeArea.caretPositionProperty().addListener()
    │
    ▼
scheduleAutoComplete(area)
    │
    ├─→ [Debounce 300ms]
    │
    ▼
showCompletion(area)
    │
    ├─→ Extract prefix (current word)
    ├─→ Find suggestions:
    │   ├─→ Java keywords
    │   ├─→ Classes from index
    │   ├─→ Methods from index
    │   ├─→ Words from current file
    │   └─→ Code snippets
    │
    └─→ Show ContextMenu
        │
        └─→ User selects → insertCompletion()
```

### Метод `scheduleAutoComplete(CodeArea area)`

**Назначение:** Планирует показ автодополнения с задержкой.

```java
private void scheduleAutoComplete(CodeArea area) {
    // 1. Отмена предыдущего таймера
    if (autoCompleteTimer != null) {
        autoCompleteTimer.stop();
    }
    
    // 2. Получение задержки из настроек
    int delay = settingsManager.getInt(
        SettingsManager.KEY_AUTO_COMPLETE_DELAY, 300
    );
    
    // 3. Создание таймера
    autoCompleteTimer = new PauseTransition(Duration.millis(delay));
    
    // 4. Действие при срабатывании
    autoCompleteTimer.setOnFinished(e -> {
        showCompletion(area);
    });
    
    // 5. Запуск таймера
    autoCompleteTimer.play();
}
```

**Настраиваемая задержка:**

- По умолчанию: 300ms
- Можно изменить в Settings → Preferences

### Метод `showCompletion(CodeArea area)`

**Назначение:** Показывает меню автодополнения.

**Алгоритм:**

```java
private void showCompletion(CodeArea area) {
    if (area == null) return;
    completionMenu.getItems().clear();
    
    // 1. Извлечение префикса (текущее слово до курсора)
    String prefix = currentWordPrefix(area);
    
    // 2. Специальный случай: точка (показываем все элементы)
    if (prefix.isEmpty() && !area.getText().isEmpty()) {
        int pos = area.getCaretPosition();
        String text = area.getText();
        if (pos > 0 && text.charAt(pos - 1) == '.') {
            prefix = "";  // Показываем все доступные элементы
        } else {
            return;  // Не показываем без префикса
        }
    }
    
    List<CompletionItem> suggestions = new ArrayList<>();
    
    // 3. Ключевые слова Java
    for (String kw : JAVA_KEYWORDS) {
        if (kw.toLowerCase().startsWith(prefix.toLowerCase())) {
            suggestions.add(new CompletionItem(kw, CompletionItemType.KEYWORD, kw));
        }
    }
    
    // 4. Слова из текущего файла (локальные идентификаторы)
    if (!prefix.isEmpty()) {
        for (String w : extractWordsForCompletion(area.getText(), prefix, 60)) {
            suggestions.add(new CompletionItem(w, CompletionItemType.VARIABLE, w));
        }
    }
    
    // 5. Элементы из индекса проекта
    if (codeIndexer != null && !prefix.isEmpty()) {
        List<CodeIndexer.CodeElement> indexed = codeIndexer.findCompletions(prefix);
        for (CodeIndexer.CodeElement elem : indexed) {
            suggestions.add(new CompletionItem(
                elem.getDisplayName(), 
                CompletionItemType.fromCodeElementType(elem.getType()),
                elem.getName()
            ));
        }
    }
    
    // 6. Сниппеты кода
    String lowerPrefix = prefix.toLowerCase();
    if ("sys".startsWith(lowerPrefix) || "system".startsWith(lowerPrefix)) {
        suggestions.add(new CompletionItem(
            "System.out.println()", 
            CompletionItemType.SNIPPET, 
            "System.out.println()"
        ));
    }
    if ("main".startsWith(lowerPrefix)) {
        suggestions.add(new CompletionItem(
            "main method", 
            CompletionItemType.SNIPPET, 
            "public static void main(String[] args) {\n    \n}"
        ));
    }
    // ... другие сниппеты (for, if, etc.)
    
    // 7. Удаление дубликатов и сортировка
    suggestions = suggestions.stream()
        .distinct()
        .sorted((a, b) -> {
            int typeCompare = a.getType().compareTo(b.getType());
            if (typeCompare != 0) return typeCompare;
            return a.getText().compareToIgnoreCase(b.getText());
        })
        .limit(30)  // Максимум 30 элементов
        .toList();
    
    // 8. Показ меню
    if (suggestions.isEmpty()) {
        completionMenu.hide();
        return;
    }
    
    // 9. Создание MenuItem для каждого предложения
    final String finalPrefix = prefix;
    for (CompletionItem item : suggestions) {
        MenuItem menuItem = new MenuItem(item.getDisplayName());
        final String completion = item.getCompletion();
        menuItem.setOnAction(e -> insertCompletion(area, finalPrefix, completion));
        completionMenu.getItems().add(menuItem);
    }
    
    // 10. Показ меню рядом с курсором
    area.requestFocus();
    area.getCaretBounds().ifPresent(bounds -> {
        completionMenu.show(area, bounds.getMaxX(), bounds.getMaxY());
    });
}
```

### Извлечение префикса

#### `currentWordPrefix(CodeArea area)`

```java
private static String currentWordPrefix(CodeArea area) {
    int pos = area.getCaretPosition();
    String text = area.getText();
    if (text == null || text.isEmpty() || pos == 0) return "";
    
    // Ищем начало слова (идем назад до первого не-identifier символа)
    int start = pos;
    while (start > 0) {
        char c = text.charAt(start - 1);
        if (!Character.isJavaIdentifierPart(c)) break;
        start--;
    }
    
    return text.substring(start, pos);
}
```

**Примеры:**

- Курсор после `Sys` → префикс `"Sys"`
- Курсор после `System.` → префикс `""` (показываем все)
- Курсор в середине `println` → префикс `"println"`

### Извлечение слов из файла

#### `extractWordsForCompletion(String text, String prefix, int limit)`

**Назначение:** Извлекает идентификаторы из текущего файла для автодополнения.

**Алгоритм:**

```java
private static List<String> extractWordsForCompletion(String text, String prefix, int limit) {
    if (text == null || prefix == null || prefix.isEmpty()) return List.of();
    
    String p = prefix.toLowerCase();
    Set<String> out = new HashSet<>();
    
    // Регулярное выражение для идентификаторов Java
    Matcher m = Pattern.compile("\\b[A-Za-z_$][A-Za-z\\d_$]{2,}\\b").matcher(text);
    
    while (m.find()) {
        String w = m.group();
        if (w == null) continue;
        
        // Фильтр по префиксу
        if (!w.toLowerCase().startsWith(p)) continue;
        
        out.add(w);
        if (out.size() >= limit) break;  // Ограничение
    }
    
    // Сортировка
    List<String> list = new ArrayList<>(out);
    list.sort(String::compareToIgnoreCase);
    return list;
}
```

**Особенности:**

- ✅ Минимум 2 символа (игнорирует однобуквенные переменные)
- ✅ Case-insensitive поиск
- ✅ Удаление дубликатов через `Set`
- ✅ Ограничение количества (60 по умолчанию)

### Вставка автодополнения

#### `insertCompletion(CodeArea area, String prefix, String completion)`

```java
private static void insertCompletion(CodeArea area, String prefix, String completion) {
    int pos = area.getCaretPosition();
    int start = pos - (prefix == null ? 0 : prefix.length());
    if (start < 0) start = pos;
    
    // Замена префикса на полное автодополнение
    area.replaceText(start, pos, completion);
    
    // Установка курсора
    int caret = start + completion.length();
    
    // Специальная обработка для методов: курсор внутри скобок
    int paren = completion.indexOf("()");
    if (paren >= 0) {
        caret = start + paren + 1;
    }
    
    area.displaceCaret(caret);
}
```

**Примеры:**

- Префикс `"Sys"` → `"System"` → курсор после `System`
- Префикс `"println"` → `"println()"` → курсор внутри скобок
- Сниппет `"main method"` → вставка полного метода с курсором внутри тела

### Типы автодополнения

#### CompletionItemType

```java
private enum CompletionItemType {
    KEYWORD,      // Ключевые слова Java
    CLASS,        // Классы из индекса
    INTERFACE,    // Интерфейсы из индекса
    METHOD,       // Методы из индекса
    VARIABLE,     // Локальные переменные/слова из файла
    SNIPPET       // Сниппеты кода
}
```

**Сортировка:**

1. По типу (KEYWORD < CLASS < INTERFACE < METHOD < VARIABLE < SNIPPET)
2. По имени (алфавитно)

### Сниппеты

**Доступные сниппеты:**

- `"sys"` / `"system"` → `System.out.println()`
- `"main"` → `public static void main(String[] args) { ... }`
- `"for"` → `for (int i = 0; i < length; i++) { ... }`
- `"if"` → `if (condition) { ... }`

**Особенности:**

- ✅ Многострочные сниппеты поддерживаются
- ✅ Курсор устанавливается в нужное место (внутри тела метода/цикла)

---

## Поиск по проекту

### Find in Files

**Метод:** `onFindInFiles()` → `searchInFiles(String searchText, boolean caseSensitive)`

**Назначение:** Поиск текста во всех Java файлах проекта.

**Алгоритм:**

```java
private void searchInFiles(String searchText, boolean caseSensitive) {
    // 1. Проверка кэша
    String cacheKey = searchText + "|" + caseSensitive;
    if (searchCache.containsKey(cacheKey)) {
        // Использование кэшированных результатов
        List<Path> cached = searchCache.get(cacheKey);
        for (Path file : cached) {
            Path relative = projectRoot.relativize(file);
            logToConsole(relative.toString());
        }
        return;
    }
    
    // 2. Поиск в фоновом потоке
    Thread searchThread = new Thread(() -> {
        List<Path> foundFiles = new ArrayList<>();
        
        try {
            Files.walk(projectRoot, 20)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !CodeIndexer.shouldHidePath(p.getParent()))
                .forEach(file -> {
                    try {
                        String content = Files.readString(file, StandardCharsets.UTF_8);
                        
                        // Поиск с учетом регистра
                        boolean found = caseSensitive 
                            ? content.contains(searchText)
                            : content.toLowerCase().contains(searchText.toLowerCase());
                        
                        if (found) {
                            foundFiles.add(file);
                            
                            // Логирование в UI thread
                            Path relative = projectRoot.relativize(file);
                            Platform.runLater(() -> {
                                logToConsole(relative.toString());
                            });
                        }
                    } catch (IOException ignored) {
                    }
                });
            
            // 3. Сохранение в кэш
            searchCache.put(cacheKey, foundFiles);
            
            Platform.runLater(() -> {
                logToConsole("---");
                logToConsole("Search completed: " + foundFiles.size() + " files");
            });
            
        } catch (IOException e) {
            Platform.runLater(() -> {
                logToConsole("Search failed: " + e.getMessage());
            });
        }
    }, "file-search");
    searchThread.setDaemon(true);
    searchThread.start();
}
```

**Особенности:**

- ✅ **Кэширование** результатов поиска
- ✅ **Case-sensitive/insensitive** поиск
- ✅ **Фоновый поток** - не блокирует UI
- ✅ Вывод результатов в консоль

### Find Usages

**Метод:** `onFindUsages()` → `findUsagesInProject(String symbol)`

**Назначение:** Поиск всех использований символа в проекте.

**Алгоритм:**

```java
private void findUsagesInProject(String symbol) {
    // 1. Переключение на вкладку Search
    if (bottomPanel != null) {
        bottomPanel.setVisible(true);
        bottomPanel.setManaged(true);
    }
    if (bottomTabs != null) bottomTabs.getSelectionModel().select(2);
    
    // 2. Очистка предыдущих результатов
    searchResultsList.getItems().clear();
    updateStatus("Searching usages: " + symbol);
    
    // 3. Поиск в фоновом потоке
    Thread t = new Thread(() -> {
        List<SearchHit> hits = new ArrayList<>();
        
        // Регулярное выражение для точного совпадения слова
        Pattern pat = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
        
        try {
            Files.walk(projectRoot, 20)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !CodeIndexer.shouldHidePath(p.getParent()))
                .forEach(file -> {
                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        
                        for (int i = 0; i < lines.size(); i++) {
                            String ln = lines.get(i);
                            if (!pat.matcher(ln).find()) continue;
                            
                            // Создание предпросмотра строки
                            String prev = ln.trim();
                            if (prev.length() > 80) {
                                prev = prev.substring(0, 77) + "...";
                            }
                            
                            hits.add(new SearchHit(file, i + 1, prev));
                        }
                    } catch (IOException ignored) {
                    }
                });
            
            // 4. Обновление UI
            Platform.runLater(() -> {
                searchResultsList.getItems().setAll(hits);
                updateStatus("Found " + hits.size() + " usages");
            });
            
        } catch (IOException e) {
            Platform.runLater(() -> {
                updateStatus("Search failed: " + e.getMessage());
            });
        }
    }, "find-usages");
    t.setDaemon(true);
    t.start();
}
```

**Особенности:**

- ✅ **Word boundary** (`\b`) - точное совпадение слова (не подстрока)
- ✅ **Предпросмотр строки** - показывает контекст
- ✅ **Результаты в Search tab** - удобная навигация

#### SearchHit

```java
private static final class SearchHit {
    private final Path file;      // Файл
    private final int line;        // Номер строки (1-based)
    private final String preview;  // Предпросмотр строки
    
    @Override
    public String toString() {
        String fn = file != null && file.getFileName() != null 
            ? file.getFileName().toString() 
            : String.valueOf(file);
        return fn + ":" + line + "  " + preview;
    }
}
```

**Навигация:**

```java
searchResultsList.setOnMouseClicked(e -> {
    if (e.getClickCount() == 2) {
        SearchHit hit = searchResultsList.getSelectionModel().getSelectedItem();
        if (hit != null) {
            openFileInEditor(hit.file);
            // TODO: Переход к строке hit.line
        }
    }
});
```

---

## Навигация по коду

### Go to Class

**Метод:** `onGoToClass()` → `goToFromIndex(Set.of(CLASS, INTERFACE), "Go to Class")`

**Алгоритм:**

```java
private void goToFromIndex(Set<CodeIndexer.CodeElementType> types, String title) {
    if (codeIndexer == null) {
        updateStatus("Index not ready");
        return;
    }
    
    // 1. Создание диалога
    Dialog<CodeIndexer.CodeElement> dialog = new Dialog<>();
    dialog.setTitle(title);
    
    // 2. Создание фильтруемого списка
    TextField filter = new TextField();
    filter.setPromptText("Type class name...");
    ListView<CodeIndexer.CodeElement> list = new ListView<>();
    
    // 3. Фильтрация по типу
    List<CodeIndexer.CodeElement> all = new ArrayList<>();
    for (var entry : codeIndexer.index.entrySet()) {
        for (CodeElement elem : entry.getValue()) {
            if (types.contains(elem.getType())) {
                all.add(elem);
            }
        }
    }
    
    // 4. Реактивная фильтрация
    filter.textProperty().addListener((o, a, b) -> {
        String q = b == null ? "" : b.trim().toLowerCase();
        if (q.isEmpty()) {
            list.getItems().setAll(all);
            return;
        }
        List<CodeElement> filtered = all.stream()
            .filter(e -> e.getName().toLowerCase().contains(q))
            .limit(200)
            .toList();
        list.getItems().setAll(filtered);
    });
    
    // 5. Обработка выбора
    list.setOnMouseClicked(e -> {
        if (e.getClickCount() == 2) {
            dialog.setResult(list.getSelectionModel().getSelectedItem());
        }
    });
    
    // 6. Показ диалога
    VBox box = new VBox(8, filter, list);
    dialog.getDialogPane().setContent(box);
    
    Optional<CodeElement> picked = dialog.showAndWait();
    picked.ifPresent(elem -> {
        openFileInEditor(elem.getFile());
        // TODO: Переход к строке elem.getLine()
    });
}
```

### Go to Symbol

Аналогично `Go to Class`, но включает также `METHOD`:

```java
goToFromIndex(Set.of(CLASS, INTERFACE, METHOD), "Go to Symbol");
```

### Go to File

**Метод:** `onGoToFile()`

```java
@FXML
public void onGoToFile() {
    TextInputDialog d = new TextInputDialog();
    d.setTitle("Go to File");
    d.setContentText("File:");
    
    Optional<String> r = d.showAndWait();
    r.ifPresent(q -> {
        String s = q.trim().toLowerCase();
        if (s.isEmpty()) return;
        
        Path found = findFileByName(projectRoot, s);
        if (found != null) {
            openFileInEditor(found);
        } else {
            updateStatus("File not found");
        }
    });
}

private static Path findFileByName(Path root, String needleLower) {
    try {
        return Files.walk(root, 20)
            .filter(Files::isRegularFile)
            .filter(p -> !CodeIndexer.shouldHidePath(p.getParent()))
            .filter(p -> {
                String name = p.getFileName() != null 
                    ? p.getFileName().toString().toLowerCase() 
                    : "";
                return name.contains(needleLower);
            })
            .findFirst()
            .orElse(null);
    } catch (Exception e) {
        return null;
    }
}
```

---

## Взаимодействие компонентов

### Поток данных: Открытие файла → Индексация → Автодополнение

```
User opens file
    │
    ▼
openFileInEditor(path)
    │
    ├─→ Create CodeArea
    ├─→ Load file content
    │
    ├─→ Setup listeners:
    │   ├─→ textProperty() → applyHighlighting()
    │   └─→ caretPositionProperty() → scheduleAutoComplete()
    │
    ├─→ scheduleDiagnostics(path, content)
    │
    └─→ [If .java] codeIndexer.indexFile(path)
        │
        └─→ Index updated
            │
            └─→ Available for:
                ├─→ Autocomplete
                ├─→ Go to Class/Symbol
                └─→ Find Usages
```

### Поток данных: Редактирование → Диагностика → Подсветка

```
User types in CodeArea
    │
    ├─→ textProperty() changes
    │   └─→ applyHighlighting(area)
    │       └─→ computeHighlightingWithProblems()
    │           ├─→ computeHighlighting() [syntax]
    │           ├─→ buildLineOverlaySpans() [problems]
    │           └─→ mergeStyleSpans()
    │
    └─→ scheduleDiagnostics(file, newContent)
        │
        └─→ [After 800ms] compileWithJavacAndParseProblems()
            │
            ├─→ Execute javac
            ├─→ Parse output
            └─→ Return List<Problem>
                │
                └─→ problemsByFile.put(file, problems)
                    │
                    └─→ [Platform.runLater]
                        ├─→ updateProblemsPanel()
                        └─→ refreshHighlightingForFile(file)
                            └─→ applyHighlighting() [with problems]
```

### Поток данных: Автодополнение

```
User types "Sys"
    │
    ▼
caretPositionProperty() changes
    │
    ▼
scheduleAutoComplete(area)
    │
    └─→ [After 300ms] showCompletion(area)
        │
        ├─→ currentWordPrefix() → "Sys"
        │
        ├─→ Filter JAVA_KEYWORDS → []
        │
        ├─→ codeIndexer.findCompletions("Sys")
        │   └─→ Returns: [System class, ...]
        │
        ├─→ extractWordsForCompletion() → []
        │
        └─→ Show ContextMenu with suggestions
            │
            └─→ User selects "System"
                │
                └─→ insertCompletion(area, "Sys", "System")
                    └─→ Replace "Sys" → "System"
```

---

## Резюме

### Ключевые особенности Editor & Code Analysis Layer:

1. ✅ **Индексация** - быстрый поиск классов, методов, интерфейсов
2. ✅ **Подсветка синтаксиса** - визуальное выделение элементов кода
3. ✅ **Реальная диагностика** - проверка через `javac` с использованием classpath проекта
4. ✅ **Автодополнение** - подсказки из ключевых слов, индекса, локальных слов, сниппетов
5. ✅ **Поиск** - Find in Files и Find Usages
6. ✅ **Навигация** - Go to Class/Symbol/File

### Производительность:

- ✅ **Debounce** для частых операций (300ms автодополнение, 800ms диагностика)
- ✅ **Фоновые потоки** для тяжелых операций
- ✅ **Кэширование** результатов поиска
- ✅ **ConcurrentHashMap** для потокобезопасности

### Ограничения и улучшения:

1. **Индексация:**
   - Не учитывает вложенные классы
   - Простые regex (могут быть false positives)
   - Нет инкрементального обновления

2. **Подсветка синтаксиса:**
   - Только базовые элементы Java
   - Нет подсветки импортов, аннотаций
   - Нет семантической подсветки (типы переменных)

3. **Диагностика:**
   - Компилирует только текущий файл (не весь проект)
   - Не учитывает зависимости между файлами
   - Нет инкрементальной компиляции

4. **Автодополнение:**
   - Нет автодополнения полей/методов объектов
   - Нет автодополнения параметров методов
   - Простые сниппеты

---

**Версия:** 1.0  
**Дата:** 2026-01-20  
**Файл:** `Documents/03-Editor-Code-Analysis.md`
