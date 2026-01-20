# Детальная документация: UI/Controller Layer

## 📋 Содержание

1. [Обзор слоя](#обзор-слоя)
2. [HelloApplication - Точка входа](#helloapplication---точка-входа)
3. [Launcher - Запуск приложения](#launcher---запуск-приложения)
4. [IdeController - Главный контроллер](#idecontroller---главный-контроллер)
5. [FXML Views - Структура интерфейса](#fxml-views---структура-интерфейса)
6. [CSS Styling - Стилизация](#css-styling---стилизация)
7. [Взаимодействие компонентов](#взаимодействие-компонентов)
8. [Жизненный цикл UI](#жизненный-цикл-ui)

---

## Обзор слоя

**Presentation Layer (UI/Controller)** - это слой, отвечающий за пользовательский интерфейс и взаимодействие с пользователем. Он построен на основе паттерна **MVC (Model-View-Controller)** с использованием **JavaFX**.

### Компоненты слоя:

```
UI/Controller Layer
├── HelloApplication.java      # JavaFX Application точка входа
├── Launcher.java              # Launcher для jpackage
├── IdeController.java         # Главный контроллер (MVC Controller)
├── ide-view.fxml              # FXML разметка интерфейса
└── ide.css                    # CSS стили и темы
```

### Ответственность:

- ✅ Управление пользовательским интерфейсом
- ✅ Обработка событий пользователя (клики, клавиатура)
- ✅ Координация работы всех сервисов
- ✅ Обновление UI в ответ на изменения
- ✅ Визуализация данных (код, дерево проекта, консоль)

---

## HelloApplication - Точка входа

### Назначение

`HelloApplication` - это главный класс JavaFX приложения, который инициализирует UI и загружает FXML разметку.

### Структура класса

```java
public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) {
        // Инициализация UI
    }
}
```

### Методы

#### `start(Stage stage)`

**Назначение:** Точка входа JavaFX приложения, вызывается автоматически при запуске.

**Алгоритм работы:**

1. **Загрузка FXML:**
   ```java
   FXMLLoader fxmlLoader = new FXMLLoader(
       HelloApplication.class.getResource("ide-view.fxml")
   );
   ```
   - Загружает FXML файл из ресурсов
   - Проверяет наличие файла (если null - выводит ошибку)

2. **Создание сцены:**
   ```java
   Scene scene = new Scene(fxmlLoader.load(), 1100, 750);
   ```
   - Размер окна: 1100x750 пикселей
   - Загружает все UI компоненты из FXML

3. **Применение CSS:**
   ```java
   var cssUrl = HelloApplication.class.getResource("ide.css");
   if (cssUrl != null) {
       scene.getStylesheets().add(cssUrl.toExternalForm());
   }
   ```
   - Загружает CSS файл со стилями
   - Применяет стили ко всей сцене

4. **Настройка Stage:**
   ```java
   stage.setTitle("F_EX Java IDE (MVP)");
   stage.setScene(scene);
   stage.show();
   ```
   - Устанавливает заголовок окна
   - Привязывает сцену к окну
   - Показывает окно

5. **Обработка ошибок:**
   - При любой ошибке выводит stack trace в консоль
   - Показывает Alert с описанием ошибки
   - Предотвращает падение приложения

### Обработка ошибок

```java
try {
    // Загрузка UI
} catch (Exception e) {
    System.err.println("FATAL ERROR starting application:");
    e.printStackTrace();
    
    // Показываем Alert пользователю
    Alert alert = new Alert(AlertType.ERROR);
    alert.setTitle("Application Error");
    alert.setHeaderText("Failed to start application");
    alert.setContentText(e.getMessage() + "\n\nCheck console for details.");
    alert.showAndWait();
}
```

### Зависимости

- `javafx.application.Application` - базовый класс JavaFX
- `javafx.fxml.FXMLLoader` - загрузчик FXML
- `javafx.scene.Scene` - сцена JavaFX
- `javafx.stage.Stage` - окно приложения

### Особенности

- ✅ Проверка наличия ресурсов перед загрузкой
- ✅ Graceful error handling
- ✅ Логирование ошибок в консоль
- ✅ Пользовательские сообщения об ошибках

---

## Launcher - Запуск приложения

### Назначение

`Launcher` - простой класс-обертка для запуска JavaFX Application. Используется для совместимости с `jpackage`, который требует явный `main` метод.

### Структура класса

```java
public class Launcher {
    public static void main(String[] args) {
        Application.launch(HelloApplication.class, args);
    }
}
```

### Методы

#### `main(String[] args)`

**Назначение:** Точка входа приложения для JVM.

**Параметры:**
- `args` - аргументы командной строки (передаются в JavaFX Application)

**Алгоритм:**
1. Вызывает `Application.launch()` с классом `HelloApplication`
2. Передает аргументы командной строки
3. JavaFX автоматически создает экземпляр `HelloApplication` и вызывает `start()`

### Почему нужен отдельный Launcher?

1. **jpackage требует main метод:**
   - `jpackage` ищет класс с методом `main(String[])`
   - `HelloApplication` наследуется от `Application`, но не имеет явного `main`

2. **Совместимость:**
   - Можно запускать как через `main`, так и через `Application.launch()`
   - Работает в IDE и в упакованном EXE

### Использование

```bash
# Запуск через Launcher
java -cp ... com.example.f_ex.Launcher

# Или напрямую через Application (тоже работает)
java -cp ... com.example.f_ex.HelloApplication
```

---

## IdeController - Главный контроллер

### Назначение

`IdeController` - это **главный контроллер** приложения, реализующий паттерн **MVC Controller**. Он координирует работу всех компонентов IDE и обрабатывает все пользовательские действия.

### Структура класса

```java
public class IdeController {
    // FXML injected components
    @FXML private TreeView<Path> projectTree;
    @FXML private TabPane editorTabs;
    @FXML private TextArea consoleArea;
    // ... другие UI компоненты
    
    // Business logic dependencies
    private CodeIndexer codeIndexer;
    private SettingsManager settingsManager;
    private RefactorRenameService renameService;
    // ... другие сервисы
    
    // State
    private Path projectRoot;
    private Map<Path, Tab> openTabsByPath;
    // ... другое состояние
}
```

### Жизненный цикл

#### 1. Инициализация (`initialize()`)

**Вызывается:** Автоматически после загрузки FXML

**Что делает:**

```java
public void initialize() {
    // 1. Инициализация менеджера настроек
    settingsManager = new SettingsManager(ideRoot);
    
    // 2. Применение темы (отложенное, т.к. Scene еще не готова)
    Platform.runLater(() -> {
        applyTheme(settingsManager.get(SettingsManager.KEY_THEME, ...));
    });
    
    // 3. Настройка дерева проекта
    projectTree.setShowRoot(false);
    projectTree.setCellFactory(...);
    
    // 4. Обработчики событий дерева
    projectTree.setOnMouseClicked(...);
    projectTree.setOnKeyPressed(...);
    
    // 5. Глобальные горячие клавиши
    stage.getScene().getAccelerators().put(...);
    
    // 6. Настройка списков результатов
    problemsList.setCellFactory(...);
    searchResultsList.setCellFactory(...);
    
    // 7. Инициализация отладочных компонентов
    debugThreadsList.setCellFactory(...);
    debugStackList.setCellFactory(...);
    debugVarsList.setCellFactory(...);
}
```

**Важные моменты:**

- ✅ Использует `Platform.runLater()` для операций, требующих готовой Scene
- ✅ Настраивает все UI компоненты
- ✅ Регистрирует обработчики событий
- ✅ Инициализирует зависимости

#### 2. Управление проектом

##### `setProjectRoot(Path root)`

**Назначение:** Устанавливает корневую директорию проекта.

**Алгоритм:**

```java
public void setProjectRoot(Path actualRoot) {
    // 1. Остановка предыдущего file watcher
    stopFileWatcher();
    
    // 2. Определение типа проекта
    ProjectDetector.ProjectType type = ProjectDetector.detectProjectType(actualRoot);
    
    // 3. Автоопределение (если тип UNKNOWN)
    if (type == ProjectType.UNKNOWN) {
        // Поиск Gradle/Maven/Java проектов в подпапках
    }
    
    // 4. Сохранение корня проекта
    projectRoot = actualRoot;
    rootLabel.setText(projectRoot.toString());
    
    // 5. Построение дерева проекта
    projectTree.setRoot(buildFileTreeRoot(projectRoot));
    projectTree.getRoot().setExpanded(true);
    
    // 6. Разрешение модели проекта (в фоне)
    Thread pm = new Thread(() -> {
        ProjectModel model = modelResolver.resolve(projectRoot, type);
        projectModel = model;
    });
    
    // 7. Индексация проекта (в фоне)
    codeIndexer = new CodeIndexer(projectRoot);
    Thread indexThread = new Thread(() -> {
        codeIndexer.indexProject();
    });
    
    // 8. Обновление целей запуска
    refreshRunTargets();
    
    // 9. Запуск file watcher
    startFileWatcher();
}
```

**Потоки выполнения:**

- **UI Thread:** Обновление дерева, labels
- **Background Thread:** Разрешение модели проекта
- **Background Thread:** Индексация кода
- **Background Thread:** File watcher

#### 3. Управление редактором

##### `openFileInEditor(Path path)`

**Назначение:** Открывает файл в редакторе (новой вкладке или существующей).

**Алгоритм:**

```java
private void openFileInEditor(Path path) {
    Path abs = path.normalize().toAbsolutePath();
    
    // 1. Проверка: файл уже открыт?
    Tab existing = openTabsByPath.get(abs);
    if (existing != null) {
        editorTabs.getSelectionModel().select(existing);
        return;
    }
    
    // 2. Проверка: это изображение?
    if (isImageFile(abs)) {
        openImageInViewer(abs);
        return;
    }
    
    // 3. Создание новой вкладки
    Tab tab = new Tab();
    tab.setText(abs.getFileName().toString());
    
    // 4. Создание CodeArea для Java файлов
    CodeArea area = new CodeArea();
    
    // 5. Загрузка содержимого файла
    String content = Files.readString(abs, StandardCharsets.UTF_8);
    area.replaceText(content);
    
    // 6. Настройка CodeArea
    area.setParagraphGraphicFactory(LineNumberFactory.get(area));
    area.setParagraphGraphicFactory(createGutter(area)); // + breakpoints
    
    // 7. Подсветка синтаксиса
    area.textProperty().addListener((obs, old, newText) -> {
        applyHighlighting(area);
    });
    
    // 8. Автодополнение
    area.caretPositionProperty().addListener((obs, old, pos) -> {
        scheduleAutoComplete(area);
    });
    
    // 9. Диагностика
    scheduleDiagnostics(abs, content);
    
    // 10. Сохранение в Recent Files
    recentFiles.markOpened(abs);
    
    // 11. Добавление вкладки
    tab.setContent(new ScrollPane(area));
    editorTabs.getTabs().add(tab);
    editorTabs.getSelectionModel().select(tab);
    
    // 12. Сохранение связи
    EditorTabData data = new EditorTabData();
    data.path = abs;
    data.editor = area;
    tab.setUserData(data);
    openTabsByPath.put(abs, tab);
}
```

**Особенности:**

- ✅ Проверка на уже открытые файлы
- ✅ Поддержка изображений (отдельный viewer)
- ✅ Настройка подсветки синтаксиса
- ✅ Настройка автодополнения
- ✅ Настройка диагностики
- ✅ Сохранение в Recent Files

##### `onSave()`

**Назначение:** Сохраняет текущий открытый файл.

**Алгоритм:**

```java
@FXML
public void onSave() {
    Tab tab = editorTabs.getSelectionModel().getSelectedItem();
    if (tab == null) return;
    
    EditorTabData data = (EditorTabData) tab.getUserData();
    if (data == null || data.path == null) {
        onSaveAs(); // Если файл новый - сохранить как
        return;
    }
    
    // Получение содержимого из CodeArea
    CodeArea area = data.editor;
    String content = area.getText();
    
    // Сохранение в файл
    Files.writeString(data.path, content, StandardCharsets.UTF_8);
    
    // Обновление статуса
    updateStatus("Saved: " + data.path.getFileName());
    
    // Переиндексация (если это Java файл)
    if (data.path.toString().endsWith(".java")) {
        codeIndexer.indexFile(data.path);
    }
    
    // Обновление диагностики
    scheduleDiagnostics(data.path, content);
}
```

#### 4. Подсветка синтаксиса

##### `applyHighlighting(CodeArea area)`

**Назначение:** Применяет подсветку синтаксиса к CodeArea.

**Алгоритм:**

```java
private void applyHighlighting(CodeArea area) {
    String text = area.getText();
    if (text.isEmpty()) return;
    
    // 1. Вычисление стилей
    StyleSpans<Collection<String>> spans = computeHighlightingWithProblems(area);
    
    // 2. Применение стилей
    area.setStyleSpans(0, spans);
}

private StyleSpans<Collection<String>> computeHighlightingWithProblems(CodeArea area) {
    String text = area.getText();
    
    // 1. Базовая подсветка синтаксиса
    StyleSpans<Collection<String>> syntaxSpans = computeHighlighting(text);
    
    // 2. Подсветка проблем (ошибки/предупреждения)
    StyleSpans<Collection<String>> problemSpans = buildLineOverlaySpans(area);
    
    // 3. Объединение стилей
    return mergeStyleSpans(syntaxSpans, problemSpans);
}

private StyleSpans<Collection<String>> computeHighlighting(String text) {
    Matcher matcher = JAVA_SYNTAX.matcher(text);
    StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();
    
    int lastEnd = 0;
    while (matcher.find()) {
        // Добавление обычного текста
        spansBuilder.add(Collections.emptyList(), matcher.start() - lastEnd);
        
        // Определение группы и стиля
        String styleClass = null;
        if (matcher.group("KEYWORD") != null) styleClass = "keyword";
        else if (matcher.group("STRING") != null) styleClass = "string";
        else if (matcher.group("COMMENT") != null) styleClass = "comment";
        // ... другие группы
        
        // Добавление стилизованного текста
        spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
        lastEnd = matcher.end();
    }
    
    // Добавление оставшегося текста
    spansBuilder.add(Collections.emptyList(), text.length() - lastEnd);
    return spansBuilder.create();
}
```

**Паттерны:**

- `JAVA_SYNTAX` - регулярное выражение для всех элементов синтаксиса
- Группы: `KEYWORD`, `STRING`, `COMMENT`, `PAREN`, `BRACE`, и т.д.
- Использует `StyleSpansBuilder` для эффективного построения стилей

#### 5. Автодополнение

##### `scheduleAutoComplete(CodeArea area)`

**Назначение:** Планирует показ автодополнения после задержки.

**Алгоритм:**

```java
private void scheduleAutoComplete(CodeArea area) {
    // 1. Отмена предыдущего таймера
    if (autoCompleteTimer != null) {
        autoCompleteTimer.stop();
    }
    
    // 2. Создание нового таймера с задержкой из настроек
    int delay = settingsManager.getInt(
        SettingsManager.KEY_AUTO_COMPLETE_DELAY, 300
    );
    autoCompleteTimer = new PauseTransition(Duration.millis(delay));
    
    // 3. Действие при срабатывании таймера
    autoCompleteTimer.setOnFinished(e -> {
        showCompletion(area);
    });
    
    // 4. Запуск таймера
    autoCompleteTimer.play();
}
```

##### `showCompletion(CodeArea area)`

**Назначение:** Показывает меню автодополнения.

**Алгоритм:**

```java
private void showCompletion(CodeArea area) {
    // 1. Получение текста до курсора
    int caretPos = area.getCaretPosition();
    String textBefore = area.getText(0, caretPos);
    
    // 2. Извлечение префикса (последнее слово)
    String prefix = extractPrefix(textBefore);
    if (prefix.length() < 2) return; // Минимум 2 символа
    
    // 3. Поиск совпадений
    List<CompletionItem> suggestions = new ArrayList<>();
    
    // 3.1. Ключевые слова Java
    for (String keyword : JAVA_KEYWORDS) {
        if (keyword.startsWith(prefix)) {
            suggestions.add(new CompletionItem(keyword, KEYWORD, keyword));
        }
    }
    
    // 3.2. Классы из индекса
    List<CodeIndexer.CodeElement> classes = codeIndexer.findClasses(prefix);
    for (CodeElement elem : classes) {
        suggestions.add(new CompletionItem(elem.name, CLASS, elem.name));
    }
    
    // 3.3. Методы из индекса
    List<CodeElement> methods = codeIndexer.findMethods(prefix);
    for (CodeElement elem : methods) {
        suggestions.add(new CompletionItem(elem.name, METHOD, elem.name));
    }
    
    // 3.4. Слова из текущего файла
    List<String> words = extractWordsForCompletion(area.getText());
    for (String word : words) {
        if (word.startsWith(prefix) && word.length() > prefix.length()) {
            suggestions.add(new CompletionItem(word, VARIABLE, word));
        }
    }
    
    // 4. Показ меню
    if (!suggestions.isEmpty()) {
        showCompletionMenu(area, prefix, suggestions);
    }
}
```

**Источники автодополнения:**

1. **Ключевые слова Java** - статический список
2. **Классы из индекса** - через `CodeIndexer`
3. **Методы из индекса** - через `CodeIndexer`
4. **Локальные слова** - из текущего файла

#### 6. Диагностика кода

##### `scheduleDiagnostics(Path file, String content)`

**Назначение:** Планирует проверку кода через javac.

**Алгоритм:**

```java
private void scheduleDiagnostics(Path file, String content) {
    // 1. Отмена предыдущего таймера
    if (diagnosticsTimer != null) {
        diagnosticsTimer.stop();
    }
    
    // 2. Создание таймера с задержкой 500ms
    diagnosticsTimer = new PauseTransition(Duration.millis(500));
    
    // 3. Действие при срабатывании
    diagnosticsTimer.setOnFinished(e -> {
        runDiagnosticsInBackground(file, content);
    });
    
    // 4. Запуск таймера
    diagnosticsTimer.play();
}

private void runDiagnosticsInBackground(Path file, String content) {
    Thread t = new Thread(() -> {
        // 1. Компиляция через javac
        List<Problem> problems = compileWithJavacAndParseProblems(file, content);
        
        // 2. Обновление проблем
        problemsByFile.put(file, problems);
        
        // 3. Обновление UI
        Platform.runLater(() -> {
            updateProblemsPanel();
            refreshHighlightingForFile(file);
        });
    }, "diagnostics");
    t.setDaemon(true);
    t.start();
}

private List<Problem> compileWithJavacAndParseProblems(Path file, String content) {
    // 1. Создание временного файла
    Path tempFile = Files.createTempFile("compile", ".java");
    Files.writeString(tempFile, content);
    
    // 2. Построение команды javac
    List<String> cmd = new ArrayList<>();
    cmd.add("javac");
    cmd.add("-sourcepath");
    cmd.add(String.join(File.pathSeparator, projectModel.sourceRoots));
    cmd.add("-classpath");
    cmd.add(String.join(File.pathSeparator, projectModel.classpath));
    cmd.add("-Xlint:all");
    cmd.add(tempFile.toString());
    
    // 3. Выполнение javac
    ProcessBuilder pb = new ProcessBuilder(cmd);
    Process p = pb.start();
    
    // 4. Парсинг вывода
    try (BufferedReader r = new BufferedReader(
        new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8)
    )) {
        List<Problem> problems = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            // Парсинг строк вида: "file.java:10: error: ..."
            Problem prob = parseProblemLine(line, file);
            if (prob != null) {
                problems.add(prob);
            }
        }
        return problems;
    }
}
```

**Особенности:**

- ✅ Debounce 500ms для оптимизации
- ✅ Использует classpath проекта
- ✅ Парсит вывод javac
- ✅ Обновляет подсветку проблемных строк

### Основные обработчики событий

#### Файловые операции

| Метод | Горячая клавиша | Описание |
|-------|----------------|----------|
| `onOpenFolder()` | `Ctrl+Shift+O` | Открыть проект |
| `onOpenFile()` | `Ctrl+O` | Открыть файл |
| `onSave()` | `Ctrl+S` | Сохранить файл |
| `onSaveAs()` | `Ctrl+Shift+S` | Сохранить как |
| `onRenameFile()` | `F2` | Переименовать файл |
| `onCloseTab()` | `Ctrl+W` | Закрыть вкладку |

#### Редактирование

| Метод | Горячая клавиша | Описание |
|-------|----------------|----------|
| `onFind()` | `Ctrl+F` | Найти в файле |
| `onReplace()` | `Ctrl+H` | Заменить |
| `onRename()` | `Shift+F6` | Переименовать символ |
| `onUndoRefactor()` | `Ctrl+Alt+Z` | Отменить рефакторинг |
| `onSelectAll()` | `Ctrl+A` | Выделить все |

#### Навигация

| Метод | Горячая клавиша | Описание |
|-------|----------------|----------|
| `onGoToClass()` | `Ctrl+Shift+N` | Перейти к классу |
| `onGoToSymbol()` | `Ctrl+Alt+S` | Перейти к символу |
| `onGoToFile()` | `Ctrl+Shift+O` | Перейти к файлу |
| `onRecentFiles()` | `Ctrl+E` | Недавние файлы |
| `onFindUsages()` | `Alt+F7` | Найти использования |

#### Проект

| Метод | Горячая клавиша | Описание |
|-------|----------------|----------|
| `onNewProject()` | `Ctrl+Alt+N` | Новый проект |
| `onNewJavaClass()` | `Ctrl+N` | Новый класс |
| `onRunProject()` | `F5` | Запустить проект |
| `onDebugProject()` | `Shift+F5` | Отладить проект |
| `onGradleBuild()` | `Ctrl+B` | Собрать проект |

### Внутренние классы

#### `EditorTabData`

**Назначение:** Хранит метаданные вкладки редактора.

```java
private static final class EditorTabData {
    Path path;           // Путь к файлу
    CodeArea editor;     // CodeArea редактора
    boolean modified;    // Флаг изменений
}
```

#### `Problem`

**Назначение:** Представляет ошибку или предупреждение.

```java
private static final class Problem {
    Path file;           // Файл с проблемой
    int line;            // Номер строки
    String kind;         // "error" или "warning"
    String message;      // Сообщение
}
```

#### `SearchHit`

**Назначение:** Результат поиска по проекту.

```java
private static final class SearchHit {
    Path file;           // Файл
    int line;            // Номер строки
    String preview;      // Предпросмотр строки
}
```

#### `RunTarget`

**Назначение:** Цель запуска проекта.

```java
private static final class RunTarget {
    String displayName;  // Отображаемое имя
    Path path;           // Путь к файлу/классу
    RunTargetType type;  // Тип (CURRENT_FILE, MAIN_CLASS, ...)
}
```

---

## FXML Views - Структура интерфейса

### `ide-view.fxml`

**Назначение:** Описывает структуру пользовательского интерфейса в декларативном виде.

### Структура разметки

```xml
<BorderPane>
    <top>
        <!-- MenuBar + ToolBar -->
    </top>
    <center>
        <!-- SplitPane: Project Tree + Editor Tabs -->
    </center>
    <bottom>
        <!-- Bottom Panel: Console/Problems/Search/Debug -->
    </bottom>
</BorderPane>
```

### Компоненты

#### 1. MenuBar (Верхнее меню)

**Структура:**

```
MenuBar
├── Project (Ctrl+Alt+N, Ctrl+Shift+O, Ctrl+N, F5, Shift+F5, Ctrl+B)
├── File (Ctrl+Shift+O, Ctrl+O, Ctrl+S, Ctrl+Shift+S, F2, Ctrl+W)
├── Edit (Ctrl+Shift+A, Ctrl+F, Ctrl+H, Shift+F6, Ctrl+Alt+Z, Ctrl+A)
├── Navigate (Ctrl+Shift+N, Ctrl+Alt+S, Ctrl+Shift+O, Ctrl+E, Alt+F7)
├── View (Refresh, Toggle Console, Toggle Bottom Panel, Toggle Theme)
├── Search (Ctrl+Shift+F)
├── Git (Clone from GitHub)
├── Settings (Preferences)
└── Build (Ctrl+B, Ctrl+R, test, clean, Package as EXE)
```

#### 2. ToolBar (Панель инструментов)

**Компоненты:**

- Кнопки: Open Folder, Open File, Save, Save As
- Кнопки проекта: New Project, Build, Run, Debug, Test
- ComboBox: Выбор цели запуска (`runTargetComboBox`)
- Labels: Root path, Error count, Warning count

#### 3. Center Area (Основная область)

**SplitPane с двумя частями:**

**Левая часть (28%):**
```xml
<VBox>
    <Label text="Project" />
    <TreeView fx:id="projectTree" />
</VBox>
```

**Правая часть (72%):**
```xml
<BorderPane>
    <top>
        <Label text="Editor" />
    </top>
    <center>
        <TabPane fx:id="editorTabs" />
    </center>
</BorderPane>
```

#### 4. Bottom Panel (Нижняя панель)

**TabPane с вкладками:**

**Console:**
```xml
<TextArea fx:id="consoleArea" 
          editable="true" 
          onKeyPressed="#onConsoleKeyPressed" />
```

**Problems:**
```xml
<ListView fx:id="problemsList" />
```

**Search:**
```xml
<ListView fx:id="searchResultsList" />
```

**Debug:**
```xml
<VBox>
    <ToolBar>
        <!-- Debug controls: Continue, Step Over, Step Into, etc. -->
    </ToolBar>
    <SplitPane>
        <ListView fx:id="debugThreadsList" />  <!-- Threads -->
        <ListView fx:id="debugStackList" />     <!-- Stack -->
        <ListView fx:id="debugVarsList" />      <!-- Variables -->
    </SplitPane>
    <TextArea fx:id="debugArea" />              <!-- Debug output -->
</VBox>
```

### Привязка к контроллеру

**FXML атрибут:**
```xml
fx:controller="com.example.f_ex.IdeController"
```

**Инъекция компонентов:**
```java
@FXML private TreeView<Path> projectTree;
@FXML private TabPane editorTabs;
@FXML private TextArea consoleArea;
// ... другие компоненты
```

**Обработчики событий:**
```xml
<MenuItem onAction="#onSave" text="Save" />
<Button onAction="#onOpenFolder" text="Open Folder" />
```

---

## CSS Styling - Стилизация

### `ide.css`

**Назначение:** Определяет стили и темы для всех UI компонентов.

### Структура стилей

#### 1. Базовые стили

```css
.root {
    -fx-font-family: "Consolas", "Monaco", monospace;
    -fx-font-size: 13px;
}
```

#### 2. Темная тема

```css
.root.dark-theme {
    -fx-base: #1e1e1e;
    -fx-background: #252526;
    -fx-control-inner-background: #1e1e1e;
    -fx-text-base-color: #d4d4d4;
    /* ... другие цвета */
}
```

**Применение:**
```java
stage.getScene().getRoot().getStyleClass().add("dark-theme");
```

#### 3. Подсветка синтаксиса

```css
.keyword {
    -fx-fill: #569cd6;
    -fx-font-weight: bold;
}

.string {
    -fx-fill: #ce9178;
}

.comment {
    -fx-fill: #6a9955;
    -fx-font-style: italic;
}
```

#### 4. Подсветка проблем

```css
.errLine {
    -rtfx-background-color: rgba(255, 0, 0, 0.12);
}

.warnLine {
    -rtfx-background-color: rgba(255, 215, 0, 0.12);
}
```

**Применение:**
- Добавляется к параграфам CodeArea через `StyleSpans`
- Использует `-rtfx-background-color` (RichTextFX специфичный)

#### 5. Стили компонентов

```css
.menu-bar {
    -fx-background-color: #2d2d30;
}

.tool-bar {
    -fx-background-color: #3e3e42;
}

.tab-pane {
    -fx-tab-min-width: 120px;
}
```

### Применение темы

```java
private void applyTheme(String theme) {
    Stage stage = getStage();
    if (stage == null || stage.getScene() == null) return;
    
    if (SettingsManager.THEME_DARK.equals(theme)) {
        stage.getScene().getRoot().getStyleClass().add("dark-theme");
    } else {
        stage.getScene().getRoot().getStyleClass().remove("dark-theme");
    }
}
```

---

## Взаимодействие компонентов

### Поток данных: Открытие файла

```
User double-clicks file in projectTree
    │
    ▼
projectTree.setOnMouseClicked()
    │
    ▼
IdeController.openFileInEditor(path)
    │
    ├─→ Check if already open → Select existing tab
    │
    ├─→ Check if image → openImageInViewer()
    │
    ├─→ Create CodeArea
    │   ├─→ Load file content
    │   ├─→ Setup line numbers
    │   ├─→ Setup breakpoints (gutter)
    │   ├─→ Setup syntax highlighting listener
    │   ├─→ Setup autocomplete listener
    │   └─→ Setup diagnostics listener
    │
    ├─→ Create Tab
    │   ├─→ Set content (ScrollPane with CodeArea)
    │   ├─→ Set title (filename)
    │   └─→ Set UserData (EditorTabData)
    │
    ├─→ Add to editorTabs
    │
    ├─→ recentFiles.markOpened(path)
    │
    └─→ scheduleDiagnostics(path, content)
        │
        └─→ [After 500ms] compileWithJavacAndParseProblems()
            │
            └─→ Update problemsByFile
                │
                └─→ updateProblemsPanel()
                    │
                    └─→ Refresh highlighting
```

### Поток данных: Сохранение файла

```
User presses Ctrl+S
    │
    ▼
IdeController.onSave()
    │
    ├─→ Get current tab
    │
    ├─→ Get EditorTabData
    │
    ├─→ Get CodeArea content
    │
    ├─→ Files.writeString(path, content)
    │
    ├─→ codeIndexer.indexFile(path) [if .java]
    │
    ├─→ scheduleDiagnostics(path, content)
    │
    └─→ updateStatus("Saved: ...")
```

### Поток данных: Автодополнение

```
User types in CodeArea
    │
    ▼
CodeArea.caretPositionProperty().addListener()
    │
    ▼
IdeController.scheduleAutoComplete(area)
    │
    ├─→ Stop previous timer
    │
    ├─→ Create PauseTransition(300ms)
    │
    └─→ [After 300ms] showCompletion(area)
        │
        ├─→ Extract prefix (last word)
        │
        ├─→ codeIndexer.findClasses(prefix)
        │
        ├─→ codeIndexer.findMethods(prefix)
        │
        ├─→ extractWordsForCompletion()
        │
        ├─→ Filter JAVA_KEYWORDS
        │
        ├─→ Build suggestions list
        │
        └─→ Show ContextMenu
            │
            └─→ User selects → insertCompletion()
```

---

## Жизненный цикл UI

### 1. Запуск приложения

```
JVM starts
    │
    ▼
Launcher.main(args)
    │
    ▼
Application.launch(HelloApplication.class, args)
    │
    ▼
HelloApplication.start(Stage)
    │
    ├─→ Load ide-view.fxml
    │   └─→ FXMLLoader creates IdeController instance
    │
    ├─→ Load ide.css
    │
    ├─→ Create Scene(1100x750)
    │
    ├─→ Set scene to stage
    │
    └─→ stage.show()
        │
        ▼
    [JavaFX Application Thread]
        │
        ▼
IdeController.initialize()
    │
    ├─→ Initialize SettingsManager
    │
    ├─→ Apply theme (Platform.runLater)
    │
    ├─→ Setup projectTree
    │
    ├─→ Register event handlers
    │
    ├─→ Setup global accelerators
    │
    └─→ Initialize debug components
```

### 2. Открытие проекта

```
User: Project → Open Project...
    │
    ▼
IdeController.onOpenFolder()
    │
    ├─→ DirectoryChooser.showDialog()
    │
    └─→ setProjectRoot(selectedPath)
        │
        ├─→ [UI Thread] Update projectTree
        │
        ├─→ [Background] ProjectModelResolver.resolve()
        │
        ├─→ [Background] CodeIndexer.indexProject()
        │
        └─→ [Background] startFileWatcher()
```

### 3. Редактирование файла

```
User types in CodeArea
    │
    ├─→ CodeArea.textProperty() changes
    │   └─→ applyHighlighting()
    │
    ├─→ CodeArea.caretPositionProperty() changes
    │   └─→ scheduleAutoComplete()
    │
    └─→ scheduleDiagnostics()
        │
        └─→ [After 500ms] compileWithJavacAndParseProblems()
            │
            └─→ Update problemsByFile
                │
                └─→ [Platform.runLater] updateProblemsPanel()
```

### 4. Закрытие приложения

```
User closes window
    │
    ▼
Stage.close()
    │
    ▼
Application.stop() [if overridden]
    │
    ├─→ stopFileWatcher()
    │
    ├─→ debugSession.stop()
    │
    └─→ Save settings (if needed)
```

---

## Резюме

### Ключевые особенности UI/Controller Layer:

1. ✅ **MVC архитектура** - четкое разделение View и Controller
2. ✅ **FXML декларативность** - структура UI в XML
3. ✅ **Реактивность** - автоматическое обновление при изменениях
4. ✅ **Многопоточность** - тяжелые операции в фоне, UI остается отзывчивым
5. ✅ **Расширяемость** - легко добавлять новые обработчики и компоненты
6. ✅ **Темизация** - поддержка светлой и темной темы
7. ✅ **Горячие клавиши** - полная поддержка стандартных комбинаций IDE

### Зависимости от других слоев:

- **Service Layer:** RefactorRenameService, ExePackager, FileOperationsService
- **Data Layer:** CodeIndexer, SettingsManager, RecentFilesManager
- **Infrastructure:** ProjectDetector, ProjectModelResolver, DebugSession

### Следующие шаги:

После изучения UI/Controller Layer можно переходить к:
- **Project & Build Layer** - определение и запуск проектов
- **Editor & Code Analysis** - индексация и анализ кода
- **Refactoring Layer** - переименование и рефакторинг

---

**Версия:** 1.0  
**Дата:** 2026-01-20  
**Автор:** F_EX Development Team
