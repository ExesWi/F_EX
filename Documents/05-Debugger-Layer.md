# Детальная документация: Debugger Layer

## 📋 Содержание

1. [Обзор слоя](#обзор-слоя)
2. [JDWP и jdb - Технологии отладки](#jdwp-и-jdb---технологии-отладки)
3. [DebugSession - Управление сессией отладки](#debugsession---управление-сессией-отладки)
4. [DebugParsers - Парсинг вывода jdb](#debugparsers---парсинг-вывода-jdb)
5. [Интеграция в IdeController](#интеграция-в-idecontroller)
6. [Breakpoints - Точки останова](#breakpoints---точки-останова)
7. [Запуск проекта в режиме отладки](#запуск-проекта-в-режиме-отладки)
8. [Сценарии использования](#сценарии-использования)
9. [Ограничения и улучшения](#ограничения-и-улучшения)

---

## Обзор слоя

**Debugger Layer** предоставляет **полнофункциональную отладку** Java приложений через интеграцию с **JDWP** (Java Debug Wire Protocol) и **jdb** (Java Debugger).

### Возможности:

- ✅ **Breakpoints** - точки останова в коде
- ✅ **Step Over/Into/Out** - пошаговое выполнение
- ✅ **Continue/Pause** - управление выполнением
- ✅ **Threads** - просмотр потоков выполнения
- ✅ **Stack Frames** - просмотр стека вызовов
- ✅ **Variables** - просмотр локальных переменных
- ✅ **Visual Breakpoints** - визуальные точки останова в редакторе

### Компоненты слоя:

```
Debugger Layer
├── DebugSession.java          # Управление jdb процессом
├── DebugParsers.java          # Парсинг вывода jdb
├── JavaProjectRunner.java     # Запуск с JDWP (Plain Java)
└── IntelliJProjectRunner.java # Запуск с JDWP (IntelliJ)
```

### Архитектура:

```
┌─────────────────┐
│  IdeController  │
│  (UI Controls)  │
└────────┬────────┘
         │
         ├─→ DebugSession ──→ jdb ──→ JDWP ──→ Java Process
         │
         ├─→ DebugParsers ──→ Parse jdb output
         │
         └─→ Breakpoints ──→ Visual dots in editor
```

---

## JDWP и jdb - Технологии отладки

### JDWP (Java Debug Wire Protocol)

**JDWP** - протокол для отладки Java приложений. Позволяет отладчику подключаться к запущенному Java процессу.

#### Параметры JDWP:

```bash
-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005
```

**Параметры:**

- `transport=dt_socket` - использование TCP/IP сокета
- `server=y` - процесс выступает как сервер (слушает подключения)
- `suspend=y` - приостановить выполнение до подключения отладчика
- `address=5005` - порт для подключения

#### Режимы работы:

1. **Server Mode** (`server=y`):
   - Java процесс слушает на порту 5005
   - Отладчик подключается к процессу
   - Используется в F_EX IDE

2. **Client Mode** (`server=n`):
   - Отладчик слушает на порту
   - Java процесс подключается к отладчику
   - Не используется в F_EX IDE

### jdb (Java Debugger)

**jdb** - консольный отладчик, входящий в JDK. Используется для взаимодействия с JDWP.

#### Основные команды jdb:

| Команда | Описание |
|---------|----------|
| `threads` | Список всех потоков |
| `thread <id>` | Выбрать поток |
| `where` | Показать стек вызовов |
| `frame <n>` | Выбрать фрейм стека |
| `locals` | Показать локальные переменные |
| `stop at <class>:<line>` | Установить breakpoint |
| `stop in <class>.<method>` | Установить breakpoint в методе |
| `cont` | Продолжить выполнение |
| `next` | Step Over |
| `step` | Step Into |
| `step up` | Step Out |
| `suspend` | Приостановить выполнение |
| `exit` | Выйти из отладчика |

---

## DebugSession - Управление сессией отладки

Файл: `src/main/java/com/example/f_ex/DebugSession.java`

### Назначение

**DebugSession** управляет процессом `jdb`, отправляет команды и обрабатывает вывод.

### Структура класса

```java
final class DebugSession {
    private final Consumer<String> log;      // Логирование
    private Process proc;                    // jdb процесс
    private OutputStream in;                  // Входной поток jdb
    private final StringBuilder buffer;      // Буфер вывода
    private final Queue<Pending> pending;    // Ожидающие запросы
    private volatile boolean ready;          // Готовность сессии
}
```

### Методы

#### `connectSocket(String host, int port)`

**Назначение:** Подключается к Java процессу через JDWP.

**Алгоритм:**

```java
void connectSocket(String host, int port) {
    if (proc != null && proc.isAlive()) return;
    
    Thread t = new Thread(() -> {
        try {
            // 1. Формирование команды jdb
            List<String> cmd = new ArrayList<>();
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) cmd.addAll(List.of("cmd.exe", "/c", "jdb"));
            else cmd.add("jdb");
            
            // 2. Параметры подключения
            cmd.add("-connect");
            cmd.add("com.sun.jdi.SocketAttach:hostname=" + host + ",port=" + port);
            
            // 3. Запуск процесса
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
            in = proc.getOutputStream();
            ready = true;
            
            Platform.runLater(() -> log.accept("[jdb] connected to " + host + ":" + port));
            
            // 4. Чтение вывода jdb
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    onLine(line);  // Обработка каждой строки
                }
            }
        } catch (Exception e) {
            Platform.runLater(() -> log.accept("[jdb] failed: " + e.getMessage()));
        } finally {
            ready = false;
        }
    }, "jdb-session");
    t.setDaemon(true);
    t.start();
}
```

**Особенности:**

- ✅ Запускается в отдельном потоке (не блокирует UI)
- ✅ Использует UTF-8 для корректной обработки вывода
- ✅ Обрабатывает вывод построчно через `onLine()`
- ✅ Устанавливает `ready = true` при успешном подключении

#### `send(String cmd)`

**Назначение:** Отправляет команду в jdb.

**Алгоритм:**

```java
void send(String cmd) {
    if (!isReady()) {
        Platform.runLater(() -> log.accept("[jdb] not connected"));
        return;
    }
    try {
        in.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
    } catch (Exception e) {
        Platform.runLater(() -> log.accept("[jdb] send failed: " + e.getMessage()));
    }
}
```

**Особенности:**

- ✅ Проверяет готовность сессии
- ✅ Добавляет `\n` для завершения команды
- ✅ Использует UTF-8 кодировку

#### `request(String cmd, Handler handler)`

**Назначение:** Отправляет команду и ожидает ответ.

**Алгоритм:**

```java
void request(String cmd, Handler handler) {
    if (!isReady()) return;
    pending.add(new Pending(handler));  // Добавляем в очередь ожидания
    send(cmd);  // Отправляем команду
}
```

**Как работает:**

1. Команда добавляется в очередь `pending`
2. Команда отправляется в jdb
3. Когда jdb выводит prompt (например, `[1]`), вызывается `flushOne()`
4. `flushOne()` извлекает первый `Pending` и вызывает его `handler.onOutput()`

#### `onLine(String line)`

**Назначение:** Обрабатывает каждую строку вывода jdb.

**Алгоритм:**

```java
private void onLine(String line) {
    Platform.runLater(() -> log.accept(line));  // Логирование
    buffer.append(line).append('\n');  // Добавление в буфер
    
    // Если строка похожа на prompt - обрабатываем ответ
    if (looksLikePrompt(line)) {
        flushOne();  // Обработка одного ожидающего запроса
    }
}
```

**Особенности:**

- ✅ Все обновления UI через `Platform.runLater()`
- ✅ Накопление вывода в буфере
- ✅ Определение prompt для обработки ответов

#### `flushOne()`

**Назначение:** Обрабатывает один ожидающий запрос.

**Алгоритм:**

```java
private void flushOne() {
    if (pending.isEmpty()) {
        buffer.setLength(0);  // Очищаем буфер, если нет ожидающих
        return;
    }
    
    Pending p = pending.poll();  // Извлекаем первый запрос
    if (p == null) return;
    
    String all = buffer.toString();
    buffer.setLength(0);  // Очищаем буфер
    
    // Парсинг строк
    List<String> lines = new ArrayList<>();
    for (String l : all.split("\\R")) {
        String t = l.trim();
        if (t.isEmpty()) continue;
        if (looksLikePrompt(t)) continue;  // Пропускаем prompt
        lines.add(l);
    }
    
    // Вызов обработчика
    Platform.runLater(() -> p.handler.onOutput(lines));
}
```

**Особенности:**

- ✅ Извлекает первый ожидающий запрос (FIFO)
- ✅ Фильтрует пустые строки и prompt
- ✅ Вызывает обработчик с результатом

#### `looksLikePrompt(String line)`

**Назначение:** Определяет, является ли строка prompt jdb.

```java
private static boolean looksLikePrompt(String line) {
    String t = line.trim();
    return t.matches(".*\\[\\d+\\].*") ||  // [1], [2], etc.
           t.endsWith(">") ||               // >
           (t.endsWith("]") && t.contains("["));  // [something]
}
```

**Примеры prompt:**

- `[1]`
- `>`
- `[main]`

#### `stop()`

**Назначение:** Останавливает сессию отладки.

```java
void stop() {
    try { send("exit"); } catch (Exception ignored) {}
    try { if (proc != null) proc.destroyForcibly(); } catch (Exception ignored) {}
    ready = false;
}
```

**Особенности:**

- ✅ Отправляет `exit` в jdb
- ✅ Принудительно завершает процесс
- ✅ Устанавливает `ready = false`

### Интерфейс Handler

```java
interface Handler {
    void onOutput(List<String> lines);
}
```

**Назначение:** Обработчик ответа от jdb.

**Использование:**

```java
debugSession.request("threads", lines -> {
    // lines содержит вывод команды "threads"
    List<ThreadItem> threads = DebugParsers.parseThreads(lines);
    // Обновление UI
});
```

---

## DebugParsers - Парсинг вывода jdb

Файл: `src/main/java/com/example/f_ex/DebugParsers.java`

### Назначение

**DebugParsers** парсит текстовый вывод jdb в структурированные данные.

### Внутренние классы

#### ThreadItem

**Назначение:** Представляет поток выполнения.

```java
static final class ThreadItem {
    final String id;      // ID потока (например, "0x1")
    final String name;     // Имя потока (например, "main")
    final String raw;      // Полная строка вывода
    
    @Override
    public String toString() { return raw; }
}
```

**Пример вывода jdb:**

```
(java.lang.Thread)0x1 main running
(java.lang.Thread)0x2 Thread-1 waiting
```

#### FrameItem

**Назначение:** Представляет фрейм стека вызовов.

```java
static final class FrameItem {
    final int index;       // Индекс фрейма (0, 1, 2, ...)
    final String raw;      // Полная строка вывода
    
    @Override
    public String toString() { return raw; }
}
```

**Пример вывода jdb:**

```
[1] com.example.Main.main (Main.java:10)
[2] java.lang.Thread.run (Thread.java:840)
```

#### VarItem

**Назначение:** Представляет локальную переменную.

```java
static final class VarItem {
    final String name;     // Имя переменной
    final String value;    // Значение переменной
    
    @Override
    public String toString() { return name + " = " + value; }
}
```

**Пример вывода jdb:**

```
i = 10
name = "Hello"
count = 5
```

### Методы парсинга

#### `parseThreads(List<String> lines)`

**Назначение:** Парсит вывод команды `threads`.

**Алгоритм:**

```java
static List<ThreadItem> parseThreads(List<String> lines) {
    List<ThreadItem> out = new ArrayList<>();
    // Паттерн: (java.lang.Thread)0x1 main running
    Pattern p = Pattern.compile("^\\(java\\.lang\\.Thread\\)(\\S+)\\s+(.*)$");
    
    for (String l : lines) {
        String t = l.trim();
        Matcher m = p.matcher(t);
        if (m.find()) {
            String id = m.group(1);        // "0x1"
            String rest = m.group(2);       // "main running"
            String name = rest.split("\\s+")[0];  // "main"
            out.add(new ThreadItem(id, name, rest));
        }
    }
    return out;
}
```

**Regex паттерн:**

```
^\(java\.lang\.Thread\)(\S+)\s+(.*)$
```

- `\(java\.lang\.Thread\)` - префикс
- `(\S+)` - ID потока (группа 1)
- `\s+` - пробелы
- `(.*)` - остальное (группа 2)

**Пример:**

```
Вход: "(java.lang.Thread)0x1 main running"
Выход: ThreadItem(id="0x1", name="main", raw="main running")
```

#### `parseWhere(List<String> lines)`

**Назначение:** Парсит вывод команды `where` (стек вызовов).

**Алгоритм:**

```java
static List<FrameItem> parseWhere(List<String> lines) {
    List<FrameItem> out = new ArrayList<>();
    // Паттерн: [1] com.example.Main.main (Main.java:10)
    Pattern p = Pattern.compile("^\\[(\\d+)\\]\\s+(.*)$");
    
    for (String l : lines) {
        Matcher m = p.matcher(l.trim());
        if (m.find()) {
            int index = Integer.parseInt(m.group(1));  // "1" -> 1
            String rest = m.group(2);                   // "com.example.Main.main (Main.java:10)"
            out.add(new FrameItem(index, rest));
        }
    }
    return out;
}
```

**Regex паттерн:**

```
^\[(\d+)\]\s+(.*)$
```

- `\[` - открывающая скобка
- `(\d+)` - индекс фрейма (группа 1)
- `\]` - закрывающая скобка
- `\s+` - пробелы
- `(.*)` - остальное (группа 2)

**Пример:**

```
Вход: "[1] com.example.Main.main (Main.java:10)"
Выход: FrameItem(index=1, raw="com.example.Main.main (Main.java:10)")
```

#### `parseLocals(List<String> lines)`

**Назначение:** Парсит вывод команды `locals` (локальные переменные).

**Алгоритм:**

```java
static List<VarItem> parseLocals(List<String> lines) {
    List<VarItem> out = new ArrayList<>();
    // Паттерн: i = 10
    Pattern p = Pattern.compile("^([A-Za-z_$][A-Za-z\\d_$]*)\\s*=\\s*(.*)$");
    
    for (String l : lines) {
        Matcher m = p.matcher(l.trim());
        if (m.find()) {
            String name = m.group(1);   // "i"
            String value = m.group(2);   // "10"
            out.add(new VarItem(name, value));
        }
    }
    return out;
}
```

**Regex паттерн:**

```
^([A-Za-z_$][A-Za-z\d_$]*)\s*=\s*(.*)$
```

- `([A-Za-z_$][A-Za-z\d_$]*)` - имя переменной (группа 1)
- `\s*=\s*` - знак равенства с пробелами
- `(.*)` - значение (группа 2)

**Пример:**

```
Вход: "i = 10"
Выход: VarItem(name="i", value="10")
```

---

## Интеграция в IdeController

### Поля класса

```java
private final Map<Path, Set<Integer>> breakpoints = new ConcurrentHashMap<>();
private final DebugSession debugSession = new DebugSession(this::appendDebugLine);
```

**Назначение:**

- `breakpoints` - хранит точки останова (файл → строки)
- `debugSession` - сессия отладки

### Методы управления отладкой

#### `onDebugProject()`

**Назначение:** Запускает проект в режиме отладки.

**Алгоритм:**

```java
@FXML
public void onDebugProject() {
    // 1. Получение выбранного target
    RunTarget selected = runTargetComboBox.getValue();
    if (selected == null) {
        refreshRunTargets();
        selected = runTargetComboBox.getValue();
    }
    if (selected == null) {
        updateStatus("No run target selected");
        return;
    }
    
    // 2. Логирование
    logToConsole("[DEBUG] Starting (JDWP): " + selected.getDisplayName());
    
    // 3. Запуск проекта с JDWP
    debugSelectedTarget(selected);
    
    // 4. Переключение на вкладку Debug
    Platform.runLater(() -> {
        if (bottomTabs != null) bottomTabs.getSelectionModel().select(3);
    });
    
    // 5. Подключение jdb
    startJdbAttach();
}
```

#### `startJdbAttach()`

**Назначение:** Подключается к Java процессу через jdb.

**Алгоритм:**

```java
private void startJdbAttach() {
    // 1. Подключение к localhost:5005
    debugSession.connectSocket("localhost", 5005);
    
    // 2. Приостановка выполнения
    debugSession.send("suspend");
    
    // 3. Применение breakpoints
    applyBreakpointsToJdb();
    
    // 4. Установка breakpoint в main (если есть)
    RunTarget selected = runTargetComboBox.getValue();
    if (selected != null && selected.getPath() != null) {
        String cls = inferClassName(selected.getPath());
        if (cls != null && !cls.isBlank()) {
            debugSession.send("stop in " + cls + ".main");
        }
    }
    
    // 5. Обновление UI
    refreshDebugAll();
}
```

#### Команды управления

```java
@FXML public void onDebugContinue() { 
    sendJdb("cont"); 
    scheduleDebugRefresh(); 
}

@FXML public void onDebugStepOver() { 
    sendJdb("next"); 
    scheduleDebugRefresh(); 
}

@FXML public void onDebugStepInto() { 
    sendJdb("step"); 
    scheduleDebugRefresh(); 
}

@FXML public void onDebugStepOut() { 
    sendJdb("step up"); 
    scheduleDebugRefresh(); 
}

@FXML public void onDebugPause() { 
    sendJdb("suspend"); 
    scheduleDebugRefresh(); 
}

@FXML public void onDebugStop() { 
    debugSession.stop(); 
}

@FXML public void onDebugRefresh() { 
    refreshDebugAll(); 
}
```

**Особенности:**

- ✅ Все команды отправляются через `sendJdb()`
- ✅ После выполнения команды вызывается `scheduleDebugRefresh()` (debounce 300ms)
- ✅ `onDebugStop()` останавливает сессию

#### `refreshDebugAll()`

**Назначение:** Обновляет все панели отладки.

```java
private void refreshDebugAll() {
    refreshDebugThreads();
    refreshDebugStackAndVars();
}
```

#### `refreshDebugThreads()`

**Назначение:** Обновляет список потоков.

```java
private void refreshDebugThreads() {
    if (debugThreadsList == null) return;
    debugSession.request("threads", lines -> {
        List<ThreadItem> threads = DebugParsers.parseThreads(lines);
        debugThreadsList.getItems().setAll(threads);
    });
}
```

**Особенности:**

- ✅ Использует `request()` для асинхронного получения данных
- ✅ Парсит вывод через `DebugParsers.parseThreads()`
- ✅ Обновляет UI через `Platform.runLater()` (внутри `request()`)

#### `refreshDebugStack()`

**Назначение:** Обновляет стек вызовов.

```java
private void refreshDebugStack() {
    if (debugStackList == null) return;
    debugSession.request("where", lines -> {
        List<FrameItem> frames = DebugParsers.parseWhere(lines);
        debugStackList.getItems().setAll(frames);
    });
}
```

#### `refreshDebugVars()`

**Назначение:** Обновляет локальные переменные.

```java
private void refreshDebugVars() {
    if (debugVarsList == null) return;
    debugSession.request("locals", lines -> {
        List<VarItem> vars = DebugParsers.parseLocals(lines);
        debugVarsList.getItems().setAll(vars);
    });
}
```

#### `scheduleDebugRefresh()`

**Назначение:** Debounce для обновления UI.

```java
private void scheduleDebugRefresh() {
    PauseTransition pt = new PauseTransition(Duration.millis(300));
    pt.setOnFinished(e -> refreshDebugAll());
    pt.play();
}
```

**Особенности:**

- ✅ Задержка 300ms перед обновлением
- ✅ Предотвращает частые обновления при быстрых командах

### Обработчики событий UI

#### Выбор потока

```java
if (debugThreadsList != null) {
    debugThreadsList.setOnMouseClicked(e -> {
        if (e.getClickCount() != 1) return;
        ThreadItem t = debugThreadsList.getSelectionModel().getSelectedItem();
        if (t == null) return;
        // Выбор потока и обновление стека/переменных
        debugSession.request("thread " + t.id, lines -> refreshDebugStackAndVars());
    });
}
```

#### Выбор фрейма стека

```java
if (debugStackList != null) {
    debugStackList.setOnMouseClicked(e -> {
        if (e.getClickCount() != 1) return;
        FrameItem f = debugStackList.getSelectionModel().getSelectedItem();
        if (f == null) return;
        // Выбор фрейма и обновление переменных
        debugSession.request("frame " + f.index, lines -> refreshDebugVars());
    });
}
```

---

## Breakpoints - Точки останова

### Хранение breakpoints

```java
private final Map<Path, Set<Integer>> breakpoints = new ConcurrentHashMap<>();
```

**Структура:**

- `Path` - путь к файлу
- `Set<Integer>` - номера строк (1-based)

**Пример:**

```java
breakpoints = {
    "Main.java" → {10, 25, 42},
    "Utils.java" → {5}
}
```

### Визуальные breakpoints

#### `createGutter(CodeArea editor, Path file)`

**Назначение:** Создает gutter (боковую панель) с номерами строк и breakpoints.

**Алгоритм:**

```java
private IntFunction<Node> createGutter(CodeArea editor, Path file) {
    IntFunction<Node> base = LineNumberFactory.get(editor);  // Базовые номера строк
    
    return line -> {
        // 1. Получение базового узла с номером строки
        Node lnNode = base.apply(line);
        int ln = line + 1;  // 0-based → 1-based
        
        // 2. Создание точки breakpoint
        Text dot = new Text("●");
        dot.setStyle("-fx-fill: transparent;");
        
        // 3. Проверка, установлен ли breakpoint
        if (file != null && breakpoints.getOrDefault(file, Set.of()).contains(ln)) {
            dot.setStyle("-fx-fill: #e51400;");  // Красный цвет
        }
        
        // 4. Создание контейнера
        HBox box = new HBox(6, dot, lnNode);
        
        // 5. Обработчик клика для toggle breakpoint
        box.setOnMouseClicked(e -> {
            if (file == null) return;
            toggleBreakpoint(file, ln);
            // Обновление цвета точки
            dot.setStyle(
                breakpoints.getOrDefault(file, Set.of()).contains(ln) 
                    ? "-fx-fill: #e51400;" 
                    : "-fx-fill: transparent;"
            );
        });
        
        return box;
    };
}
```

**Визуальное представление:**

```
● 1  public class Main {
  2      public static void main(String[] args) {
● 3          int x = 10;
  4          System.out.println(x);
  5      }
  6  }
```

- `●` - красная точка = breakpoint установлен
- Прозрачная точка = breakpoint не установлен

### Управление breakpoints

#### `toggleBreakpoint(Path file, int line)`

**Назначение:** Переключает breakpoint (установить/удалить).

**Алгоритм:**

```java
private void toggleBreakpoint(Path file, int line) {
    breakpoints.compute(file, (k, v) -> {
        Set<Integer> s = (v == null) ? new HashSet<>() : new HashSet<>(v);
        if (!s.add(line)) s.remove(line);  // Если уже есть - удаляем
        return s;
    });
    
    updateStatus("Breakpoint " + 
        (breakpoints.getOrDefault(file, Set.of()).contains(line) ? "set" : "removed") + 
        ": " + file.getFileName() + ":" + line);
    
    // Если jdb подключен - применяем breakpoints
    if (debugSession.isReady()) applyBreakpointsToJdb();
}
```

**Особенности:**

- ✅ Использует `compute()` для атомарного обновления
- ✅ Если breakpoint уже есть - удаляет, иначе добавляет
- ✅ Автоматически применяет breakpoints в jdb, если сессия активна

#### `applyBreakpointsToJdb()`

**Назначение:** Применяет все breakpoints к jdb.

**Алгоритм:**

```java
private void applyBreakpointsToJdb() {
    RunTarget selected = runTargetComboBox.getValue();
    if (selected == null) return;
    Path javaFile = selected.getPath();
    if (javaFile == null) return;
    
    // Определение имени класса
    String cls = inferClassName(javaFile);
    if (cls == null || cls.isBlank()) return;
    
    // Применение breakpoints
    Set<Integer> lines = breakpoints.getOrDefault(javaFile, Set.of());
    for (Integer ln : lines) {
        debugSession.send("stop at " + cls + ":" + ln);
    }
}
```

**Команда jdb:**

```
stop at com.example.Main:10
```

**Формат:**

- `stop at <класс>:<строка>`

**Пример:**

```java
// Breakpoints в Main.java: {10, 25}
// Класс: com.example.Main

// Отправляются команды:
stop at com.example.Main:10
stop at com.example.Main:25
```

#### `inferClassName(Path javaFile)`

**Назначение:** Определяет полное имя класса из пути к файлу.

**Алгоритм:**

```java
private String inferClassName(Path javaFile) {
    try {
        String content = Files.readString(javaFile, StandardCharsets.UTF_8);
        
        // 1. Поиск package
        Matcher pm = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;").matcher(content);
        String pkg = pm.find() ? pm.group(1) : "";
        
        // 2. Имя файла без .java
        String name = javaFile.getFileName().toString();
        if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
        
        // 3. Объединение package + имя класса
        return pkg.isEmpty() ? name : pkg + "." + name;
    } catch (Exception e) {
        return null;
    }
}
```

**Пример:**

```
Файл: src/main/java/com/example/Main.java
Package: package com.example;
Имя файла: Main.java

Результат: com.example.Main
```

---

## Запуск проекта в режиме отладки

### JavaProjectRunner.debugFile()

**Назначение:** Запускает Java файл с JDWP параметрами.

**Алгоритм:**

```java
public void debugFile(Path projectRoot, Path javaFile) {
    Path sourceRoot = findSourceRoot(projectRoot, javaFile);
    boolean usesJavaFX = checkUsesJavaFX(projectRoot, sourceRoot);
    compileAndRun(projectRoot, sourceRoot, javaFile, usesJavaFX, true);  // debug=true
}
```

#### `compileAndRun(..., boolean debug)`

**Алгоритм формирования команды запуска:**

```java
List<String> runCmd = new ArrayList<>();
runCmd.add(javaCmd);

// UTF-8 кодировка
runCmd.add("-Dfile.encoding=UTF-8");
runCmd.add("-Dconsole.encoding=UTF-8");

// JDWP параметры (если debug=true)
if (debug) {
    runCmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005");
}

runCmd.add("-cp");
runCmd.add(classesDir.toString());

// JavaFX модули (если нужно)
if (usesJavaFX) {
    String javafxPath = findJavaFXPath();
    if (javafxPath != null) {
        runCmd.add("--module-path");
        runCmd.add(javafxPath);
        runCmd.add("--add-modules");
        runCmd.add("javafx.controls,javafx.fxml");
    }
}

runCmd.add(className);  // Имя класса для запуска
```

**Итоговая команда:**

```bash
java -Dfile.encoding=UTF-8 \
     -Dconsole.encoding=UTF-8 \
     -agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 \
     -cp build/classes \
     com.example.Main
```

**Особенности:**

- ✅ `suspend=y` - процесс приостанавливается до подключения отладчика
- ✅ `address=5005` - порт для подключения
- ✅ `server=y` - процесс выступает как сервер

### IntelliJProjectRunner.debugFile()

**Аналогично** `JavaProjectRunner.debugFile()`, но с учетом библиотек из `.idea/libraries`.

---

## Сценарии использования

### Сценарий 1: Установка breakpoint и запуск отладки

```
User: Opens Main.java
User: Clicks on line 10 (gutter)
    │
    ▼
toggleBreakpoint(Main.java, 10)
    │
    ├─→ breakpoints.put(Main.java, {10})
    └─→ Visual dot becomes red
        │
        ▼
User: Presses "Debug Project" button
    │
    ▼
onDebugProject()
    │
    ├─→ debugSelectedTarget(selected)
    │   └─→ JavaProjectRunner.debugFile()
    │       └─→ compileAndRun(..., debug=true)
    │           └─→ java -agentlib:jdwp=... com.example.Main
    │               └─→ Process starts, suspends on port 5005
    │
    └─→ startJdbAttach()
        │
        ├─→ debugSession.connectSocket("localhost", 5005)
        │   └─→ jdb -connect com.sun.jdi.SocketAttach:hostname=localhost,port=5005
        │
        ├─→ debugSession.send("suspend")
        │
        ├─→ applyBreakpointsToJdb()
        │   └─→ debugSession.send("stop at com.example.Main:10")
        │
        └─→ refreshDebugAll()
            ├─→ refreshDebugThreads()
            ├─→ refreshDebugStack()
            └─→ refreshDebugVars()
```

### Сценарий 2: Step Over выполнение

```
User: Presses "Step Over" (F8)
    │
    ▼
onDebugStepOver()
    │
    ├─→ sendJdb("next")
    │   └─→ debugSession.send("next")
    │       └─→ jdb executes "next" command
    │
    └─→ scheduleDebugRefresh()
        │
        └─→ (after 300ms) refreshDebugAll()
            ├─→ refreshDebugThreads()
            ├─→ refreshDebugStack()  // Stack updated
            └─→ refreshDebugVars()    // Variables updated
```

### Сценарий 3: Просмотр переменных

```
User: Selects thread "main" in Threads list
    │
    ▼
debugThreadsList.setOnMouseClicked()
    │
    └─→ debugSession.request("thread 0x1", ...)
        │
        └─→ refreshDebugStackAndVars()
            │
            ├─→ refreshDebugStack()
            │   └─→ debugSession.request("where", lines -> {
            │       └─→ DebugParsers.parseWhere(lines)
            │           └─→ [FrameItem(index=1, "Main.main (Main.java:10)")]
            │
            └─→ refreshDebugVars()
                └─→ debugSession.request("locals", lines -> {
                    └─→ DebugParsers.parseLocals(lines)
                        └─→ [VarItem(name="x", value="10")]
```

### Сценарий 4: Continue выполнение

```
User: Presses "Continue" (F9)
    │
    ▼
onDebugContinue()
    │
    ├─→ sendJdb("cont")
    │   └─→ debugSession.send("cont")
    │       └─→ jdb executes "cont" command
    │           └─→ Process continues until breakpoint
    │
    └─→ scheduleDebugRefresh()
        │
        └─→ (after 300ms) refreshDebugAll()
            └─→ UI updates when breakpoint hit
```

---

## Ограничения и улучшения

### Текущие ограничения

#### 1. Только Plain Java и IntelliJ проекты

**Ограничение:**

- ❌ Gradle/Maven проекты не поддерживают JDWP отладку
- ❌ Только `JavaProjectRunner` и `IntelliJProjectRunner` поддерживают debug

**Код:**

```java
if (type == ProjectDetector.ProjectType.INTELLIJ_IDEA || 
    type == ProjectDetector.ProjectType.JAVA) {
    // Debug supported
} else {
    logToConsole("[DEBUG] For Gradle/Maven projects: JDWP debug is not wired yet.");
    onRunProjectDefault();
}
```

**Улучшения:**

- Добавить поддержку Gradle (`gradlew --debug-jvm`)
- Добавить поддержку Maven (`mvnDebug`)

#### 2. Парсинг вывода jdb

**Ограничения:**

- ❌ Простой regex парсинг (может не работать для сложных случаев)
- ❌ Не обрабатывает все форматы вывода jdb
- ❌ Нет обработки ошибок парсинга

**Улучшения:**

- Использовать более надежные паттерны
- Добавить fallback для неизвестных форматов
- Логирование ошибок парсинга

#### 3. Breakpoints

**Ограничения:**

- ❌ Breakpoints только для текущего файла (не для всех файлов проекта)
- ❌ Нет условных breakpoints
- ❌ Нет логпоинтов (logpoints)

**Улучшения:**

- Поддержка breakpoints во всех файлах проекта
- Условные breakpoints (`stop at Main:10 if x > 5`)
- Логпоинты (вывод без остановки)

#### 4. Переменные

**Ограничения:**

- ❌ Только локальные переменные текущего фрейма
- ❌ Нет просмотра полей объектов
- ❌ Нет вычисления выражений

**Улучшения:**

- Просмотр полей объектов (`obj.field`)
- Вычисление выражений (`x + y`)
- Watches (отслеживание выражений)

#### 5. UI

**Ограничения:**

- ❌ Нет подсветки текущей строки выполнения
- ❌ Нет визуализации стека вызовов
- ❌ Нет интерактивного просмотра объектов

**Улучшения:**

- Подсветка текущей строки (зеленый фон)
- Визуализация стека вызовов (дерево)
- Интерактивный просмотр объектов (раскрытие полей)

### Планируемые улучшения

1. **Gradle/Maven Debug:**
   - Интеграция с `gradlew --debug-jvm`
   - Интеграция с `mvnDebug`

2. **Улучшенный парсинг:**
   - Более надежные regex паттерны
   - Обработка ошибок

3. **Расширенные breakpoints:**
   - Условные breakpoints
   - Логпоинты
   - Breakpoints во всех файлах

4. **Расширенный просмотр переменных:**
   - Поля объектов
   - Вычисление выражений
   - Watches

5. **Улучшенный UI:**
   - Подсветка текущей строки
   - Визуализация стека
   - Интерактивный просмотр объектов

---

## Резюме

### Ключевые особенности Debugger Layer:

1. ✅ **JDWP интеграция** - подключение к Java процессу через JDWP
2. ✅ **jdb управление** - отправка команд и обработка вывода
3. ✅ **Breakpoints** - визуальные точки останова в редакторе
4. ✅ **Step Over/Into/Out** - пошаговое выполнение
5. ✅ **Threads/Stack/Variables** - просмотр состояния выполнения
6. ✅ **Асинхронная обработка** - не блокирует UI

### Технические детали:

- **JDWP параметры:** `-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005`
- **jdb команды:** `threads`, `where`, `locals`, `cont`, `next`, `step`, `stop at`
- **Парсинг:** Regex паттерны для структурирования вывода jdb
- **Breakpoints:** `Map<Path, Set<Integer>>` для хранения точек останова

### Производительность:

- ✅ Асинхронная обработка через отдельные потоки
- ✅ Debounce для обновления UI (300ms)
- ✅ Минимальное использование памяти

---

**Версия:** 1.0  
**Дата:** 2026-01-20  
**Файл:** `Documents/05-Debugger-Layer.md`
