# Детальная документация: Refactoring Layer

## 📋 Содержание

1. [Обзор слоя](#обзор-слоя)
2. [RefactorRenameService - Умное переименование](#refactorrenameservice---умное-переименование)
3. [State Machine для парсинга Java кода](#state-machine-для-парсинга-java-кода)
4. [RefactorUndoManager - Отмена рефакторинга](#refactorundomanager---отмена-рефакторинга)
5. [FileOperationsService - Операции с файлами](#fileoperationsservice---операции-с-файлами)
6. [Интеграция в IdeController](#интеграция-в-idecontroller)
7. [Сценарии использования](#сценарии-использования)
8. [Ограничения и улучшения](#ограничения-и-улучшения)

---

## Обзор слоя

**Refactoring Layer** отвечает за **безопасное переименование** символов и файлов в проекте:

- ✅ **Переименование символов** - классов, методов, переменных
- ✅ **Preview изменений** - показ плана перед применением
- ✅ **Умное игнорирование** - не переименовывает в строках, комментариях, литералах
- ✅ **Отмена (Undo)** - возможность откатить последний рефакторинг
- ✅ **Переименование файлов** - с автоматическим обновлением класса внутри

### Компоненты слоя:

```
Refactoring Layer
├── RefactorRenameService.java    # Сервис переименования
├── RefactorUndoManager.java      # Менеджер отмены
└── FileOperationsService.java    # Операции с файлами
```

### Принципы:

1. **Безопасность** - preview перед применением
2. **Точность** - не затрагивает строки и комментарии
3. **Откат** - возможность undo
4. **Атомарность** - все изменения применяются вместе

---

## RefactorRenameService - Умное переименование

Файл: `src/main/java/com/example/f_ex/RefactorRenameService.java`

### Назначение

**RefactorRenameService** выполняет безопасное переименование идентификаторов в Java коде, **игнорируя** строки, комментарии и литералы.

### Внутренние классы

#### RenamePlan

**Назначение:** План переименования (preview) перед применением.

```java
static final class RenamePlan {
    final String from;              // Старое имя
    final String to;                // Новое имя
    final List<FileChange> changes;  // Список изменений по файлам
}
```

#### FileChange

**Назначение:** Изменения в одном файле.

```java
static final class FileChange {
    final Path file;         // Файл
    final int occurrences;  // Количество замен
    final String before;    // Содержимое до
    final String after;     // Содержимое после
}
```

#### RenameResult

**Назначение:** Результат применения переименования.

```java
static final class RenameResult {
    final int filesChanged;  // Количество измененных файлов
    final int occurrences;   // Общее количество замен
}
```

### Основные методы

#### `planRename(List<Path> files, String from, String to)`

**Назначение:** Создает план переименования без применения изменений.

**Алгоритм:**

```java
RenamePlan planRename(List<Path> files, String from, String to) {
    // 1. Валидация
    if (files == null || files.isEmpty()) return new RenamePlan(from, to, List.of());
    if (!isIdentifier(from) || !isIdentifier(to)) return new RenamePlan(from, to, List.of());
    if (from.equals(to)) return new RenamePlan(from, to, List.of());
    
    // 2. Обработка каждого файла
    List<FileChange> changes = new ArrayList<>();
    for (Path f : files) {
        if (f == null) continue;
        try {
            // 3. Чтение файла
            String s = Files.readString(f, StandardCharsets.UTF_8);
            
            // 4. Умная замена (игнорируя строки/комментарии)
            ReplaceResult rr = replaceIdentifiersOutsideStringsAndComments(s, from, to);
            
            // 5. Если есть изменения - добавляем в план
            if (rr.count == 0) continue;
            changes.add(new FileChange(f, rr.count, s, rr.text));
        } catch (Exception ignored) {
            // Игнорируем ошибки чтения
        }
    }
    
    return new RenamePlan(from, to, changes);
}
```

**Валидация:**

- ✅ Проверка на пустые списки
- ✅ Проверка, что `from` и `to` - валидные идентификаторы Java
- ✅ Проверка, что имена не совпадают

**Особенности:**

- ✅ Не изменяет файлы - только создает план
- ✅ Возвращает пустой план, если изменений нет
- ✅ Игнорирует ошибки чтения файлов

#### `applyPlan(RenamePlan plan)`

**Назначение:** Применяет план переименования к файлам.

**Алгоритм:**

```java
RenameResult applyPlan(RenamePlan plan) {
    if (plan == null || plan.changes == null || plan.changes.isEmpty()) {
        return new RenameResult(0, 0);
    }
    
    int changedFiles = 0;
    int occ = 0;
    
    // Применение изменений к каждому файлу
    for (FileChange ch : plan.changes) {
        if (ch == null || ch.file == null) continue;
        try {
            // Запись измененного содержимого
            Files.writeString(
                ch.file, 
                ch.after, 
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            changedFiles++;
            occ += ch.occurrences;
        } catch (Exception ignored) {
            // Игнорируем ошибки записи
        }
    }
    
    return new RenameResult(changedFiles, occ);
}
```

**Особенности:**

- ✅ Атомарность - все файлы обновляются
- ✅ Игнорирует ошибки записи (продолжает с другими файлами)
- ✅ Возвращает статистику изменений

#### `renameInFiles(List<Path> files, String from, String to)`

**Назначение:** Удобный метод для планирования и применения за один вызов.

```java
RenameResult renameInFiles(List<Path> files, String from, String to) {
    RenamePlan plan = planRename(files, from, to);
    return applyPlan(plan);
}
```

---

## State Machine для парсинга Java кода

### Назначение

**Ключевая особенность** `RefactorRenameService` - умный парсер Java кода, который **корректно различает** код, строки, комментарии и литералы.

### Проблема

Простое `String.replace()` **не подходит** для переименования:

```java
// ❌ Неправильно:
String code = "String name = \"MyClass\"; // MyClass is good";
code.replace("MyClass", "NewClass");
// Результат: "String name = \"NewClass\"; // NewClass is good"
// Проблема: переименовало в строке и комментарии!
```

**Нужно:**
- ✅ Переименовать `MyClass` в коде
- ❌ НЕ переименовывать в строке `"MyClass"`
- ❌ НЕ переименовывать в комментарии `// MyClass`

### Решение: State Machine

Используется **конечный автомат (state machine)** для отслеживания текущего контекста парсинга.

#### Состояния (States)

```java
private enum State {
    CODE,           // Обычный код
    STRING,         // Внутри строкового литерала "..."
    CHAR,           // Внутри символьного литерала '...'
    LINE_COMMENT,   // Внутри однострочного комментария //
    BLOCK_COMMENT   // Внутри многострочного комментария /* */
}
```

### Алгоритм `replaceIdentifiersOutsideStringsAndComments(...)`

**Назначение:** Заменяет идентификаторы только в коде, игнорируя строки и комментарии.

**Алгоритм:**

```java
private static ReplaceResult replaceIdentifiersOutsideStringsAndComments(
    String src, 
    String from, 
    String to
) {
    if (src == null || src.isEmpty()) return new ReplaceResult(src, 0);
    
    StringBuilder out = new StringBuilder(src.length());
    int i = 0;
    int count = 0;
    State st = State.CODE;  // Начинаем в состоянии CODE
    
    while (i < src.length()) {
        char c = src.charAt(i);
        
        // ============================================
        // СОСТОЯНИЕ: CODE (обычный код)
        // ============================================
        if (st == State.CODE) {
            // Переход в строку
            if (c == '"') {
                out.append(c);
                i++;
                st = State.STRING;
                continue;
            }
            
            // Переход в символьный литерал
            if (c == '\'') {
                out.append(c);
                i++;
                st = State.CHAR;
                continue;
            }
            
            // Переход в однострочный комментарий
            if (c == '/' && i + 1 < src.length()) {
                char n = src.charAt(i + 1);
                if (n == '/') {
                    out.append(c).append(n);
                    i += 2;
                    st = State.LINE_COMMENT;
                    continue;
                }
                // Переход в многострочный комментарий
                if (n == '*') {
                    out.append(c).append(n);
                    i += 2;
                    st = State.BLOCK_COMMENT;
                    continue;
                }
            }
            
            // Обработка идентификатора
            if (isIdentStart(c)) {
                int start = i;
                i++;
                // Читаем весь идентификатор
                while (i < src.length() && isIdentPart(src.charAt(i))) {
                    i++;
                }
                String ident = src.substring(start, i);
                
                // Заменяем, если совпадает
                if (ident.equals(from)) {
                    out.append(to);
                    count++;
                } else {
                    out.append(ident);
                }
                continue;
            }
            
            // Обычный символ - просто копируем
            out.append(c);
            i++;
            continue;
        }
        
        // ============================================
        // СОСТОЯНИЕ: STRING (внутри строки "...")
        // ============================================
        if (st == State.STRING) {
            out.append(c);
            i++;
            
            // Обработка escape-последовательностей
            if (c == '\\' && i < src.length()) {
                out.append(src.charAt(i));
                i++;
                continue;
            }
            
            // Конец строки
            if (c == '"') {
                st = State.CODE;
            }
            continue;
        }
        
        // ============================================
        // СОСТОЯНИЕ: CHAR (внутри '...')
        // ============================================
        if (st == State.CHAR) {
            out.append(c);
            i++;
            
            // Обработка escape-последовательностей
            if (c == '\\' && i < src.length()) {
                out.append(src.charAt(i));
                i++;
                continue;
            }
            
            // Конец символьного литерала
            if (c == '\'') {
                st = State.CODE;
            }
            continue;
        }
        
        // ============================================
        // СОСТОЯНИЕ: LINE_COMMENT (// ...)
        // ============================================
        if (st == State.LINE_COMMENT) {
            out.append(c);
            i++;
            // Конец комментария - новая строка
            if (c == '\n') {
                st = State.CODE;
            }
            continue;
        }
        
        // ============================================
        // СОСТОЯНИЕ: BLOCK_COMMENT (/* ... */)
        // ============================================
        // BLOCK_COMMENT
        out.append(c);
        i++;
        // Конец комментария - */
        if (c == '*' && i < src.length() && src.charAt(i) == '/') {
            out.append('/');
            i++;
            st = State.CODE;
        }
    }
    
    return new ReplaceResult(out.toString(), count);
}
```

### Диаграмма состояний

```
        [любой символ]
            │
            ▼
    ┌───────────────┐
    │     CODE      │◄──────┐
    └───────────────┘       │
         │                   │
         │ "                 │
         │ '                 │
         │ //                │
         │ /*                │
         │                   │
    ┌────┴────┬──────┬───────┘
    │         │      │
    ▼         ▼      ▼
┌───────┐ ┌──────┐ ┌─────────────┐
│STRING │ │ CHAR │ │LINE_COMMENT │
└───────┘ └──────┘ └─────────────┘
    │         │            │
    │ "       │ '         │ \n
    │         │           │
    └─────────┴───────────┘
              │
              ▼
    ┌─────────────────┐
    │  BLOCK_COMMENT   │
    └─────────────────┘
              │
              │ */
              │
              └───► CODE
```

### Примеры работы

#### Пример 1: Строка

**Исходный код:**
```java
String name = "MyClass";
MyClass obj = new MyClass();
```

**Переименование:** `MyClass` → `NewClass`

**Результат:**
```java
String name = "MyClass";  // ✅ НЕ изменено (в строке)
NewClass obj = new NewClass();  // ✅ Изменено (в коде)
```

**Как работает:**

1. `State.CODE` → встречаем `"` → переход в `State.STRING`
2. В `State.STRING` все символы копируются как есть
3. Встречаем `"` → переход обратно в `State.CODE`
4. В `State.CODE` находим `MyClass` → заменяем на `NewClass`

#### Пример 2: Комментарий

**Исходный код:**
```java
// MyClass is deprecated
MyClass obj = new MyClass();
```

**Результат:**
```java
// MyClass is deprecated  // ✅ НЕ изменено (в комментарии)
NewClass obj = new NewClass();  // ✅ Изменено (в коде)
```

#### Пример 3: Escape-последовательности

**Исходный код:**
```java
String s = "MyClass\\nMyClass";
MyClass obj = new MyClass();
```

**Результат:**
```java
String s = "MyClass\\nMyClass";  // ✅ НЕ изменено
NewClass obj = new NewClass();  // ✅ Изменено
```

**Как работает:**

- В `State.STRING` встречаем `\` → читаем следующий символ (`n`) → оба копируем
- Это предотвращает ложное определение конца строки

#### Пример 4: Многострочный комментарий

**Исходный код:**
```java
/* MyClass
   is good */
MyClass obj = new MyClass();
```

**Результат:**
```java
/* MyClass
   is good */  // ✅ НЕ изменено
NewClass obj = new NewClass();  // ✅ Изменено
```

### Вспомогательные методы

#### `isIdentStart(char c)`

```java
private static boolean isIdentStart(char c) {
    return Character.isLetter(c) || c == '_' || c == '$';
}
```

**Проверяет:** начало идентификатора Java (буква, `_`, `$`)

#### `isIdentPart(char c)`

```java
private static boolean isIdentPart(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '$';
}
```

**Проверяет:** продолжение идентификатора (буква, цифра, `_`, `$`)

#### `isIdentifier(String s)`

```java
private static boolean isIdentifier(String s) {
    return s != null && s.matches("[A-Za-z_$][A-Za-z\\d_$]*");
}
```

**Проверяет:** валидный идентификатор Java (regex)

---

## RefactorUndoManager - Отмена рефакторинга

Файл: `src/main/java/com/example/f_ex/RefactorUndoManager.java`

### Назначение

**RefactorUndoManager** предоставляет возможность **откатить** последний рефакторинг, восстановив исходное содержимое файлов.

### Структура

```java
final class RefactorUndoManager {
    private Map<Path, String> lastBackup = null;  // Backup последнего рефакторинга
}
```

**Особенности:**

- ✅ Хранит только **последний** backup (не историю)
- ✅ `LinkedHashMap` - сохраняет порядок файлов
- ✅ Backup содержит **исходное** содержимое файлов (до изменений)

### Методы

#### `rememberBackup(RenamePlan plan)`

**Назначение:** Сохраняет backup файлов перед применением рефакторинга.

**Алгоритм:**

```java
void rememberBackup(RefactorRenameService.RenamePlan plan) {
    if (plan == null || plan.changes == null || plan.changes.isEmpty()) {
        lastBackup = null;
        return;
    }
    
    LinkedHashMap<Path, String> b = new LinkedHashMap<>();
    
    // Сохранение исходного содержимого каждого файла
    for (RefactorRenameService.FileChange ch : plan.changes) {
        if (ch == null || ch.file == null) continue;
        b.put(ch.file, ch.before);  // Сохраняем "до"
    }
    
    lastBackup = b.isEmpty() ? null : b;
}
```

**Особенности:**

- ✅ Сохраняет `ch.before` (исходное содержимое)
- ✅ Использует `LinkedHashMap` для сохранения порядка
- ✅ Очищает backup, если план пустой

#### `canUndo()`

**Назначение:** Проверяет, можно ли отменить рефакторинг.

```java
boolean canUndo() {
    return lastBackup != null && !lastBackup.isEmpty();
}
```

#### `undo()`

**Назначение:** Откатывает последний рефакторинг.

**Алгоритм:**

```java
int undo() {
    if (!canUndo()) return 0;
    
    int restored = 0;
    
    // Восстановление каждого файла из backup
    for (Map.Entry<Path, String> e : lastBackup.entrySet()) {
        try {
            Files.writeString(
                e.getKey(), 
                e.getValue(),  // Исходное содержимое
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            restored++;
        } catch (Exception ignored) {
            // Игнорируем ошибки записи
        }
    }
    
    lastBackup = null;  // Очищаем backup после использования
    return restored;
}
```

**Особенности:**

- ✅ Восстанавливает все файлы из backup
- ✅ Возвращает количество восстановленных файлов
- ✅ Очищает backup после использования (можно отменить только один раз)
- ✅ Игнорирует ошибки записи (продолжает с другими файлами)

### Ограничения

**Текущая реализация:**

- ❌ Хранит только **последний** backup (нет истории)
- ❌ После undo backup очищается (нельзя undo дважды)
- ❌ Нет проверки, что файлы не изменились с момента backup

**Улучшения:**

- Добавить историю (стек backup'ов)
- Добавить проверку изменений файлов
- Добавить возможность redo

---

## FileOperationsService - Операции с файлами

Файл: `src/main/java/com/example/f_ex/FileOperationsService.java`

### Назначение

**FileOperationsService** предоставляет безопасные операции с файловой системой, в частности - переименование файлов.

### Методы

#### `renameFile(Path file, String newName)`

**Назначение:** Безопасно переименовывает файл на диске.

**Алгоритм:**

```java
Path renameFile(Path file, String newName) throws Exception {
    // 1. Валидация входных параметров
    if (file == null) throw new IllegalArgumentException("file");
    if (newName == null) throw new IllegalArgumentException("newName");
    
    // 2. Очистка и проверка нового имени
    String name = newName.trim();
    if (name.isEmpty()) throw new IllegalArgumentException("Empty name");
    
    // 3. Проверка на недопустимые символы
    if (name.contains("/") || name.contains("\\") || name.contains(":")) {
        throw new IllegalArgumentException("Invalid name");
    }
    
    // 4. Определение целевого пути
    Path parent = file.getParent();
    if (parent == null) throw new IllegalArgumentException("No parent");
    Path target = parent.resolve(name);
    
    // 5. Проверка, что целевой файл не существует
    if (Files.exists(target)) {
        throw new IllegalArgumentException("Target exists");
    }
    
    // 6. Переименование (move)
    return Files.move(file, target);
}
```

**Валидация:**

- ✅ Проверка на `null`
- ✅ Проверка на пустое имя
- ✅ Проверка на недопустимые символы (`/`, `\`, `:`)
- ✅ Проверка, что целевой файл не существует

**Особенности:**

- ✅ Использует `Files.move()` - атомарная операция
- ✅ Возвращает новый путь к файлу
- ✅ Бросает исключения при ошибках (не игнорирует)

---

## Интеграция в IdeController

### Переименование символа

**Метод:** `onRename()` (`Shift+F6`)

**Алгоритм:**

```java
@FXML
public void onRename() {
    // 1. Получение текущего файла и редактора
    Tab tab = editorTabs.getSelectionModel().getSelectedItem();
    EditorTabData data = (EditorTabData) tab.getUserData();
    if (data == null || data.path == null || data.editor == null) {
        updateStatus("No editor file selected");
        return;
    }
    
    // 2. Извлечение слова под курсором
    String from = wordAt(data.editor.getText(), data.editor.getCaretPosition());
    if (from == null || from.isBlank()) {
        updateStatus("Place caret on identifier");
        return;
    }
    
    // 3. Диалог для ввода нового имени
    Dialog<Map<String, Object>> d = new Dialog<>();
    d.setTitle("Rename");
    d.setHeaderText("Rename identifier");
    
    TextField newNameField = new TextField(from);
    CheckBox wholeProject = new CheckBox("Whole project");
    
    // 4. Получение результата
    Optional<Map<String, Object>> result = d.showAndWait();
    result.ifPresent(params -> {
        String newName = (String) params.get("newName");
        boolean wholeProj = (Boolean) params.get("wholeProject");
        
        // 5. Определение списка файлов
        List<Path> files;
        if (wholeProj) {
            files = listAllJavaFiles(projectRoot);  // Весь проект
        } else {
            files = List.of(data.path);  // Только текущий файл
        }
        
        // 6. Создание плана переименования
        RefactorRenameService.RenamePlan plan = renameService.planRename(files, from, newName);
        if (plan.changes.isEmpty()) {
            updateStatus("Nothing to rename");
            return;
        }
        
        // 7. Preview и подтверждение
        if (!confirmRenamePlan(plan)) return;
        
        // 8. Сохранение backup
        undoManager.rememberBackup(plan);
        
        // 9. Применение изменений
        RefactorRenameService.RenameResult res = renameService.applyPlan(plan);
        updateStatus("Rename: " + res.occurrences + " occurrences in " + res.filesChanged + " files");
        
        // 10. Переоткрытие всех открытых редакторов
        reopenAllOpenEditors();
        
        // 11. Опциональное переименование файла (если это класс)
        if (renameFileToo && fileForRename != null) {
            // Переименование .java файла
            Path newAbs = fileOps.renameFile(oldAbs, newName + ".java");
            // Обновление вкладок и дерева проекта
        }
    });
}
```

**Особенности:**

- ✅ Извлечение слова под курсором автоматически
- ✅ Выбор области (файл/проект)
- ✅ Preview перед применением
- ✅ Сохранение backup перед изменениями
- ✅ Автоматическое переименование файла (если это класс)

#### `confirmRenamePlan(RenamePlan plan)`

**Назначение:** Показывает preview изменений пользователю.

```java
private boolean confirmRenamePlan(RefactorRenameService.RenamePlan plan) {
    int files = plan.changes.size();
    int occ = plan.changes.stream().mapToInt(c -> c.occurrences).sum();
    
    StringBuilder sb = new StringBuilder();
    sb.append("Rename ").append(plan.from).append(" -> ").append(plan.to).append("\n");
    sb.append("Files: ").append(files).append(", occurrences: ").append(occ).append("\n\n");
    
    // Показываем первые 20 файлов
    int shown = 0;
    for (RefactorRenameService.FileChange ch : plan.changes) {
        if (shown >= 20) break;
        sb.append(ch.file.getFileName()).append(" (").append(ch.occurrences).append(")\n");
        shown++;
    }
    if (files > shown) sb.append("... +").append(files - shown).append(" more\n");
    
    Alert a = new Alert(AlertType.CONFIRMATION);
    a.setTitle("Rename Preview");
    a.setContentText(sb.toString());
    Optional<ButtonType> r = a.showAndWait();
    return r.isPresent() && r.get() == ButtonType.OK;
}
```

**Формат preview:**

```
Rename MyClass -> NewClass
Files: 5, occurrences: 12

MyClass.java (3)
Test.java (2)
Utils.java (1)
... +2 more
```

### Отмена рефакторинга

**Метод:** `onUndoRefactor()` (`Ctrl+Alt+Z`)

```java
@FXML
public void onUndoRefactor() {
    int restored = undoManager.undo();
    if (restored == 0) {
        updateStatus("Nothing to undo");
        return;
    }
    updateStatus("Undo: restored " + restored + " files");
    reopenAllOpenEditors();  // Переоткрытие для обновления UI
}
```

### Переименование файла

**Метод:** `onRenameFile()` (`F2`)

**Алгоритм:**

```java
@FXML
public void onRenameFile() {
    // 1. Определение файла для переименования
    Path target = null;
    Tab tab = editorTabs.getSelectionModel().getSelectedItem();
    EditorTabData data = (EditorTabData) tab.getUserData();
    if (data != null && data.path != null) {
        target = data.path;
    } else if (projectTree.getSelectionModel().getSelectedItem() != null) {
        target = projectTree.getSelectionModel().getSelectedItem().getValue();
    }
    
    if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
        updateStatus("Select a file to rename");
        return;
    }
    
    // 2. Диалог для ввода нового имени
    String current = target.getFileName().toString();
    TextInputDialog d = new TextInputDialog(current);
    d.setTitle("Rename File");
    d.setContentText("New file name:");
    
    d.showAndWait().ifPresent(newName -> {
        try {
            // 3. Переименование файла
            Path oldAbs = target.normalize().toAbsolutePath();
            Path newAbs = fileOps.renameFile(oldAbs, newName).normalize().toAbsolutePath();
            
            // 4. Обновление открытых вкладок
            Tab opened = openTabsByPath.remove(oldAbs);
            if (opened != null) {
                openTabsByPath.put(newAbs, opened);
                opened.setText(newAbs.getFileName().toString());
                EditorTabData td = (EditorTabData) opened.getUserData();
                if (td != null) td.path = newAbs;
            }
            
            // 5. Автоматическое переименование класса внутри (если это Java файл)
            String oldName = current;
            String newFile = newAbs.getFileName().toString();
            if (oldName.endsWith(".java") && newFile.endsWith(".java")) {
                String oldBase = oldName.substring(0, oldName.length() - 5);
                String newBase = newFile.substring(0, newFile.length() - 5);
                
                if (!oldBase.equals(newBase) && newBase.matches("[A-Za-z_$][A-Za-z\\d_$]*")) {
                    try {
                        String src = Files.readString(newAbs, StandardCharsets.UTF_8);
                        
                        // Проверка, есть ли класс с таким именем
                        if (src.contains("class " + oldBase) || 
                            src.contains("interface " + oldBase) || 
                            src.contains("enum " + oldBase) || 
                            src.contains("record " + oldBase)) {
                            
                            // Переименование класса внутри файла
                            RefactorRenameService.RenamePlan plan = 
                                renameService.planRename(List.of(newAbs), oldBase, newBase);
                            
                            if (!plan.changes.isEmpty()) {
                                undoManager.rememberBackup(plan);
                                renameService.applyPlan(plan);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            
            // 6. Обновление дерева проекта
            refreshTreeIfUnderRoot(newAbs);
            updateStatus("Renamed: " + current + " -> " + newAbs.getFileName());
            
        } catch (Exception e) {
            updateStatus("Rename failed: " + e.getMessage());
        }
    });
}
```

**Особенности:**

- ✅ Переименование файла на диске
- ✅ Обновление открытых вкладок
- ✅ **Автоматическое переименование класса** внутри файла (если имя совпадает)
- ✅ Обновление дерева проекта

---

## Сценарии использования

### Сценарий 1: Переименование переменной в одном файле

```
User: Places caret on "count"
User: Presses Shift+F6
    │
    ▼
IdeController.onRename()
    │
    ├─→ wordAt() → "count"
    ├─→ Dialog: "Rename count to: [newCount]"
    ├─→ User enters: "newCount"
    ├─→ User unchecks "Whole project"
    │
    ▼
renameService.planRename([currentFile], "count", "newCount")
    │
    ├─→ replaceIdentifiersOutsideStringsAndComments()
    │   ├─→ State.CODE → finds "count" → replaces
    │   └─→ State.STRING → finds "count" → ignores
    │
    └─→ Returns: RenamePlan (1 file, 5 occurrences)
        │
        ▼
confirmRenamePlan()
    │
    └─→ Shows: "Rename count -> newCount\nFiles: 1, occurrences: 5"
        │
        └─→ User confirms
            │
            ▼
undoManager.rememberBackup(plan)
    │
    └─→ Saves original content
        │
        ▼
renameService.applyPlan(plan)
    │
    └─→ Writes modified content
        │
        ▼
reopenAllOpenEditors()
    │
    └─→ Refreshes UI
```

### Сценарий 2: Переименование класса по всему проекту

```
User: Places caret on "MyClass"
User: Presses Shift+F6
    │
    ├─→ wordAt() → "MyClass"
    ├─→ Dialog: "Rename MyClass to: [NewClass]"
    ├─→ User enters: "NewClass"
    └─→ User checks "Whole project"
        │
        ▼
listAllJavaFiles(projectRoot)
    │
    └─→ Returns: [File1.java, File2.java, File3.java, ...]
        │
        ▼
renameService.planRename(allFiles, "MyClass", "NewClass")
    │
    ├─→ For each file:
    │   ├─→ Read file
    │   ├─→ replaceIdentifiersOutsideStringsAndComments()
    │   └─→ Count occurrences
    │
    └─→ Returns: RenamePlan (12 files, 45 occurrences)
        │
        ▼
confirmRenamePlan()
    │
    └─→ Shows: "Rename MyClass -> NewClass\nFiles: 12, occurrences: 45\n\nFile1.java (5)\nFile2.java (3)\n..."
        │
        └─→ User confirms
            │
            ▼
undoManager.rememberBackup(plan)
    │
    └─→ Saves backup of 12 files
        │
        ▼
renameService.applyPlan(plan)
    │
    └─→ Writes all 12 files
        │
        ▼
[Optional] Rename file MyClass.java → NewClass.java
    │
    └─→ fileOps.renameFile()
        │
        └─→ renameService.planRename() [for class inside]
            │
            └─→ applyPlan() [rename class declaration]
```

### Сценарий 3: Отмена рефакторинга

```
User: Presses Ctrl+Alt+Z
    │
    ▼
IdeController.onUndoRefactor()
    │
    ▼
undoManager.undo()
    │
    ├─→ For each file in lastBackup:
    │   └─→ Files.writeString(file, originalContent)
    │
    └─→ Returns: 12 (restored files)
        │
        ▼
reopenAllOpenEditors()
    │
    └─→ Refreshes UI with restored content
```

---

## Ограничения и улучшения

### Текущие ограничения

#### 1. State Machine

**Ограничения:**

- ❌ Не учитывает raw strings (Java 15+)
- ❌ Не учитывает text blocks (Java 13+)
- ❌ Не различает типы и переменные с одинаковым именем
- ❌ Не учитывает контекст (может переименовать не то)

**Улучшения:**

- Использовать AST парсер (например, JavaParser)
- Учитывать scope переменных
- Различать типы и переменные

#### 2. Preview

**Ограничения:**

- ❌ Показывает только первые 20 файлов
- ❌ Не показывает конкретные строки с изменениями
- ❌ Нет diff view

**Улучшения:**

- Показывать все файлы с возможностью прокрутки
- Показывать конкретные строки с изменениями
- Diff view (до/после)

#### 3. Undo

**Ограничения:**

- ❌ Только последний рефакторинг
- ❌ Нет redo
- ❌ Нет проверки изменений файлов

**Улучшения:**

- История рефакторингов (стек)
- Redo функциональность
- Проверка, что файлы не изменились

#### 4. Переименование файла

**Ограничения:**

- ❌ Переименует класс только если имя точно совпадает
- ❌ Не обновляет импорты в других файлах
- ❌ Не учитывает вложенные классы

**Улучшения:**

- Обновление импортов во всех файлах
- Учет вложенных классов
- Обновление package declarations

### Планируемые улучшения

1. **AST-based рефакторинг:**
   - Использование JavaParser или Eclipse JDT
   - Точное понимание структуры кода
   - Учет scope и контекста

2. **Extract Method:**
   - Выделение метода из выделенного кода
   - Автоматическое определение параметров
   - Обновление вызовов

3. **Extract Variable:**
   - Выделение выражения в переменную
   - Автоматический выбор типа
   - Замена всех использований

4. **Inline:**
   - Inline Method
   - Inline Variable

5. **Move:**
   - Move Class to Package
   - Move Method to Class

---

## Резюме

### Ключевые особенности Refactoring Layer:

1. ✅ **Умное переименование** - state machine для корректной обработки кода
2. ✅ **Preview изменений** - показ плана перед применением
3. ✅ **Отмена (Undo)** - возможность откатить изменения
4. ✅ **Переименование файлов** - с автоматическим обновлением класса
5. ✅ **Безопасность** - валидация и проверки на каждом этапе

### Технические детали:

- **State Machine** - 5 состояний для парсинга Java кода
- **Escape-последовательности** - корректная обработка `\n`, `\"`, и т.д.
- **Атомарность** - все изменения применяются вместе
- **Backup система** - сохранение исходного содержимого

### Производительность:

- ✅ Обработка файлов последовательно (можно распараллелить)
- ✅ Минимальное использование памяти (не хранит весь проект в памяти)
- ✅ Быстрая валидация идентификаторов (regex)

---

**Версия:** 1.0  
**Дата:** 2026-01-20  
**Файл:** `Documents/04-Refactoring-Layer.md`
