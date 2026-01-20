# Детальная документация: Editor & Code Analysis Layer

## 📋 Содержание

1. [Обзор слоя](#обзор-слоя)
2. [Syntax Highlighting - Подсветка синтаксиса](#syntax-highlighting---подсветка-синтаксиса)
3. [Autocompletion - Автодополнение](#autocompletion---автодополнение)
4. [Diagnostics - Диагностика кода](#diagnostics---диагностика-кода)
5. [Problems Panel - Панель проблем](#problems-panel---панель-проблем)
6. [Quick Fixes - Быстрые исправления](#quick-fixes---быстрые-исправления)
7. [Сценарии использования](#сценарии-использования)
8. [Ограничения и улучшения](#ограничения-и-улучшения)

---

## Обзор слоя

**Editor & Code Analysis Layer** предоставляет **продвинутые возможности редактирования** и **анализа кода**:

- ✅ **Syntax Highlighting** - подсветка синтаксиса Java
- ✅ **Autocompletion** - автодополнение кода (ключевые слова, индексы, сниппеты)
- ✅ **Real-time Diagnostics** - диагностика ошибок и предупреждений через `javac`
- ✅ **Problems Panel** - панель с ошибками и предупреждениями
- ✅ **Quick Fixes** - быстрые исправления (удаление неиспользуемых импортов, добавление импортов)
- ✅ **Visual Problem Highlighting** - визуальная подсветка проблемных строк в редакторе

### Компоненты слоя:

```
Editor & Code Analysis Layer
├── RichTextFX CodeArea      # Редактор кода
├── Syntax Highlighting      # Подсветка синтаксиса
├── Autocompletion System    # Автодополнение
├── Diagnostics Engine       # Диагностика через javac
└── Problems Panel           # UI для проблем
```

### Архитектура:

```
┌─────────────────┐
│   CodeArea      │
│  (RichTextFX)   │
└────────┬────────┘
         │
         ├─→ Syntax Highlighting ──→ StyleSpans ──→ CSS Classes
         │
         ├─→ Autocompletion ──→ CodeIndexer ──→ Suggestions
         │
         ├─→ Diagnostics ──→ javac ──→ Problems
         │
         └─→ Problem Highlighting ──→ Overlay Spans
```

---

## Syntax Highlighting - Подсветка синтаксиса

### Назначение

**Syntax Highlighting** использует **RichTextFX** для подсветки синтаксиса Java кода с помощью CSS классов.

### Технология

- **RichTextFX** - библиотека для редактирования кода
- **StyleSpans** - структура для применения стилей к тексту
- **CSS Classes** - стили для разных типов токенов

### Regex паттерн

```java
private static final Pattern JAVA_SYNTAX = Pattern.compile(
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

**Named groups:**

- `KEYWORD` - ключевые слова Java
- `PAREN` - скобки `()`
- `BRACE` - фигурные скобки `{}`
- `BRACKET` - квадратные скобки `[]`
- `SEMICOLON` - точка с запятой `;`
- `STRING` - строковые литералы `"..."`
- `CHAR` - символьные литералы `'...'`
- `COMMENT` - комментарии `//` и `/* */`

### Алгоритм `computeHighlighting(String text)`

**Назначение:** Вычисляет стили для всего текста.

**Алгоритм:**

```java
private static StyleSpans<Collection<String>> computeHighlighting(String text) {
    Matcher matcher = JAVA_SYNTAX.matcher(text);
    int lastEnd = 0;
    StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
    
    while (matcher.find()) {
        // Определение стиля по named group
        String style =
            matcher.group("KEYWORD") != null ? "kw" :
            matcher.group("PAREN") != null ? "paren" :
            matcher.group("BRACE") != null ? "brace" :
            matcher.group("BRACKET") != null ? "bracket" :
            matcher.group("SEMICOLON") != null ? "semi" :
            matcher.group("STRING") != null ? "str" :
            matcher.group("CHAR") != null ? "chr" :
            matcher.group("COMMENT") != null ? "cmt" :
            null;
        
        // Добавление обычного текста до совпадения
        spans.add(Collections.emptyList(), matcher.start() - lastEnd);
        
        // Добавление стилизованного текста
        spans.add(
            style == null ? Collections.emptyList() : Collections.singleton(style),
            matcher.end() - matcher.start()
        );
        
        lastEnd = matcher.end();
    }
    
    // Добавление оставшегося текста
    spans.add(Collections.emptyList(), text.length() - lastEnd);
    return spans.create();
}
```

**Особенности:**

- ✅ Использует `StyleSpansBuilder` для построения стилей
- ✅ Обрабатывает все совпадения regex паттерна
- ✅ Добавляет обычный текст между совпадениями

### CSS стили

**Файл:** `src/main/resources/com/example/f_ex/ide.css`

```css
/* Light theme (default) */
.kw { -fx-fill: #c586c0; -fx-font-weight: bold; }
.paren { -fx-fill: #d4d4d4; }
.brace { -fx-fill: #d4d4d4; }
.bracket { -fx-fill: #d4d4d4; }
.semi { -fx-fill: #d4d4d4; }
.str { -fx-fill: #ce9178; }
.chr { -fx-fill: #ce9178; }
.cmt { -fx-fill: #6a9955; }

/* Dark theme */
.root.dark-theme .kw { -fx-fill: #c586c0; -fx-font-weight: bold; }
.root.dark-theme .str { -fx-fill: #ce9178; }
.root.dark-theme .cmt { -fx-fill: #6a9955; }
```

**Цвета:**

- **Keywords** - фиолетовый (`#c586c0`)
- **Strings** - оранжевый (`#ce9178`)
- **Comments** - зеленый (`#6a9955`)
- **Punctuation** - серый (`#d4d4d4`)

### Применение подсветки

#### `applyHighlighting(CodeArea area)`

**Назначение:** Применяет подсветку синтаксиса и проблем к редактору.

**Алгоритм:**

```java
private void applyHighlighting(CodeArea area) {
    if (area == null) return;
    String text = area.getText();
    
    // Определение файла для подсветки проблем
    Path file = null;
    Tab tab = editorTabs.getSelectionModel().getSelectedItem();
    if (tab != null) {
        EditorTabData data = (EditorTabData) tab.getUserData();
        if (data != null && data.editor == area) {
            file = data.path;
        }
    }
    
    // Применение подсветки с проблемами
    area.setStyleSpans(0, computeHighlightingWithProblems(text, file));
}
```

**Особенности:**

- ✅ Определяет файл по текущей вкладке
- ✅ Объединяет синтаксическую подсветку с подсветкой проблем
- ✅ Применяется при каждом изменении текста

### Объединение с подсветкой проблем

#### `computeHighlightingWithProblems(String text, Path file)`

**Назначение:** Объединяет синтаксическую подсветку с подсветкой проблем.

**Алгоритм:**

```java
private StyleSpans<Collection<String>> computeHighlightingWithProblems(String text, Path file) {
    // 1. Базовая синтаксическая подсветка
    StyleSpans<Collection<String>> syntax = computeHighlighting(text);
    if (file == null) return syntax;
    
    // 2. Получение проблем для файла
    List<Problem> probs = problemsByFile.get(file);
    if (probs == null || probs.isEmpty()) return syntax;
    
    // 3. Сбор строк с ошибками и предупреждениями
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
    
    // 5. Объединение синтаксической подсветки и overlay
    return mergeStyleSpans(syntax, overlay);
}
```

**Особенности:**

- ✅ Сначала применяется синтаксическая подсветка
- ✅ Затем накладывается overlay для проблемных строк
- ✅ Объединяются стили через `mergeStyleSpans()`

#### `buildLineOverlaySpans(String text, Set<Integer> errLines, Set<Integer> warnLines)`

**Назначение:** Создает overlay для проблемных строк.

**Алгоритм:**

```java
private static StyleSpans<Collection<String>> buildLineOverlaySpans(
    String text, 
    Set<Integer> errLines, 
    Set<Integer> warnLines
) {
    Map<Integer, String> lineStyle = new HashMap<>();
    for (Integer l : warnLines) lineStyle.put(l, "warnLine");
    for (Integer l : errLines) lineStyle.put(l, "errLine");  // Ошибки имеют приоритет
    
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
            
            // Добавление стиля для строки
            if (len > 0) {
                spans.add(
                    ls == null ? Collections.emptyList() : Collections.singleton(ls), 
                    len
                );
            }
            if (!isEnd) spans.add(Collections.emptyList(), 1);  // \n
            
            line++;
            lineStart = idx + 1;
        }
        idx++;
    }
    
    return spans.create();
}
```

**CSS стили для проблем:**

```css
.errLine {
    -rtfx-background-color: rgba(255, 0, 0, 0.12);  /* Красный фон */
}

.warnLine {
    -rtfx-background-color: rgba(255, 215, 0, 0.12);  /* Желтый фон */
}
```

#### `mergeStyleSpans(...)`

**Назначение:** Объединяет два `StyleSpans` в один.

**Алгоритм:**

```java
private static StyleSpans<Collection<String>> mergeStyleSpans(
    StyleSpans<Collection<String>> a,  // Синтаксическая подсветка
    StyleSpans<Collection<String>> b   // Overlay проблем
) {
    var itA = a.iterator();
    var itB = b.iterator();
    var sa = itA.hasNext() ? itA.next() : null;
    var sb = itB.hasNext() ? itB.next() : null;
    int ra = sa != null ? sa.getLength() : 0;
    int rb = sb != null ? sb.getLength() : 0;
    
    StyleSpansBuilder<Collection<String>> out = new StyleSpansBuilder<>();
    
    while (sa != null && sb != null) {
        int len = Math.min(ra, rb);
        
        // Объединение стилей
        List<String> merged = new ArrayList<>(sa.getStyle());
        for (String s : sb.getStyle()) {
            if (!merged.contains(s)) merged.add(s);
        }
        
        out.add(merged, len);
        
        ra -= len;
        rb -= len;
        if (ra == 0) { sa = itA.hasNext() ? itA.next() : null; ra = sa != null ? sa.getLength() : 0; }
        if (rb == 0) { sb = itB.hasNext() ? itB.next() : null; rb = sb != null ? sb.getLength() : 0; }
    }
    
    return out.create();
}
```

**Особенности:**

- ✅ Объединяет стили из обоих spans
- ✅ Удаляет дубликаты стилей
- ✅ Обрабатывает разные длины spans

---

## Autocompletion - Автодополнение

### Назначение

**Autocompletion** предоставляет **автоматическое дополнение кода** на основе:
- Ключевых слов Java
- Индекса проекта (классы, методы, интерфейсы)
- Слов из текущего файла
- Сниппетов (шаблонов кода)

### Триггеры автодополнения

1. **Автоматический** - при вводе буквы/цифры/точки (debounce 300ms)
2. **Принудительный** - `Ctrl+Space`

### Алгоритм `scheduleAutoComplete(CodeArea area)`

**Назначение:** Планирует показ автодополнения с задержкой.

**Алгоритм:**

```java
private void scheduleAutoComplete(CodeArea area) {
    if (autoCompleteTimer != null) {
        autoCompleteTimer.stop();  // Отмена предыдущего таймера
    }
    
    // Создание таймера с настраиваемой задержкой
    autoCompleteTimer = new PauseTransition(
        Duration.millis(settingsManager.getInt(SettingsManager.KEY_AUTO_COMPLETE_DELAY, 300))
    );
    
    autoCompleteTimer.setOnFinished(e -> {
        if (completionMenu.isShowing()) return;  // Уже показано
        showCompletion(area);
    });
    
    autoCompleteTimer.play();
}
```

**Особенности:**

- ✅ Debounce - отменяет предыдущий таймер при новом вводе
- ✅ Настраиваемая задержка (по умолчанию 300ms)
- ✅ Проверяет, не показано ли уже меню

### Алгоритм `showCompletion(CodeArea area)`

**Назначение:** Показывает меню автодополнения.

**Алгоритм:**

```java
private void showCompletion(CodeArea area) {
    if (area == null) return;
    completionMenu.getItems().clear();
    
    // 1. Извлечение префикса текущего слова
    String prefix = currentWordPrefix(area);
    if (prefix.isEmpty() && !area.getText().isEmpty()) {
        // Если нет префикса, но есть точка - ищем методы/поля класса
        int pos = area.getCaretPosition();
        String text = area.getText();
        if (pos > 0 && text.charAt(pos - 1) == '.') {
            prefix = "";  // Показываем все доступные элементы
        } else {
            return;  // Не показываем автодополнение без префикса
        }
    }
    
    List<CompletionItem> suggestions = new ArrayList<>();
    
    // 2. Ключевые слова Java
    for (String kw : JAVA_KEYWORDS) {
        if (kw.toLowerCase().startsWith(prefix.toLowerCase())) {
            suggestions.add(new CompletionItem(kw, CompletionItemType.KEYWORD, kw));
        }
    }
    
    // 3. Слова из текущего файла (локальные идентификаторы)
    if (!prefix.isEmpty()) {
        for (String w : extractWordsForCompletion(area.getText(), prefix, 60)) {
            suggestions.add(new CompletionItem(w, CompletionItemType.VARIABLE, w));
        }
    }
    
    // 4. Элементы из индекса проекта
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
    
    // 5. Сниппеты
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
    if ("for".startsWith(lowerPrefix)) {
        suggestions.add(new CompletionItem(
            "for loop", 
            CompletionItemType.SNIPPET, 
            "for (int i = 0; i < length; i++) {\n    \n}"
        ));
    }
    if ("if".startsWith(lowerPrefix)) {
        suggestions.add(new CompletionItem(
            "if statement", 
            CompletionItemType.SNIPPET, 
            "if (condition) {\n    \n}"
        ));
    }
    
    // 6. Удаление дубликатов и сортировка
    suggestions = suggestions.stream()
        .distinct()
        .sorted((a, b) -> {
            int typeCompare = a.getType().compareTo(b.getType());
            if (typeCompare != 0) return typeCompare;
            return a.getText().compareToIgnoreCase(b.getText());
        })
        .limit(30)  // Ограничение результатов
        .toList();
    
    if (suggestions.isEmpty()) {
        completionMenu.hide();
        return;
    }
    
    // 7. Создание меню
    final String finalPrefix = prefix;
    for (CompletionItem item : suggestions) {
        MenuItem menuItem = new MenuItem(item.getDisplayName());
        final String completion = item.getCompletion();
        menuItem.setOnAction(e -> insertCompletion(area, finalPrefix, completion));
        completionMenu.getItems().add(menuItem);
    }
    
    // 8. Показ меню
    area.requestFocus();
    area.getCaretBounds().ifPresent(bounds -> {
        completionMenu.show(area, bounds.getMaxX(), bounds.getMaxY());
    });
}
```

**Источники предложений:**

1. **Ключевые слова Java** - `public`, `class`, `void`, и т.д.
2. **Слова из текущего файла** - локальные переменные, методы
3. **Индекс проекта** - классы, интерфейсы, методы из `CodeIndexer`
4. **Сниппеты** - шаблоны кода (`main`, `for`, `if`, `System.out.println()`)

### Вспомогательные методы

#### `currentWordPrefix(CodeArea area)`

**Назначение:** Извлекает префикс текущего слова под курсором.

```java
private static String currentWordPrefix(CodeArea area) {
    int pos = area.getCaretPosition();
    String text = area.getText();
    if (text == null || text.isEmpty() || pos == 0) return "";
    
    int start = pos;
    while (start > 0) {
        char c = text.charAt(start - 1);
        if (!Character.isJavaIdentifierPart(c)) break;
        start--;
    }
    
    return text.substring(start, pos);
}
```

**Пример:**

```
Текст: "public class Main {"
Курсор: после "Mai"

Результат: "Mai"
```

#### `extractWordsForCompletion(String text, String prefix, int limit)`

**Назначение:** Извлекает слова из текста, начинающиеся с префикса.

```java
private static List<String> extractWordsForCompletion(String text, String prefix, int limit) {
    if (text == null || prefix == null || prefix.isEmpty()) return List.of();
    String p = prefix.toLowerCase();
    Set<String> out = new HashSet<>();
    
    // Поиск всех идентификаторов
    Matcher m = Pattern.compile("\\b[A-Za-z_$][A-Za-z\\d_$]{2,}\\b").matcher(text);
    while (m.find()) {
        String w = m.group();
        if (w == null) continue;
        if (!w.toLowerCase().startsWith(p)) continue;
        out.add(w);
        if (out.size() >= limit) break;
    }
    
    List<String> list = new ArrayList<>(out);
    list.sort(String::compareToIgnoreCase);
    return list;
}
```

**Особенности:**

- ✅ Минимальная длина слова: 2 символа
- ✅ Case-insensitive поиск
- ✅ Ограничение количества результатов

#### `insertCompletion(CodeArea area, String prefix, String completion)`

**Назначение:** Вставляет автодополнение в редактор.

```java
private static void insertCompletion(CodeArea area, String prefix, String completion) {
    int pos = area.getCaretPosition();
    int start = pos - (prefix == null ? 0 : prefix.length());
    if (start < 0) start = pos;
    
    // Замена префикса на полное дополнение
    area.replaceText(start, pos, completion);
    
    // Установка курсора внутри скобок (если есть)
    int caret = start + completion.length();
    int paren = completion.indexOf("()");
    if (paren >= 0) {
        caret = start + paren + 1;  // Внутри скобок
    }
    area.displaceCaret(caret);
}
```

**Особенности:**

- ✅ Заменяет префикс на полное дополнение
- ✅ Устанавливает курсор внутри скобок для сниппетов
- ✅ Работает с многострочными сниппетами

### CompletionItem

**Назначение:** Представляет элемент автодополнения.

```java
private static class CompletionItem {
    private final String displayName;    // Отображаемое имя
    private final CompletionItemType type;  // Тип
    private final String completion;    // Текст для вставки
    
    // Getters...
}
```

### CompletionItemType

```java
private enum CompletionItemType {
    KEYWORD,    // Ключевое слово
    CLASS,      // Класс
    INTERFACE,  // Интерфейс
    METHOD,     // Метод
    VARIABLE,   // Переменная
    SNIPPET     // Сниппет
}
```

---

## Diagnostics - Диагностика кода

### Назначение

**Diagnostics** выполняет **real-time анализ кода** через `javac` для обнаружения ошибок и предупреждений.

### Алгоритм `scheduleDiagnostics(Path file, String content)`

**Назначение:** Планирует диагностику с задержкой (debounce).

**Алгоритм:**

```java
private void scheduleDiagnostics(Path file, String content) {
    if (file == null) return;
    if (diagnosticsTimer != null) diagnosticsTimer.stop();
    
    // Debounce 800ms
    diagnosticsTimer = new PauseTransition(Duration.millis(800));
    diagnosticsTimer.setOnFinished(e -> runDiagnosticsInBackground(file, content));
    diagnosticsTimer.play();
}
```

**Особенности:**

- ✅ Debounce 800ms - предотвращает частые запуски
- ✅ Отменяет предыдущий таймер при новом изменении

### Алгоритм `runDiagnosticsInBackground(Path file, String content)`

**Назначение:** Запускает диагностику в фоновом потоке.

**Алгоритм:**

```java
private void runDiagnosticsInBackground(Path file, String content) {
    if (file == null) return;
    
    Thread t = new Thread(() -> {
        // Компиляция и парсинг проблем
        List<Problem> problems = compileWithJavacAndParseProblems(file, content);
        
        // Сохранение проблем
        problemsByFile.put(file, problems);
        
        // Обновление UI
        Platform.runLater(() -> updateProblemsPanel());
    }, "javac-diagnostics");
    t.setDaemon(true);
    t.start();
}
```

**Особенности:**

- ✅ Выполняется в фоновом потоке (не блокирует UI)
- ✅ Обновляет UI через `Platform.runLater()`

### Алгоритм `compileWithJavacAndParseProblems(Path file, String content)`

**Назначение:** Компилирует файл через `javac` и парсит вывод.

**Алгоритм:**

```java
private List<Problem> compileWithJavacAndParseProblems(Path file, String content) {
    List<Problem> result = new ArrayList<>();
    try {
        // 1. Создание временной директории
        Path tmpDir = Files.createTempDirectory("f_ex_javac_");
        tmpDir.toFile().deleteOnExit();
        
        // 2. Сохранение содержимого во временный файл
        Path tmpFile = tmpDir.resolve(file.getFileName().toString());
        Files.writeString(tmpFile, content == null ? "" : content, 
            StandardCharsets.UTF_8, 
            StandardOpenOption.CREATE, 
            StandardOpenOption.TRUNCATE_EXISTING
        );
        
        // 3. Формирование команды javac
        List<String> cmd = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        if (isWindows) {
            cmd.addAll(List.of("cmd.exe", "/c", "javac"));
        } else {
            cmd.add("javac");
        }
        
        cmd.addAll(List.of(
            "-encoding", "UTF-8",
            "-Xlint:all",        // Все предупреждения
            "-proc:none",        // Отключить обработку аннотаций
            "-d", tmpDir.toString()
        ));
        
        // 4. Добавление sourcepath и classpath из проекта
        if (projectModel != null && !projectModel.sourceRoots.isEmpty()) {
            cmd.add("-sourcepath");
            cmd.add(joinPaths(projectModel.sourceRoots));
        }
        if (projectModel != null && !projectModel.classpath.isEmpty()) {
            cmd.add("-cp");
            cmd.add(joinPaths(projectModel.classpath));
        }
        
        cmd.add(tmpFile.toString());
        
        // 5. Запуск javac
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        
        // 6. Чтение вывода
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        p.waitFor();
        
        // 7. Парсинг вывода javac
        Pattern head = Pattern.compile("^(.*?):(\\d+):\\s*(error|warning|note):\\s*(.*)$");
        String[] lines = out.toString().split("\\R");
        
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            Matcher m = head.matcher(raw);
            if (!m.matches()) continue;
            
            int ln = safeParseInt(m.group(2));
            String kind = m.group(3);
            String msg = m.group(4);
            
            // Обработка многострочных сообщений
            while (i + 1 < lines.length) {
                String next = lines[i + 1];
                String nextTrim = next.trim();
                if (nextTrim.isEmpty()) {
                    i++;
                    break;
                }
                if (head.matcher(nextTrim).matches()) break;  // Новая проблема
                
                // Пропускаем строки с исходником и ^
                boolean looksLikeCaret = nextTrim.chars().allMatch(ch -> ch == '^');
                if (!looksLikeCaret && !nextTrim.equals(lines[i].trim())) {
                    if (!nextTrim.startsWith(tmpFile.toString()) && 
                        !nextTrim.startsWith(file.toString())) {
                        // Добавляем продолжение сообщения (если не код)
                        if (!nextTrim.contains(";") && 
                            !nextTrim.contains("{") && 
                            !nextTrim.contains("}")) {
                            msg = msg + " " + nextTrim;
                        }
                    }
                }
                i++;
            }
            
            result.add(new Problem(file, ln, kind, msg));
        }
    } catch (Exception ignored) {
        // Игнорируем ошибки
    }
    return result;
}
```

**Формат вывода javac:**

```
Main.java:10: error: cannot find symbol
    System.out.println(x);
                       ^
  symbol:   variable x
  location: class Main
```

**Regex паттерн:**

```regex
^(.*?):(\d+):\s*(error|warning|note):\s*(.*)$
```

- `(.*?)` - имя файла
- `(\d+)` - номер строки
- `(error|warning|note)` - тип проблемы
- `(.*)` - сообщение

**Особенности:**

- ✅ Использует временный файл (не изменяет исходный)
- ✅ Учитывает `sourcepath` и `classpath` из проекта
- ✅ Парсит многострочные сообщения
- ✅ Игнорирует строки с исходным кодом и `^`

---

## Problems Panel - Панель проблем

### Назначение

**Problems Panel** отображает все ошибки и предупреждения проекта в одном месте.

### Структура Problem

```java
private static final class Problem {
    private final Path file;      // Файл
    private final int line;       // Номер строки (1-based)
    private final String kind;    // error|warning|note
    private final String message; // Сообщение
    
    @Override
    public String toString() {
        String fn = file != null && file.getFileName() != null 
            ? file.getFileName().toString() 
            : String.valueOf(file);
        return fn + ":" + line + " [" + kind + "] " + message;
    }
}
```

### Алгоритм `updateProblemsPanel()`

**Назначение:** Обновляет панель проблем.

**Алгоритм:**

```java
private void updateProblemsPanel() {
    if (problemsList == null) return;
    
    int err = 0;
    int warn = 0;
    List<Problem> items = new ArrayList<>();
    
    // 1. Сбор всех проблем из всех файлов
    for (var entry : problemsByFile.entrySet()) {
        for (Problem p : entry.getValue()) {
            if ("error".equalsIgnoreCase(p.kind)) err++;
            else if ("warning".equalsIgnoreCase(p.kind)) warn++;
            items.add(p);
        }
    }
    
    // 2. Сортировка проблем
    items.sort((a, b) -> {
        // Сначала по типу (error > warning > note)
        int ka = "error".equalsIgnoreCase(a.kind) ? 0 
               : "warning".equalsIgnoreCase(a.kind) ? 1 : 2;
        int kb = "error".equalsIgnoreCase(b.kind) ? 0 
               : "warning".equalsIgnoreCase(b.kind) ? 1 : 2;
        if (ka != kb) return Integer.compare(ka, kb);
        
        // Затем по файлу
        String fa = a.file != null ? a.file.toString() : "";
        String fb = b.file != null ? b.file.toString() : "";
        int fc = fa.compareToIgnoreCase(fb);
        if (fc != 0) return fc;
        
        // Затем по строке
        return Integer.compare(a.line, b.line);
    });
    
    // 3. Обновление UI
    problemsList.getItems().setAll(items);
    if (errorCountLabel != null) errorCountLabel.setText(String.valueOf(err));
    if (warningCountLabel != null) warningCountLabel.setText(String.valueOf(warn));
    updateStatus((err == 0 && warn == 0) ? "Ready" : ("⛔ " + err + "  ⚠ " + warn));
}
```

**Сортировка:**

1. **По типу:** error → warning → note
2. **По файлу:** алфавитно
3. **По строке:** по возрастанию

**Особенности:**

- ✅ Агрегирует проблемы из всех файлов
- ✅ Подсчитывает ошибки и предупреждения
- ✅ Обновляет счетчики в UI (errorCountLabel, warningCountLabel)

### Обработчик клика на проблему

```java
if (problemsList != null) {
    problemsList.setOnMouseClicked(e -> {
        if (e.getClickCount() != 1) return;
        Problem p = problemsList.getSelectionModel().getSelectedItem();
        if (p == null || p.file == null) return;
        
        // Открытие файла и переход к строке
        openFileAndGoTo(p.file, p.line);
    });
}
```

**Особенности:**

- ✅ Клик на проблеме открывает файл и переходит к строке
- ✅ Использует `openFileAndGoTo()` для навигации

### Context Menu

```java
ContextMenu cm = new ContextMenu();
MenuItem quickFix = new MenuItem("Quick Fix");
MenuItem copy = new MenuItem("Copy Message");
cm.getItems().addAll(quickFix, copy);

quickFix.setOnAction(ev -> {
    Problem p = problemsList.getSelectionModel().getSelectedItem();
    if (p == null) return;
    runQuickFix(p);
});

copy.setOnAction(ev -> {
    Problem p = problemsList.getSelectionModel().getSelectedItem();
    if (p == null) return;
    ClipboardContent c = new ClipboardContent();
    c.putString(p.toString());
    Clipboard.getSystemClipboard().setContent(c);
});

problemsList.setContextMenu(cm);
```

**Действия:**

- **Quick Fix** - быстрое исправление проблемы
- **Copy Message** - копирование сообщения в буфер обмена

---

## Quick Fixes - Быстрые исправления

### Назначение

**Quick Fixes** предоставляет **автоматические исправления** для некоторых типов проблем.

### Поддерживаемые Quick Fixes

1. **Удаление неиспользуемых импортов**
2. **Добавление импортов** (для "cannot find symbol")

### Алгоритм `runQuickFix(Problem p)`

**Назначение:** Выполняет быстрое исправление для проблемы.

**Алгоритм:**

```java
private void runQuickFix(Problem p) {
    if (p == null || p.file == null) return;
    
    String msg = p.message == null ? "" : p.message;
    
    // 1. Удаление неиспользуемого импорта
    if ("warning".equalsIgnoreCase(p.kind) && 
        msg.toLowerCase().contains("import") && 
        msg.toLowerCase().contains("never used")) {
        
        String imp = extractUnusedImportFqn(msg);
        if (imp == null) {
            updateStatus("Quick fix not available");
            return;
        }
        
        boolean ok = removeUnusedImport(p.file, imp);
        if (ok) {
            updateStatus("Removed unused import: " + imp);
            refreshDiagnosticsForOpenTabIfAny(p.file);
        } else {
            updateStatus("Failed to apply quick fix");
        }
        return;
    }
    
    // 2. Добавление импорта (для "cannot find symbol")
    if ("error".equalsIgnoreCase(p.kind) && 
        msg.toLowerCase().contains("cannot find symbol")) {
        
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Add import");
        d.setHeaderText("Add import (enter full qualified name)");
        d.setContentText("import:");
        Optional<String> r = d.showAndWait();
        
        r.ifPresent(fqn -> {
            String s = fqn == null ? "" : fqn.trim();
            if (s.isEmpty()) return;
            
            if (addImport(p.file, s)) {
                updateStatus("Added import: " + s);
                refreshDiagnosticsForOpenTabIfAny(p.file);
            } else {
                updateStatus("Failed to add import");
            }
        });
        return;
    }
    
    updateStatus("Quick fix not available");
}
```

### `extractUnusedImportFqn(String message)`

**Назначение:** Извлекает полное имя неиспользуемого импорта из сообщения.

**Пример сообщения:**

```
Main.java:3: warning: [unused] import java.util.List is never used
```

**Алгоритм:**

```java
private String extractUnusedImportFqn(String message) {
    // Парсинг сообщения вида:
    // "import java.util.List is never used"
    // или
    // "[unused] import java.util.List is never used"
    
    Pattern p = Pattern.compile("import\\s+([\\w.]+)\\s+is\\s+never\\s+used");
    Matcher m = p.matcher(message);
    if (m.find()) {
        return m.group(1);
    }
    return null;
}
```

### `removeUnusedImport(Path file, String importFqn)`

**Назначение:** Удаляет неиспользуемый импорт из файла.

**Алгоритм:**

```java
private boolean removeUnusedImport(Path file, String importFqn) {
    try {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        
        // Поиск строки импорта
        String importLine = "import " + importFqn + ";";
        String importLineWithWildcard = "import " + importFqn + ".*;";
        
        // Удаление строки импорта
        String newContent = content.replace(importLine + "\n", "")
                                   .replace(importLineWithWildcard + "\n", "")
                                   .replace(importLine, "")
                                   .replace(importLineWithWildcard, "");
        
        if (newContent.equals(content)) {
            return false;  // Импорт не найден
        }
        
        // Сохранение файла
        Files.writeString(file, newContent, StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        
        // Обновление открытого редактора
        Tab tab = openTabsByPath.get(file.normalize().toAbsolutePath());
        if (tab != null) {
            EditorTabData data = (EditorTabData) tab.getUserData();
            if (data != null && data.editor != null) {
                data.editor.replaceText(newContent);
                applyHighlighting(data.editor);
            }
        }
        
        return true;
    } catch (Exception e) {
        return false;
    }
}
```

### `addImport(Path file, String importFqn)`

**Назначение:** Добавляет импорт в файл.

**Алгоритм:**

```java
private boolean addImport(Path file, String importFqn) {
    try {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        
        // Проверка, не добавлен ли уже импорт
        if (content.contains("import " + importFqn + ";")) {
            return false;  // Уже есть
        }
        
        // Поиск места для вставки (после package, перед классом)
        int packageEnd = content.indexOf(";", content.indexOf("package"));
        int classStart = content.indexOf("class ");
        int interfaceStart = content.indexOf("interface ");
        int enumStart = content.indexOf("enum ");
        
        int insertPos = Math.max(packageEnd, 0);
        if (classStart > 0 && classStart < insertPos) insertPos = classStart;
        if (interfaceStart > 0 && interfaceStart < insertPos) insertPos = interfaceStart;
        if (enumStart > 0 && enumStart < insertPos) insertPos = enumStart;
        
        // Вставка импорта
        String importLine = "import " + importFqn + ";\n";
        String newContent = content.substring(0, insertPos + 1) + 
                           importLine + 
                           content.substring(insertPos + 1);
        
        // Сохранение файла
        Files.writeString(file, newContent, StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        
        // Обновление открытого редактора
        Tab tab = openTabsByPath.get(file.normalize().toAbsolutePath());
        if (tab != null) {
            EditorTabData data = (EditorTabData) tab.getUserData();
            if (data != null && data.editor != null) {
                data.editor.replaceText(newContent);
                applyHighlighting(data.editor);
            }
        }
        
        return true;
    } catch (Exception e) {
        return false;
    }
}
```

**Особенности:**

- ✅ Проверяет, не добавлен ли уже импорт
- ✅ Вставляет импорт после `package`, перед `class`/`interface`/`enum`
- ✅ Обновляет открытый редактор

---

## Сценарии использования

### Сценарий 1: Автодополнение

```
User: Types "pub"
    │
    ▼
scheduleAutoComplete()
    │
    └─→ (after 300ms) showCompletion()
        │
        ├─→ currentWordPrefix() → "pub"
        ├─→ JAVA_KEYWORDS → ["public", "public static", ...]
        ├─→ codeIndexer.findCompletions("pub") → []
        └─→ Suggestions: ["public", "public static void main(...)"]
            │
            └─→ User selects "public"
                │
                ▼
insertCompletion()
    │
    └─→ Replaces "pub" with "public"
```

### Сценарий 2: Диагностика

```
User: Types code with error
    │
    ▼
scheduleDiagnostics(file, content)
    │
    └─→ (after 800ms) runDiagnosticsInBackground()
        │
        ├─→ compileWithJavacAndParseProblems()
        │   ├─→ Write content to temp file
        │   ├─→ Run javac
        │   └─→ Parse output
        │       └─→ [Problem(file=Main.java, line=10, kind=error, message="cannot find symbol")]
        │
        └─→ Platform.runLater(() -> updateProblemsPanel())
            │
            ├─→ problemsByFile.put(file, problems)
            ├─→ Update problemsList
            ├─→ Update errorCountLabel
            └─→ applyHighlighting() → Red background on line 10
```

### Сценарий 3: Quick Fix

```
User: Right-clicks on problem "import java.util.List is never used"
    │
    ▼
runQuickFix(problem)
    │
    ├─→ extractUnusedImportFqn() → "java.util.List"
    └─→ removeUnusedImport(file, "java.util.List")
        │
        ├─→ Read file content
        ├─→ Remove "import java.util.List;"
        ├─→ Write file
        └─→ Update editor
            │
            └─→ refreshDiagnosticsForOpenTabIfAny()
                │
                └─→ scheduleDiagnostics() → Problem removed
```

---

## Ограничения и улучшения

### Текущие ограничения

#### 1. Syntax Highlighting

**Ограничения:**

- ❌ Простой regex парсинг (может не работать для сложных случаев)
- ❌ Не учитывает контекст (например, строки в комментариях)
- ❌ Нет подсветки чисел

**Улучшения:**

- Использовать более сложный парсер (state machine)
- Учитывать контекст
- Добавить подсветку чисел

#### 2. Autocompletion

**Ограничения:**

- ❌ Нет контекстного автодополнения (например, методы объекта)
- ❌ Нет параметров методов
- ❌ Нет документации для предложений

**Улучшения:**

- Контекстное автодополнение (типы, методы)
- Параметры методов
- Javadoc для предложений

#### 3. Diagnostics

**Ограничения:**

- ❌ Компилирует только один файл (не весь проект)
- ❌ Нет инкрементальной компиляции
- ❌ Медленно для больших файлов

**Улучшения:**

- Компиляция всего проекта
- Инкрементальная компиляция
- Кэширование результатов

#### 4. Quick Fixes

**Ограничения:**

- ❌ Только 2 типа quick fixes
- ❌ Нет автоматического предложения импортов
- ❌ Нет других исправлений

**Улучшения:**

- Больше типов quick fixes
- Автоматическое предложение импортов
- Исправление других типов ошибок

### Планируемые улучшения

1. **Улучшенная подсветка синтаксиса:**
   - State machine парсер
   - Подсветка чисел
   - Учет контекста

2. **Расширенное автодополнение:**
   - Контекстное автодополнение
   - Параметры методов
   - Javadoc

3. **Улучшенная диагностика:**
   - Компиляция всего проекта
   - Инкрементальная компиляция
   - Кэширование

4. **Расширенные Quick Fixes:**
   - Больше типов исправлений
   - Автоматическое предложение импортов
   - Исправление других ошибок

---

## Резюме

### Ключевые особенности Editor & Code Analysis Layer:

1. ✅ **Syntax Highlighting** - подсветка синтаксиса Java через RichTextFX
2. ✅ **Autocompletion** - автодополнение из ключевых слов, индекса, сниппетов
3. ✅ **Real-time Diagnostics** - диагностика через javac с debounce
4. ✅ **Problems Panel** - централизованный просмотр проблем
5. ✅ **Quick Fixes** - автоматические исправления
6. ✅ **Visual Problem Highlighting** - визуальная подсветка проблемных строк

### Технические детали:

- **RichTextFX** - библиотека для редактирования кода
- **StyleSpans** - структура для применения стилей
- **javac** - компилятор для диагностики
- **Regex парсинг** - для синтаксической подсветки и парсинга вывода javac

### Производительность:

- ✅ Debounce для автодополнения (300ms) и диагностики (800ms)
- ✅ Фоновые потоки для диагностики
- ✅ Ограничение результатов автодополнения (30 элементов)

---

**Версия:** 1.0  
**Дата:** 2026-01-20  
**Файл:** `Documents/03-Editor-Code-Analysis-Layer.md`
