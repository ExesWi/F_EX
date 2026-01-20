# Детальная документация: Settings & File System Layer

## 📋 Содержание

1. [Обзор слоя](#обзор-слоя)
2. [SettingsManager - Управление настройками](#settingsmanager---управление-настройками)
3. [File Watcher - Мониторинг файловой системы](#file-watcher---мониторинг-файловой-системы)
4. [Интеграция в IdeController](#интеграция-в-idecontroller)
5. [Сценарии использования](#сценарии-использования)
6. [Ограничения и улучшения](#ограничения-и-улучшения)

---

## Обзор слоя

**Settings & File System Layer** предоставляет **управление настройками IDE** и **мониторинг изменений файловой системы**.

### Возможности:

- ✅ **SettingsManager** - сохранение и загрузка настроек IDE
- ✅ **File Watcher** - мониторинг изменений файлов в реальном времени
- ✅ **Automatic Tree Refresh** - автоматическое обновление дерева проекта
- ✅ **Persistent Settings** - настройки сохраняются между сессиями

### Компоненты слоя:

```
Settings & File System Layer
├── SettingsManager.java    # Управление настройками
└── File Watcher (IdeController) # Мониторинг файловой системы
```

---

## SettingsManager - Управление настройками

Файл: `src/main/java/com/example/f_ex/SettingsManager.java`

### Назначение

**SettingsManager** управляет настройками IDE, сохраняя их в файл `.ide-settings.properties`.

### Структура класса

```java
public class SettingsManager {
    private static final String SETTINGS_FILE = ".ide-settings.properties";
    private final Properties settings;    // Хранилище настроек
    private final Path settingsPath;       // Путь к файлу настроек
}
```

### Инициализация

```java
public SettingsManager(Path projectRoot) {
    this.settings = new Properties();
    this.settingsPath = projectRoot != null ? 
        projectRoot.resolve(SETTINGS_FILE) : 
        Paths.get(System.getProperty("user.home")).resolve(SETTINGS_FILE);
    loadSettings();
}
```

**Логика:**

- Если `projectRoot` задан - настройки в корне проекта
- Если `projectRoot == null` - настройки в домашней директории пользователя

**Расположение файла:**

- `{projectRoot}/.ide-settings.properties` (для проекта)
- `{user.home}/.ide-settings.properties` (глобальные)

### Методы

#### `loadSettings()`

**Назначение:** Загружает настройки из файла.

```java
private void loadSettings() {
    if (Files.exists(settingsPath)) {
        try (var reader = Files.newBufferedReader(settingsPath)) {
            settings.load(reader);
        } catch (IOException e) {
            // Используем настройки по умолчанию
        }
    }
}
```

**Особенности:**

- ✅ Если файл не существует - используются настройки по умолчанию
- ✅ Игнорирует ошибки чтения (fallback на defaults)

#### `saveSettings()`

**Назначение:** Сохраняет настройки в файл.

```java
public void saveSettings() {
    try {
        Files.createDirectories(settingsPath.getParent());
        try (var writer = Files.newBufferedWriter(settingsPath)) {
            settings.store(writer, "IDE Settings");
        }
    } catch (IOException e) {
        // Игнорируем ошибки сохранения
    }
}
```

**Особенности:**

- ✅ Создает директорию, если не существует
- ✅ Автоматически вызывается при изменении настроек

#### `get(String key, String defaultValue)` / `set(String key, String value)`

**Назначение:** Получение и установка строковых настроек.

```java
public String get(String key, String defaultValue) {
    return settings.getProperty(key, defaultValue);
}

public void set(String key, String value) {
    settings.setProperty(key, value);
    saveSettings();  // Автоматическое сохранение
}
```

#### `getBoolean(String key, boolean defaultValue)` / `setBoolean(String key, boolean value)`

**Назначение:** Получение и установка булевых настроек.

```java
public boolean getBoolean(String key, boolean defaultValue) {
    String value = settings.getProperty(key);
    return value != null ? Boolean.parseBoolean(value) : defaultValue;
}

public void setBoolean(String key, boolean value) {
    settings.setProperty(key, String.valueOf(value));
    saveSettings();
}
```

#### `getInt(String key, int defaultValue)` / `setInt(String key, int value)`

**Назначение:** Получение и установка числовых настроек.

```java
public int getInt(String key, int defaultValue) {
    String value = settings.getProperty(key);
    try {
        return value != null ? Integer.parseInt(value) : defaultValue;
    } catch (NumberFormatException e) {
        return defaultValue;
    }
}

public void setInt(String key, int value) {
    settings.setProperty(key, String.valueOf(value));
    saveSettings();
}
```

### Константы настроек

```java
public static final String KEY_THEME = "theme";
public static final String KEY_FONT_FAMILY = "font.family";
public static final String KEY_FONT_SIZE = "font.size";
public static final String KEY_AUTO_COMPLETE = "auto.complete.enabled";
public static final String KEY_AUTO_COMPLETE_DELAY = "auto.complete.delay";

public static final String THEME_LIGHT = "light";
public static final String THEME_DARK = "dark";
```

**Использование:**

```java
// Получение темы
String theme = settingsManager.get(SettingsManager.KEY_THEME, SettingsManager.THEME_LIGHT);

// Установка темы
settingsManager.set(SettingsManager.KEY_THEME, SettingsManager.THEME_DARK);

// Получение булевой настройки
boolean autoComplete = settingsManager.getBoolean(SettingsManager.KEY_AUTO_COMPLETE, true);

// Установка числовой настройки
settingsManager.setInt(SettingsManager.KEY_AUTO_COMPLETE_DELAY, 500);
```

### Формат файла настроек

**Пример `.ide-settings.properties`:**

```properties
#IDE Settings
theme=dark
font.family=Consolas
font.size=14
auto.complete.enabled=true
auto.complete.delay=300
```

---

## File Watcher - Мониторинг файловой системы

### Назначение

**File Watcher** использует `java.nio.file.WatchService` для мониторинга изменений файловой системы в реальном времени.

### Технология

- **WatchService** - API Java для мониторинга файловой системы
- **WatchEvent** - события изменения (CREATE, DELETE, MODIFY)
- **Recursive Watching** - рекурсивная регистрация директорий

### Структура в IdeController

```java
private WatchService fileWatcher;
private Thread fileWatcherThread;
private PauseTransition treeRefreshTimer;
```

### Алгоритм `startFileWatcher()`

**Назначение:** Запускает мониторинг файловой системы.

**Алгоритм:**

```java
private void startFileWatcher() {
    stopFileWatcher();  // Остановка предыдущего watcher
    if (projectRoot == null) return;
    
    try {
        // 1. Создание WatchService
        fileWatcher = FileSystems.getDefault().newWatchService();
        
        // 2. Регистрация корневой директории и всех поддиректорий
        registerDirectory(projectRoot);
        
        // 3. Запуск потока мониторинга
        fileWatcherThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // 4. Ожидание события
                    WatchKey key = fileWatcher.take();
                    if (key == null) continue;
                    
                    boolean shouldRefresh = false;
                    
                    // 5. Обработка событий
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                        
                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path path = ev.context();
                        Path fullPath = ((Path) key.watchable()).resolve(path);
                        
                        // 6. Пропуск скрытых путей
                        if (shouldHidePath(fullPath)) continue;
                        
                        // 7. Определение необходимости обновления
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE || 
                            kind == StandardWatchEventKinds.ENTRY_DELETE ||
                            kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                            shouldRefresh = true;
                            
                            // 8. Регистрация новых директорий
                            if (kind == StandardWatchEventKinds.ENTRY_CREATE && 
                                Files.isDirectory(fullPath)) {
                                registerDirectory(fullPath);
                            }
                        }
                    }
                    
                    // 9. Планирование обновления дерева
                    if (shouldRefresh) {
                        Platform.runLater(() -> {
                            if (projectRoot != null) {
                                scheduleTreeRefresh();
                            }
                        });
                    }
                    
                    // 10. Сброс ключа для продолжения мониторинга
                    boolean valid = key.reset();
                    if (!valid) break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Platform.runLater(() -> logToConsole("File watcher error: " + e.getMessage()));
            }
        }, "file-watcher");
        
        fileWatcherThread.setDaemon(true);
        fileWatcherThread.start();
    } catch (Exception e) {
        logToConsole("Failed to start file watcher: " + e.getMessage());
    }
}
```

**Шаги:**

1. **Создание WatchService** - `FileSystems.getDefault().newWatchService()`
2. **Регистрация директорий** - `registerDirectory(projectRoot)` (рекурсивно)
3. **Ожидание событий** - `fileWatcher.take()` (блокирующий вызов)
4. **Обработка событий** - `key.pollEvents()`
5. **Планирование обновления** - `scheduleTreeRefresh()` (debounce)
6. **Сброс ключа** - `key.reset()` для продолжения мониторинга

### Алгоритм `registerDirectory(Path dir)`

**Назначение:** Регистрирует директорию для мониторинга (рекурсивно).

**Алгоритм:**

```java
private void registerDirectory(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) return;
    if (shouldHidePath(dir)) return;  // Пропуск скрытых директорий
    
    try {
        // 1. Регистрация директории
        dir.register(fileWatcher, 
            StandardWatchEventKinds.ENTRY_CREATE,  // Создание
            StandardWatchEventKinds.ENTRY_DELETE,  // Удаление
            StandardWatchEventKinds.ENTRY_MODIFY   // Изменение
        );
        
        // 2. Рекурсивная регистрация поддиректорий
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (Files.isDirectory(child) && !shouldHidePath(child)) {
                    registerDirectory(child);  // Рекурсия
                }
            }
        }
    } catch (IOException ignored) {
        // Игнорируем ошибки регистрации
    }
}
```

**Особенности:**

- ✅ Рекурсивная регистрация всех поддиректорий
- ✅ Пропуск скрытых директорий (`build`, `.gradle`, `.git`, и т.д.)
- ✅ Регистрация новых директорий при их создании

### Алгоритм `stopFileWatcher()`

**Назначение:** Останавливает мониторинг файловой системы.

```java
private void stopFileWatcher() {
    // 1. Прерывание потока
    if (fileWatcherThread != null) {
        fileWatcherThread.interrupt();
        fileWatcherThread = null;
    }
    
    // 2. Закрытие WatchService
    if (fileWatcher != null) {
        try {
            fileWatcher.close();
        } catch (IOException ignored) {
        }
        fileWatcher = null;
    }
}
```

**Особенности:**

- ✅ Прерывание потока мониторинга
- ✅ Закрытие WatchService
- ✅ Вызывается при смене проекта

### Алгоритм `scheduleTreeRefresh()`

**Назначение:** Планирует обновление дерева проекта с debounce.

**Алгоритм:**

```java
private void scheduleTreeRefresh() {
    if (treeRefreshTimer == null) {
        treeRefreshTimer = new PauseTransition(Duration.millis(300));
        treeRefreshTimer.setOnFinished(e -> {
            if (projectRoot != null) {
                refreshTreeIfUnderRoot(projectRoot);
            }
        });
    }
    treeRefreshTimer.stop();  // Отмена предыдущего таймера
    treeRefreshTimer.play();  // Запуск нового таймера
}
```

**Особенности:**

- ✅ Debounce 300ms - предотвращает частые обновления
- ✅ Отменяет предыдущий таймер при новом событии
- ✅ Обновляет дерево проекта через `refreshTreeIfUnderRoot()`

### Типы событий

#### ENTRY_CREATE

**Назначение:** Файл или директория созданы.

**Обработка:**

- Если создана директория - регистрируется для мониторинга
- Планируется обновление дерева проекта

#### ENTRY_DELETE

**Назначение:** Файл или директория удалены.

**Обработка:**

- Планируется обновление дерева проекта
- WatchKey автоматически удаляется при удалении директории

#### ENTRY_MODIFY

**Назначение:** Файл изменен.

**Обработка:**

- Планируется обновление дерева проекта
- Может срабатывать несколько раз при сохранении файла

#### OVERFLOW

**Назначение:** События потеряны (слишком много событий).

**Обработка:**

- Игнорируется
- Обновление дерева не планируется

---

## Интеграция в IdeController

### Инициализация SettingsManager

```java
public void initialize() {
    ideRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    settingsManager = new SettingsManager(ideRoot);
    
    // Применение темы
    Platform.runLater(() -> {
        applyTheme(settingsManager.get(SettingsManager.KEY_THEME, SettingsManager.THEME_LIGHT));
    });
}
```

### Запуск File Watcher

```java
public void setProjectRoot(Path actualRoot) {
    // ...
    stopFileWatcher();  // Остановка предыдущего watcher
    // ...
    startFileWatcher();  // Запуск нового watcher
}
```

### Использование настроек

#### Применение темы

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

#### Применение настроек шрифта

```java
private void applyFontSettings(String fontFamily, int fontSize) {
    for (Tab tab : editorTabs.getTabs()) {
        EditorTabData data = (EditorTabData) tab.getUserData();
        if (data != null && data.editor != null) {
            data.editor.setStyle(String.format(
                "-fx-font-family: '%s'; -fx-font-size: %dpx;", 
                fontFamily, fontSize
            ));
        }
    }
}
```

#### Использование задержки автодополнения

```java
autoCompleteTimer = new PauseTransition(
    Duration.millis(settingsManager.getInt(
        SettingsManager.KEY_AUTO_COMPLETE_DELAY, 
        300
    ))
);
```

---

## Сценарии использования

### Сценарий 1: Сохранение и загрузка настроек

```
User: Changes theme to "dark"
    │
    ▼
onToggleTheme() / onSettings()
    │
    └─→ settingsManager.set(SettingsManager.KEY_THEME, "dark")
        │
        ├─→ settings.setProperty("theme", "dark")
        └─→ saveSettings()
            │
            └─→ Files.write(.ide-settings.properties)
                │
                └─→ File: theme=dark
                    │
                    └─→ Next launch: settingsManager.get(KEY_THEME, "light")
                        │
                        └─→ Returns "dark"
```

### Сценарий 2: Мониторинг файловой системы

```
User: Creates new file "Test.java" in project
    │
    ▼
File System Event: ENTRY_CREATE
    │
    ▼
fileWatcher.take() → WatchKey
    │
    ├─→ key.pollEvents() → [ENTRY_CREATE: Test.java]
    ├─→ shouldHidePath() → false
    ├─→ shouldRefresh = true
    └─→ Platform.runLater(() -> scheduleTreeRefresh())
        │
        └─→ (after 300ms) refreshTreeIfUnderRoot()
            │
            └─→ projectTree.setRoot(buildFileTreeRoot(projectRoot))
                │
                └─→ Tree updated with Test.java
```

### Сценарий 3: Рекурсивная регистрация

```
User: Opens project with structure:
project/
├── src/
│   └── main/
│       └── java/
│           └── Main.java
└── build/

startFileWatcher()
    │
    └─→ registerDirectory(project)
        │
        ├─→ project.register(fileWatcher, ...)
        └─→ For each child:
            │
            ├─→ registerDirectory(src)
            │   ├─→ src.register(fileWatcher, ...)
            │   └─→ For each child:
            │       │
            │       └─→ registerDirectory(main)
            │           ├─→ main.register(fileWatcher, ...)
            │           └─→ For each child:
            │               │
            │               └─→ registerDirectory(java)
            │                   └─→ java.register(fileWatcher, ...)
            │
            └─→ registerDirectory(build) → SKIPPED (shouldHidePath)
```

---

## Ограничения и улучшения

### Текущие ограничения

#### 1. File Watcher

**Ограничения:**

- ❌ Может не работать на некоторых файловых системах (например, сетевые диски)
- ❌ OVERFLOW события игнорируются (могут быть потеряны изменения)
- ❌ Нет обработки переименования файлов (создается как CREATE + DELETE)

**Улучшения:**

- Обработка OVERFLOW событий (периодическое обновление)
- Обработка переименования (детекция через временные метки)
- Fallback на периодический опрос для проблемных файловых систем

#### 2. SettingsManager

**Ограничения:**

- ❌ Простой формат (Properties)
- ❌ Нет валидации настроек
- ❌ Нет миграции настроек при обновлении IDE

**Улучшения:**

- JSON формат для более сложных настроек
- Валидация настроек
- Миграция настроек при обновлении

#### 3. Производительность

**Ограничения:**

- ❌ Регистрация всех директорий может быть медленной для больших проектов
- ❌ Debounce 300ms может быть слишком коротким для быстрых изменений

**Улучшения:**

- Ленивая регистрация директорий (только при открытии)
- Настраиваемый debounce
- Оптимизация для больших проектов

### Планируемые улучшения

1. **Улучшенный File Watcher:**
   - Обработка OVERFLOW
   - Обработка переименования
   - Fallback на периодический опрос

2. **Расширенный SettingsManager:**
   - JSON формат
   - Валидация настроек
   - Миграция настроек

3. **Оптимизация производительности:**
   - Ленивая регистрация
   - Настраиваемый debounce
   - Кэширование

---

## Резюме

### Ключевые особенности Settings & File System Layer:

1. ✅ **SettingsManager** - простое и эффективное управление настройками
2. ✅ **File Watcher** - мониторинг файловой системы в реальном времени
3. ✅ **Automatic Tree Refresh** - автоматическое обновление дерева проекта
4. ✅ **Persistent Settings** - настройки сохраняются между сессиями
5. ✅ **Recursive Watching** - мониторинг всех поддиректорий

### Технические детали:

- **Properties** - формат хранения настроек
- **WatchService** - API Java для мониторинга файловой системы
- **Debounce** - предотвращение частых обновлений (300ms)

### Производительность:

- ✅ Автоматическое сохранение настроек
- ✅ Debounce для обновления дерева
- ✅ Фоновый поток для мониторинга

---

**Версия:** 1.0  
**Дата:** 2026-01-20  
**Файл:** `Documents/08-Settings-FileSystem-Layer.md`
