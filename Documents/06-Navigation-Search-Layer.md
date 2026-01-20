# Детальная документация: Navigation & Search Layer

## 📋 Содержание

1. [Обзор слоя](#обзор-слоя)
2. [CodeIndexer - Индексация кода](#codeindexer---индексация-кода)
3. [RecentFilesManager - Недавние файлы](#recentfilesmanager---недавние-файлы)
4. [Go to Class/Symbol/File - Навигация](#go-to-classsymbolfile---навигация)
5. [Find in Files - Поиск по проекту](#find-in-files---поиск-по-проекту)
6. [Find Usages - Поиск использований](#find-usages---поиск-использований)
7. [Go to Definition - Переход к определению](#go-to-definition---переход-к-определению)
8. [Action Search - Поиск действий](#action-search---поиск-действий)
9. [Сценарии использования](#сценарии-использования)
10. [Ограничения и улучшения](#ограничения-и-улучшения)

---

## Обзор слоя

**Navigation & Search Layer** предоставляет **быструю навигацию** по коду и **поиск** в проекте:

- ✅ **Индексация кода** - быстрый поиск классов, методов, интерфейсов
- ✅ **Go to Class/Symbol/File** - переход к элементам кода
- ✅ **Find in Files** - поиск текста по всему проекту
- ✅ **Find Usages** - поиск использований символа
- ✅ **Go to Definition** - переход к определению (Ctrl+Click)
- ✅ **Recent Files** - быстрый доступ к недавно открытым файлам
- ✅ **Action Search** - поиск действий IDE

### Компоненты слоя:

```
Navigation & Search Layer
├── CodeIndexer.java          # Индексация кода
├── RecentFilesManager.java   # Управление недавними файлами
└── IdeController.java        # UI интеграция
```

### Архитектура:

```
┌─────────────────┐
│  IdeController  │
│  (UI Controls)  │
└────────┬────────┘
         │
         ├─→ CodeIndexer ──→ Index ──→ Fast Lookup
         │
         ├─→ RecentFilesManager ──→ Recent Files List
         │
         └─→ Search Methods ──→ File System Walk ──→ Results
```

---

## CodeIndexer - Индексация кода

Файл: `src/main/java/com/example/f_ex/CodeIndexer.java`

### Назначение

**CodeIndexer** создает **in-memory индекс** всех классов, интерфейсов и методов в проекте для быстрого поиска и навигации.

### Структура класса

```java
public class CodeIndexer {
    private final Path projectRoot;
    private final Map<String, List<CodeElement>> index;  // Имя → элементы
}
```

**Структура индекса:**

- `String` (lowercase) - имя элемента (класс, метод, интерфейс)
- `List<CodeElement>` - список элементов с таким именем (может быть несколько)

**Пример:**

```java
index = {
    "main" → [
        CodeElement(name="main", type=METHOD, file=Main.java, line=10),
        CodeElement(name="main", type=METHOD, file=Utils.java, line=5)
    ],
    "calculator" → [
        CodeElement(name="Calculator", type=CLASS, file=Calculator.java, line=1)
    ]
}
```

### Внутренние классы

#### CodeElementType

```java
public enum CodeElementType {
    CLASS,      // Класс
    INTERFACE,  // Интерфейс
    METHOD,     // Метод
    VARIABLE    // Переменная (не используется в текущей реализации)
}
```

#### CodeElement

**Назначение:** Представляет элемент кода.

```java
public static class CodeElement {
    private final String name;           // Имя элемента
    private final CodeElementType type; // Тип
    private final Path file;            // Файл
    private final String packageName;    // Package
    private final int line;             // Номер строки (1-based)
}
```

**Методы:**

- `getName()` - имя элемента
- `getType()` - тип элемента
- `getFile()` - файл, где определен
- `getPackageName()` - package
- `getLine()` - номер строки
- `getDisplayName()` - форматированное имя для UI

**Пример `getDisplayName()`:**

```java
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
```

**Результат:**

- `C Calculator (com.example)` - класс
- `I Runnable (java.lang)` - интерфейс
- `M main` - метод

### Методы

#### `indexProject()`

**Назначение:** Индексирует весь проект.

**Алгоритм:**

```java
public void indexProject() {
    index.clear();  // Очистка старого индекса
    
    if (projectRoot == null || !Files.isDirectory(projectRoot)) {
        return;
    }
    
    try {
        // Обход всех Java файлов
        Files.walk(projectRoot, 20)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !shouldHidePath(p.getParent()))  // Игнорируем скрытые папки
                .forEach(this::indexFile);  // Индексация каждого файла
    } catch (IOException e) {
        // Игнорируем ошибки
    }
}
```

**Особенности:**

- ✅ Рекурсивный обход до глубины 20
- ✅ Только `.java` файлы
- ✅ Игнорирует скрытые папки (`build`, `.gradle`, `.idea`, `.git`, и т.д.)
- ✅ Очищает старый индекс перед индексацией

#### `indexFile(Path file)`

**Назначение:** Индексирует один Java файл.

**Алгоритм:**

```java
private void indexFile(Path file) {
    try {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        String packageName = extractPackage(content);
        
        // 1. Поиск классов
        Matcher classMatcher = CLASS_PATTERN.matcher(content);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            int line = lineAt(content, classMatcher.start(1));
            addToIndex(className, CodeElementType.CLASS, file, packageName, line);
        }
        
        // 2. Поиск интерфейсов
        Matcher interfaceMatcher = INTERFACE_PATTERN.matcher(content);
        while (interfaceMatcher.find()) {
            String interfaceName = interfaceMatcher.group(1);
            int line = lineAt(content, interfaceMatcher.start(1));
            addToIndex(interfaceName, CodeElementType.INTERFACE, file, packageName, line);
        }
        
        // 3. Поиск методов
        Matcher methodMatcher = METHOD_PATTERN.matcher(content);
        while (methodMatcher.find()) {
            String methodName = methodMatcher.group(methodMatcher.groupCount());
            if (methodName != null && !methodName.equals("class") && !methodName.equals("interface")) {
                int line = lineAt(content, methodMatcher.start(methodMatcher.groupCount()));
                addToIndex(methodName, CodeElementType.METHOD, file, packageName, line);
            }
        }
    } catch (IOException e) {
        // Игнорируем ошибки
    }
}
```

**Regex паттерны:**

1. **CLASS_PATTERN:**
   ```regex
   \b(?:public\s+)?(?:final\s+)?(?:abstract\s+)?class\s+(\w+)
   ```
   - Находит: `public class Main`, `final class Utils`, `abstract class Base`

2. **INTERFACE_PATTERN:**
   ```regex
   \b(?:public\s+)?interface\s+(\w+)
   ```
   - Находит: `public interface Runnable`, `interface Listener`

3. **METHOD_PATTERN:**
   ```regex
   \b(?:public|private|protected)\s+(?:static\s+)?(?:final\s+)?(?:\w+\s+)*(\w+)\s*\([^)]*\)
   ```
   - Находит: `public void main(String[] args)`, `private static int calculate()`

**Особенности:**

- ✅ Извлекает package из файла
- ✅ Вычисляет номер строки для каждого элемента
- ✅ Игнорирует ложные срабатывания (например, `class` в имени метода)

#### `extractPackage(String content)`

**Назначение:** Извлекает package из содержимого файла.

```java
private String extractPackage(String content) {
    Pattern pkgPattern = Pattern.compile("package\\s+([\\w.]+);");
    Matcher m = pkgPattern.matcher(content);
    if (m.find()) {
        return m.group(1);
    }
    return "";
}
```

**Пример:**

```
Вход: "package com.example;"
Выход: "com.example"
```

#### `addToIndex(String name, CodeElementType type, Path file, String packageName, int line)`

**Назначение:** Добавляет элемент в индекс.

```java
private void addToIndex(String name, CodeElementType type, Path file, String packageName, int line) {
    index.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>())
            .add(new CodeElement(name, type, file, packageName, line));
}
```

**Особенности:**

- ✅ Использует lowercase ключ для case-insensitive поиска
- ✅ Поддерживает несколько элементов с одним именем (перегрузка методов)

#### `lineAt(String s, int offset)`

**Назначение:** Вычисляет номер строки по смещению в тексте.

```java
private static int lineAt(String s, int offset) {
    if (s == null || s.isEmpty()) return 1;
    if (offset < 0) return 1;
    int line = 1;
    int max = Math.min(offset, s.length());
    for (int i = 0; i < max; i++) {
        if (s.charAt(i) == '\n') line++;
    }
    return line;
}
```

**Алгоритм:**

- Начинает с строки 1
- Подсчитывает количество `\n` до указанного смещения

**Пример:**

```
Текст: "line1\nline2\nline3"
offset = 10 (после "line2\n")

Результат: 2
```

#### `findCompletions(String prefix)`

**Назначение:** Находит все элементы, начинающиеся с префикса.

**Алгоритм:**

```java
public List<CodeElement> findCompletions(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
        return new ArrayList<>();
    }
    
    String lowerPrefix = prefix.toLowerCase();
    List<CodeElement> results = new ArrayList<>();
    
    // Поиск элементов, начинающихся с префикса
    for (Map.Entry<String, List<CodeElement>> entry : index.entrySet()) {
        if (entry.getKey().startsWith(lowerPrefix)) {
            results.addAll(entry.getValue());
        }
    }
    
    // Сортировка по типу и имени
    results.sort((a, b) -> {
        int typeCompare = a.getType().compareTo(b.getType());
        if (typeCompare != 0) return typeCompare;
        return a.getName().compareToIgnoreCase(b.getName());
    });
    
    return results;
}
```

**Особенности:**

- ✅ Case-insensitive поиск
- ✅ Сортировка: сначала по типу (CLASS, INTERFACE, METHOD), затем по имени
- ✅ Возвращает все совпадения (не только первое)

**Пример:**

```
prefix = "calc"

Результат:
[
    CodeElement(name="Calculator", type=CLASS, ...),
    CodeElement(name="calculate", type=METHOD, ...),
    CodeElement(name="calculateSum", type=METHOD, ...)
]
```

#### `shouldHidePath(Path path)`

**Назначение:** Проверяет, нужно ли скрыть путь от индексации.

```java
public static boolean shouldHidePath(Path path) {
    if (path == null) return true;
    String name = path.getFileName() != null 
        ? path.getFileName().toString() 
        : path.toString();
    return HIDDEN_DIRS.contains(name) || name.startsWith(".");
}
```

**Скрытые директории:**

```java
private static final Set<String> HIDDEN_DIRS = Set.of(
    "build", ".gradle", ".idea", ".git", "out", "bin", 
    ".vscode", "node_modules", ".classpath", ".project"
);
```

**Особенности:**

- ✅ Игнорирует системные папки
- ✅ Игнорирует папки, начинающиеся с `.`

---

## RecentFilesManager - Недавние файлы

Файл: `src/main/java/com/example/f_ex/RecentFilesManager.java`

### Назначение

**RecentFilesManager** управляет списком недавно открытых файлов для быстрого доступа.

### Структура класса

```java
final class RecentFilesManager {
    private final int limit;                    // Максимальное количество файлов
    private final LinkedHashSet<Path> recent;   // Список недавних файлов
}
```

**Особенности:**

- ✅ Использует `LinkedHashSet` для сохранения порядка и уникальности
- ✅ Ограничение количества файлов (по умолчанию минимум 5)

### Методы

#### `markOpened(Path file)`

**Назначение:** Отмечает файл как открытый.

**Алгоритм:**

```java
void markOpened(Path file) {
    if (file == null) return;
    
    // Удаляем файл, если он уже есть (для перемещения в конец)
    recent.remove(file);
    
    // Добавляем в конец
    recent.add(file);
    
    // Ограничиваем размер списка
    while (recent.size() > limit) {
        Path first = recent.iterator().next();
        recent.remove(first);
    }
}
```

**Особенности:**

- ✅ Если файл уже в списке - перемещается в конец (самый недавний)
- ✅ Удаляет старые файлы, если превышен лимит
- ✅ FIFO порядок (первый добавленный удаляется первым)

**Пример:**

```
limit = 5
recent = [A, B, C, D, E]

markOpened(B)
→ recent = [A, C, D, E, B]  // B перемещен в конец

markOpened(F)
→ recent = [C, D, E, B, F]  // A удален, F добавлен
```

#### `list()`

**Назначение:** Возвращает список недавних файлов (от новых к старым).

```java
List<Path> list() {
    List<Path> out = new ArrayList<>(recent);
    Collections.reverse(out);  // Реверс: новые → старые
    return out;
}
```

**Особенности:**

- ✅ Возвращает список от новых к старым
- ✅ Создает новый список (не изменяет внутренний)

**Пример:**

```
recent = [A, B, C, D, E]  // A - самый старый, E - самый новый

list()
→ [E, D, C, B, A]  // E - самый новый
```

---

## Go to Class/Symbol/File - Навигация

### Go to Class

**Метод:** `onGoToClass()` (`Ctrl+N`)

**Алгоритм:**

```java
@FXML
public void onGoToClass() {
    goToFromIndex(
        Set.of(CodeElementType.CLASS, CodeElementType.INTERFACE), 
        "Go to Class"
    );
}
```

**Особенности:**

- ✅ Показывает только классы и интерфейсы
- ✅ Использует общий метод `goToFromIndex()`

### Go to Symbol

**Метод:** `onGoToSymbol()` (`Ctrl+Alt+Shift+N`)

**Алгоритм:**

```java
@FXML
public void onGoToSymbol() {
    goToFromIndex(
        Set.of(
            CodeElementType.CLASS, 
            CodeElementType.INTERFACE, 
            CodeElementType.METHOD
        ), 
        "Go to Symbol"
    );
}
```

**Особенности:**

- ✅ Показывает классы, интерфейсы и методы
- ✅ Более широкий поиск, чем Go to Class

### Go to File

**Метод:** `onGoToFile()` (`Ctrl+Shift+N`)

**Алгоритм:**

```java
@FXML
public void onGoToFile() {
    if (projectRoot == null) {
        updateStatus("No project root set");
        return;
    }
    
    TextInputDialog d = new TextInputDialog();
    d.setTitle("Go to File");
    d.setHeaderText("Type part of file name");
    d.setContentText("File:");
    
    Optional<String> r = d.showAndWait();
    r.ifPresent(q -> {
        String s = q == null ? "" : q.trim().toLowerCase();
        if (s.isEmpty()) return;
        
        // Поиск файла по имени
        Path found = findFileByName(projectRoot, s);
        if (found != null) {
            openFileInEditor(found);
        } else {
            updateStatus("File not found");
        }
    });
}
```

#### `findFileByName(Path root, String needleLower)`

**Назначение:** Находит файл по части имени.

**Алгоритм:**

```java
private static Path findFileByName(Path root, String needleLower) {
    try {
        return Files.walk(root, 20)
                .filter(Files::isRegularFile)
                .filter(p -> !CodeIndexer.shouldHidePath(p.getParent()))
                .filter(p -> {
                    String fileName = p.getFileName() != null 
                        ? p.getFileName().toString().toLowerCase() 
                        : "";
                    return fileName.contains(needleLower);
                })
                .findFirst()
                .orElse(null);
    } catch (Exception e) {
        return null;
    }
}
```

**Особенности:**

- ✅ Case-insensitive поиск
- ✅ Поиск по части имени (contains)
- ✅ Возвращает первый найденный файл
- ✅ Игнорирует скрытые папки

**Пример:**

```
needleLower = "main"

Найдет:
- Main.java
- MainActivity.java
- MainTest.java
```

### `goToFromIndex(Set<CodeElementType> types, String title)`

**Назначение:** Общий метод для навигации по индексу.

**Алгоритм:**

```java
private void goToFromIndex(Set<CodeElementType> types, String title) {
    if (codeIndexer == null) {
        updateStatus("Index not ready");
        return;
    }
    
    Dialog<CodeElement> dialog = new Dialog<>();
    dialog.setTitle(title);
    dialog.setHeaderText(null);
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    
    // Поле фильтрации
    TextField filter = new TextField();
    filter.setPromptText("Type to search...");
    
    // Список результатов
    ListView<CodeElement> list = new ListView<>();
    
    // Обработчик ввода
    filter.textProperty().addListener((o, a, b) -> {
        String q = b == null ? "" : b.trim();
        if (q.isEmpty()) {
            list.getItems().clear();
            return;
        }
        
        // Поиск в индексе
        List<CodeElement> found = codeIndexer.findCompletions(q).stream()
                .filter(e -> types.contains(e.getType()))  // Фильтр по типу
                .limit(200)
                .toList();
        list.getItems().setAll(found);
    });
    
    // Двойной клик для выбора
    list.setOnMouseClicked(e -> {
        if (e.getClickCount() == 2) {
            CodeElement selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                dialog.setResult(selected);
            }
        }
    });
    
    // Layout
    VBox vbox = new VBox(10, new Label("Search:"), filter, list);
    vbox.setPrefSize(500, 400);
    dialog.getDialogPane().setContent(vbox);
    
    // Обработка результата
    Optional<CodeElement> picked = dialog.showAndWait();
    picked.ifPresent(e -> openFileAndGoTo(e.getFile(), e.getLine()));
}
```

**Особенности:**

- ✅ Real-time фильтрация при вводе
- ✅ Фильтр по типу элементов
- ✅ Двойной клик для выбора
- ✅ Ограничение результатов (200 элементов)
- ✅ Переход к файлу и строке при выборе

#### `openFileAndGoTo(Path file, int line)`

**Назначение:** Открывает файл и переходит к указанной строке.

```java
private void openFileAndGoTo(Path file, int line) {
    if (file == null) return;
    
    // Открытие файла
    openFileInEditor(file);
    
    // Переход к строке
    Platform.runLater(() -> {
        Tab tab = openTabsByPath.get(file.normalize().toAbsolutePath());
        if (tab == null) return;
        
        EditorTabData data = (EditorTabData) tab.getUserData();
        if (data == null || data.editor == null) return;
        
        int ln = Math.max(1, line);
        int para = ln - 1;  // 1-based → 0-based
        
        if (para >= data.editor.getParagraphs().size()) {
            para = data.editor.getParagraphs().size() - 1;
        }
        
        // Перемещение курсора
        data.editor.moveTo(para, 0);
        data.editor.requestFocus();
    });
}
```

**Особенности:**

- ✅ Открывает файл, если не открыт
- ✅ Переходит к указанной строке
- ✅ Устанавливает фокус на редактор
- ✅ Проверяет границы (не выходит за пределы файла)

---

## Find in Files - Поиск по проекту

**Метод:** `onFindInFiles()` (`Ctrl+Shift+F`)

### Назначение

**Find in Files** выполняет поиск текста по всем файлам проекта.

### Алгоритм

```java
@FXML
public void onFindInFiles() {
    Dialog<Map<String, String>> dialog = new Dialog<>();
    dialog.setTitle("Find in Files");
    dialog.setHeaderText("Search for text in project files");
    
    ButtonType searchButton = new ButtonType("Search", ButtonBar.ButtonData.OK_DONE);
    dialog.getDialogPane().getButtonTypes().addAll(searchButton, ButtonType.CANCEL);
    
    // UI элементы
    GridPane grid = new GridPane();
    TextField searchField = new TextField();
    searchField.setPromptText("Enter text to search...");
    CheckBox caseSensitive = new CheckBox("Case sensitive");
    
    grid.add(new Label("Search:"), 0, 0);
    grid.add(searchField, 1, 0);
    grid.add(caseSensitive, 1, 1);
    
    dialog.getDialogPane().setContent(grid);
    
    // Обработка результата
    dialog.setResultConverter(buttonType -> {
        if (buttonType == searchButton) {
            Map<String, String> result = new HashMap<>();
            result.put("text", searchField.getText());
            result.put("caseSensitive", String.valueOf(caseSensitive.isSelected()));
            return result;
        }
        return null;
    });
    
    Optional<Map<String, String>> result = dialog.showAndWait();
    result.ifPresent(params -> {
        String searchText = params.get("text");
        boolean caseSensitive = Boolean.parseBoolean(params.get("caseSensitive"));
        
        if (searchText == null || searchText.trim().isEmpty()) {
            updateStatus("Search text is empty");
            return;
        }
        
        // Выполнение поиска
        performSearchInFiles(searchText, caseSensitive);
    });
}
```

### `performSearchInFiles(String searchText, boolean caseSensitive)`

**Назначение:** Выполняет поиск по файлам.

**Алгоритм:**

```java
private void performSearchInFiles(String searchText, boolean caseSensitive) {
    if (projectRoot == null) {
        updateStatus("No project root set");
        return;
    }
    
    if (searchResultsList == null) return;
    
    // Показываем панель результатов
    if (bottomPanel != null) {
        bottomPanel.setVisible(true);
        bottomPanel.setManaged(true);
    }
    if (bottomTabs != null) {
        bottomTabs.getSelectionModel().select(2);  // Search tab
    }
    
    // Очистка предыдущих результатов
    searchResultsList.getItems().clear();
    updateStatus("Searching: " + searchText);
    
    // Проверка кэша
    String cacheKey = searchText.toLowerCase() + "|" + caseSensitive;
    if (searchCache.containsKey(cacheKey)) {
        List<Path> cached = searchCache.get(cacheKey);
        Platform.runLater(() -> {
            for (Path p : cached) {
                searchResultsList.getItems().add(new SearchHit(p, 0, ""));
            }
            updateStatus("Found " + cached.size() + " files (cached)");
        });
        return;
    }
    
    // Поиск в фоновом потоке
    Thread t = new Thread(() -> {
        List<SearchHit> hits = new ArrayList<>();
        List<Path> foundFiles = new ArrayList<>();
        
        try {
            Files.walk(projectRoot, 20)
                    .filter(Files::isRegularFile)
                    .filter(p -> !CodeIndexer.shouldHidePath(p.getParent()))
                    .forEach(file -> {
                        try {
                            String content = Files.readString(file, StandardCharsets.UTF_8);
                            String search = caseSensitive ? searchText : searchText.toLowerCase();
                            String fileContent = caseSensitive ? content : content.toLowerCase();
                            
                            // Поиск вхождения
                            if (fileContent.contains(search)) {
                                foundFiles.add(file);
                                
                                // Поиск строк с совпадениями
                                String[] lines = content.split("\\R");
                                for (int i = 0; i < lines.length; i++) {
                                    String line = lines[i];
                                    String lineLower = caseSensitive ? line : line.toLowerCase();
                                    if (lineLower.contains(search)) {
                                        hits.add(new SearchHit(file, i + 1, line.trim()));
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            Platform.runLater(() -> updateStatus("Search failed: " + e.getMessage()));
            return;
        }
        
        // Обновление UI
        Platform.runLater(() -> {
            searchResultsList.getItems().setAll(hits);
            searchCache.put(cacheKey, foundFiles);  // Кэширование
            updateStatus("Found " + hits.size() + " matches in " + foundFiles.size() + " files");
        });
    }, "file-search");
    t.setDaemon(true);
    t.start();
}
```

**Особенности:**

- ✅ Поиск в фоновом потоке (не блокирует UI)
- ✅ Case-sensitive/insensitive поиск
- ✅ Кэширование результатов
- ✅ Показывает строки с совпадениями
- ✅ Игнорирует скрытые папки

### SearchHit

**Назначение:** Представляет результат поиска.

```java
private static final class SearchHit {
    private final Path file;      // Файл
    private final int line;       // Номер строки
    private final String preview; // Предпросмотр строки
    
    @Override
    public String toString() {
        return file.getFileName() + ":" + line + " - " + preview;
    }
}
```

**Формат отображения:**

```
Main.java:10 - public static void main(String[] args) {
Utils.java:5 - private void calculate() {
```

### Обработчик клика на результат

```java
if (searchResultsList != null) {
    searchResultsList.setOnMouseClicked(e -> {
        if (e.getClickCount() != 1) return;
        SearchHit hit = searchResultsList.getSelectionModel().getSelectedItem();
        if (hit == null) return;
        
        // Открытие файла и переход к строке
        openFileAndGoTo(hit.file, hit.line);
    });
}
```

---

## Find Usages - Поиск использований

**Метод:** `onFindUsages()` (`Alt+F7`)

### Назначение

**Find Usages** находит все использования символа в проекте.

### Алгоритм

```java
@FXML
public void onFindUsages() {
    Tab tab = editorTabs.getSelectionModel().getSelectedItem();
    EditorTabData data = (EditorTabData) tab.getUserData();
    
    // Извлечение слова под курсором
    String initial = "";
    if (data != null && data.editor != null) {
        initial = wordAt(data.editor.getText(), data.editor.getCaretPosition());
        if (initial == null) initial = "";
    }
    
    // Диалог для ввода символа
    TextInputDialog d = new TextInputDialog(initial);
    d.setTitle("Find Usages");
    d.setHeaderText("Find usages in project");
    d.setContentText("Symbol:");
    
    Optional<String> res = d.showAndWait();
    res.ifPresent(sym -> {
        String s = sym == null ? "" : sym.trim();
        if (s.isEmpty()) return;
        findUsagesInProject(s);
    });
}
```

### `findUsagesInProject(String symbol)`

**Назначение:** Находит все использования символа.

**Алгоритм:**

```java
private void findUsagesInProject(String symbol) {
    if (projectRoot == null) {
        updateStatus("No project root set");
        return;
    }
    if (searchResultsList == null) return;
    
    // Показываем панель результатов
    if (bottomPanel != null) {
        bottomPanel.setVisible(true);
        bottomPanel.setManaged(true);
    }
    if (bottomTabs != null) {
        bottomTabs.getSelectionModel().select(2);  // Search tab
    }
    
    // Regex паттерн для поиска целых слов
    Pattern pat = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
    searchResultsList.getItems().clear();
    updateStatus("Searching usages: " + symbol);
    
    // Поиск в фоновом потоке
    Thread t = new Thread(() -> {
        List<SearchHit> hits = new ArrayList<>();
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
                                // Поиск целых слов (word boundary)
                                if (!pat.matcher(ln).find()) continue;
                                
                                String prev = ln.trim();
                                if (prev.length() > 80) prev = prev.substring(0, 77) + "...";
                                hits.add(new SearchHit(file, i + 1, prev));
                            }
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            Platform.runLater(() -> updateStatus("Search failed: " + e.getMessage()));
            return;
        }
        
        // Обновление UI
        Platform.runLater(() -> {
            searchResultsList.getItems().setAll(hits);
            updateStatus("Found " + hits.size() + " usages of " + symbol);
        });
    }, "find-usages");
    t.setDaemon(true);
    t.start();
}
```

**Особенности:**

- ✅ Поиск **целых слов** (word boundary `\b`)
- ✅ Только Java файлы
- ✅ Показывает строки с совпадениями
- ✅ Поиск в фоновом потоке

**Regex паттерн:**

```java
Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b")
```

- `\b` - word boundary (начало/конец слова)
- `Pattern.quote()` - экранирование специальных символов

**Пример:**

```
symbol = "count"

Найдет:
- int count = 10;
- count++;
- return count;

НЕ найдет:
- counter (часть слова)
- myCount (часть слова)
```

---

## Go to Definition - Переход к определению

**Горячая клавиша:** `Ctrl+Click` на символе

### Назначение

**Go to Definition** переходит к определению символа под курсором.

### Алгоритм

```java
// Обработчик Ctrl+Click в редакторе
editor.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
    if (!e.isControlDown()) return;
    
    int pos = editor.hit(e.getX(), e.getY()).getInsertionIndex();
    String word = wordAt(editor.getText(), pos);
    if (word == null || word.isBlank()) return;
    
    goToDefinition(word);
    e.consume();
});
```

### `goToDefinition(String symbol)`

**Назначение:** Переходит к определению символа.

**Алгоритм:**

```java
private void goToDefinition(String symbol) {
    if (codeIndexer == null || symbol == null || symbol.isBlank()) return;
    
    // Поиск в индексе
    List<CodeElement> hits = codeIndexer.findCompletions(symbol).stream()
            .filter(e -> e.getName().equals(symbol))  // Точное совпадение
            .toList();
    
    if (hits.isEmpty()) {
        updateStatus("Definition not found: " + symbol);
        return;
    }
    
    // Выбор первого результата (можно улучшить - выбрать лучший)
    CodeElement best = hits.get(0);
    openFileAndGoTo(best.getFile(), best.getLine());
}
```

**Особенности:**

- ✅ Использует `CodeIndexer` для поиска
- ✅ Точное совпадение имени (case-sensitive)
- ✅ Выбирает первый результат (можно улучшить - выбрать лучший по контексту)

**Пример:**

```
User: Ctrl+Click on "Calculator"

codeIndexer.findCompletions("Calculator")
→ [CodeElement(name="Calculator", type=CLASS, file=Calculator.java, line=1)]

openFileAndGoTo(Calculator.java, 1)
→ Opens Calculator.java and moves to line 1
```

---

## Action Search - Поиск действий

**Метод:** `onActionSearch()` (`Ctrl+Shift+A`)

### Назначение

**Action Search** позволяет быстро найти и выполнить действие IDE.

### Алгоритм

```java
@FXML
public void onActionSearch() {
    List<ActionItem> actions = buildActions();
    
    Dialog<ActionItem> dialog = new Dialog<>();
    dialog.setTitle("Action Search");
    dialog.setHeaderText(null);
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    
    // Поле фильтрации
    TextField filter = new TextField();
    filter.setPromptText("Type action name...");
    
    // Список действий
    ListView<ActionItem> list = new ListView<>();
    
    // Обработчик ввода
    filter.textProperty().addListener((o, a, b) -> {
        String q = b == null ? "" : b.trim().toLowerCase();
        if (q.isEmpty()) {
            list.getItems().setAll(actions.stream().limit(40).toList());
            return;
        }
        
        // Фильтрация действий
        List<ActionItem> found = actions.stream()
                .filter(x -> x.name.toLowerCase().contains(q))
                .limit(200)
                .toList();
        list.getItems().setAll(found);
    });
    
    // Двойной клик для выбора
    list.setOnMouseClicked(e -> {
        if (e.getClickCount() == 2) {
            ActionItem selected = list.getSelectionModel().getSelectedItem();
            if (selected != null) {
                dialog.setResult(selected);
            }
        }
    });
    
    // Layout
    VBox vbox = new VBox(10, new Label("Search:"), filter, list);
    vbox.setPrefSize(500, 400);
    dialog.getDialogPane().setContent(vbox);
    
    // Обработка результата
    Optional<ActionItem> picked = dialog.showAndWait();
    picked.ifPresent(item -> item.run.run());
}
```

### ActionItem

**Назначение:** Представляет действие IDE.

```java
private static final class ActionItem {
    private final String name;    // Имя действия
    private final Runnable run;   // Выполняемый код
    
    @Override
    public String toString() {
        return name;
    }
}
```

### `buildActions()`

**Назначение:** Создает список всех доступных действий.

```java
private List<ActionItem> buildActions() {
    List<ActionItem> a = new ArrayList<>();
    a.add(new ActionItem("Go to Class...", this::onGoToClass));
    a.add(new ActionItem("Go to Symbol...", this::onGoToSymbol));
    a.add(new ActionItem("Go to File...", this::onGoToFile));
    a.add(new ActionItem("Recent Files...", this::onRecentFiles));
    a.add(new ActionItem("Find Usages...", this::onFindUsages));
    a.add(new ActionItem("Find in Files...", this::onFindInFiles));
    a.add(new ActionItem("Debug Project", this::onDebugProject));
    a.add(new ActionItem("Rename...", this::onRename));
    // ... и т.д.
    return a;
}
```

**Особенности:**

- ✅ Real-time фильтрация при вводе
- ✅ Case-insensitive поиск
- ✅ Двойной клик для выполнения
- ✅ Ограничение результатов (200 действий)

---

## Сценарии использования

### Сценарий 1: Go to Class

```
User: Presses Ctrl+N
    │
    ▼
onGoToClass()
    │
    └─→ goToFromIndex(CLASS, INTERFACE, "Go to Class")
        │
        ├─→ Dialog with TextField and ListView
        │
        └─→ User types "calc"
            │
            ▼
codeIndexer.findCompletions("calc")
    │
    └─→ [CodeElement("Calculator", CLASS, ...), CodeElement("calculate", METHOD, ...)]
        │
        └─→ Filter by type (CLASS, INTERFACE)
            │
            └─→ [CodeElement("Calculator", CLASS, ...)]
                │
                └─→ User double-clicks
                    │
                    ▼
openFileAndGoTo(Calculator.java, 1)
    │
    ├─→ openFileInEditor(Calculator.java)
    └─→ Move cursor to line 1
```

### Сценарий 2: Find Usages

```
User: Places caret on "count"
User: Presses Alt+F7
    │
    ▼
onFindUsages()
    │
    ├─→ wordAt() → "count"
    ├─→ Dialog: "Find usages: count"
    └─→ User confirms
        │
        ▼
findUsagesInProject("count")
    │
    ├─→ Pattern.compile("\\bcount\\b")
    └─→ Thread: Files.walk(projectRoot)
        │
        ├─→ For each .java file:
        │   ├─→ Read lines
        │   └─→ Pattern.matcher(line).find()
        │       └─→ If match: SearchHit(file, line, preview)
        │
        └─→ Platform.runLater()
            │
            └─→ searchResultsList.setItems(hits)
                │
                └─→ User clicks on result
                    │
                    ▼
openFileAndGoTo(file, line)
    │
    └─→ Opens file and moves to line
```

### Сценарий 3: Go to Definition (Ctrl+Click)

```
User: Ctrl+Click on "Calculator" in code
    │
    ▼
MouseEvent handler
    │
    ├─→ wordAt() → "Calculator"
    └─→ goToDefinition("Calculator")
        │
        ├─→ codeIndexer.findCompletions("Calculator")
        │   └─→ [CodeElement("Calculator", CLASS, Calculator.java, 1)]
        │
        └─→ Filter: e.getName().equals("Calculator")
            │
            └─→ [CodeElement("Calculator", CLASS, Calculator.java, 1)]
                │
                └─→ openFileAndGoTo(Calculator.java, 1)
                    │
                    └─→ Opens Calculator.java and moves to line 1
```

---

## Ограничения и улучшения

### Текущие ограничения

#### 1. CodeIndexer

**Ограничения:**

- ❌ Простой regex парсинг (может не работать для сложных случаев)
- ❌ Не учитывает вложенные классы
- ❌ Не учитывает generics
- ❌ Не учитывает аннотации

**Улучшения:**

- Использовать AST парсер (JavaParser, Eclipse JDT)
- Учитывать вложенные классы
- Учитывать generics (`List<String>`)
- Учитывать аннотации

#### 2. Find Usages

**Ограничения:**

- ❌ Простой regex поиск (может найти не то)
- ❌ Не учитывает scope переменных
- ❌ Не различает поля и локальные переменные

**Улучшения:**

- AST-based поиск
- Учет scope
- Различение полей и переменных

#### 3. Go to Definition

**Ограничения:**

- ❌ Выбирает первый результат (может быть не тот)
- ❌ Не учитывает контекст (импорты, package)
- ❌ Не работает для полей объектов (`obj.field`)

**Улучшения:**

- Выбор лучшего результата по контексту
- Учет импортов и package
- Поддержка полей объектов

#### 4. Find in Files

**Ограничения:**

- ❌ Простой contains поиск (нет regex)
- ❌ Нет фильтрации по типу файлов
- ❌ Нет замены (только поиск)

**Улучшения:**

- Regex поиск
- Фильтрация по типу файлов (`.java`, `.xml`, и т.д.)
- Replace in Files

### Планируемые улучшения

1. **AST-based индексация:**
   - Использование JavaParser или Eclipse JDT
   - Точное понимание структуры кода
   - Учет scope и контекста

2. **Улучшенный Find Usages:**
   - AST-based поиск
   - Учет scope
   - Различение полей и переменных

3. **Улучшенный Go to Definition:**
   - Выбор лучшего результата
   - Учет контекста
   - Поддержка полей объектов

4. **Расширенный Find in Files:**
   - Regex поиск
   - Фильтрация по типу файлов
   - Replace in Files

---

## Резюме

### Ключевые особенности Navigation & Search Layer:

1. ✅ **CodeIndexer** - быстрая индексация кода для навигации
2. ✅ **Go to Class/Symbol/File** - быстрый переход к элементам
3. ✅ **Find in Files** - поиск текста по проекту
4. ✅ **Find Usages** - поиск использований символа
5. ✅ **Go to Definition** - переход к определению (Ctrl+Click)
6. ✅ **Recent Files** - быстрый доступ к недавним файлам
7. ✅ **Action Search** - поиск действий IDE

### Технические детали:

- **Индексация:** Regex парсинг для классов, интерфейсов, методов
- **Поиск:** Word boundary для точного поиска использований
- **Кэширование:** Результаты поиска кэшируются
- **Фоновые потоки:** Поиск не блокирует UI

### Производительность:

- ✅ In-memory индекс для быстрого поиска
- ✅ Фоновые потоки для больших поисков
- ✅ Кэширование результатов
- ✅ Ограничение результатов (200 элементов)

---

**Версия:** 1.0  
**Дата:** 2026-01-20  
**Файл:** `Documents/06-Navigation-Search-Layer.md`
