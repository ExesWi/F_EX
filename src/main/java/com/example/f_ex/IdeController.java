package com.example.f_ex;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.geometry.Insets;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

public class IdeController {
    @FXML private TreeView<Path> projectTree;
    @FXML private TabPane editorTabs;
    @FXML private TextArea consoleArea;
    @FXML private Label rootLabel;
    @FXML private Label statusLabel;
    @FXML private Label cursorPositionLabel;
    @FXML private ComboBox<RunTarget> runTargetComboBox;

    private Path projectRoot;
    private Path ideRoot;
    private final Map<Path, Tab> openTabsByPath = new HashMap<>();
    private boolean consoleVisible = true;
    private Process currentRunningProcess;
    private OutputStream processInput;
    private CodeIndexer codeIndexer;
    private SettingsManager settingsManager;
    private javafx.util.Duration autoCompleteDelay = javafx.util.Duration.millis(300);
    private javafx.animation.PauseTransition autoCompleteTimer;
    private final Map<String, List<Path>> searchCache = new ConcurrentHashMap<>();
    
    // Список папок, которые нужно скрыть в дереве
    private static final Set<String> HIDDEN_DIRS = Set.of(
        "build", ".gradle", ".idea", ".git", "out", "bin", 
        ".vscode", "node_modules", ".classpath", ".project"
    );

    private static final String[] JAVA_KEYWORDS = new String[]{
            "abstract","assert","boolean","break","byte","case","catch","char","class","const","continue",
            "default","do","double","else","enum","extends","final","finally","float","for","goto","if",
            "implements","import","instanceof","int","interface","long","native","new","package","private",
            "protected","public","return","short","static","strictfp","super","switch","synchronized","this",
            "throw","throws","transient","try","void","volatile","while","var","record","sealed","permits","non-sealed"
    };

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

    private final ContextMenu completionMenu = new ContextMenu();

    public void initialize() {
        ideRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        settingsManager = new SettingsManager(ideRoot);
        
        // Применяем тему после того, как Scene будет доступна
        Platform.runLater(() -> {
            applyTheme(settingsManager.get(SettingsManager.KEY_THEME, SettingsManager.THEME_LIGHT));
        });
        
        projectTree.setShowRoot(false);
        projectTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item.getFileName() != null ? item.getFileName().toString() : item.toString());
                }
            }
        });

        // Обработка кликов в дереве проекта
        projectTree.setOnMouseClicked(e -> {
            TreeItem<Path> selected = projectTree.getSelectionModel().getSelectedItem();
            if (selected == null || selected.getValue() == null) return;
            
            Path p = selected.getValue();
            
            if (e.getClickCount() == 1) {
                // Одинарный клик - раскрываем/сворачиваем папки
                if (Files.isDirectory(p)) {
                    Platform.runLater(() -> {
                        selected.setExpanded(!selected.isExpanded());
                    });
                }
            } else if (e.getClickCount() == 2) {
                // Двойной клик - открываем файлы
                if (Files.isRegularFile(p)) {
                    Platform.runLater(() -> {
                        openFileInEditor(p);
                    });
                }
            }
        });
        
        // Также обрабатываем Enter для открытия файлов
        projectTree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                TreeItem<Path> selected = projectTree.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue() != null) {
                    Path p = selected.getValue();
                    if (Files.isRegularFile(p)) {
                        openFileInEditor(p);
                    } else if (Files.isDirectory(p)) {
                        selected.setExpanded(!selected.isExpanded());
                    }
                }
            }
        });

        // Basic keyboard shortcuts (active when scene is focused)
        Platform.runLater(() -> {
            Stage stage = getStage();
            if (stage == null || stage.getScene() == null) return;
            stage.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN),
                    this::onSave
            );
            stage.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.O, KeyCombination.CONTROL_DOWN),
                    this::onOpenFile
            );
            stage.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN),
                    this::onFind
            );
            stage.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.H, KeyCombination.CONTROL_DOWN),
                    this::onReplace
            );
            stage.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.W, KeyCombination.CONTROL_DOWN),
                    this::onCloseTab
            );
            stage.getScene().getAccelerators().put(
                    new KeyCodeCombination(KeyCode.F5),
                    this::onRunProject
            );
        });
        
        // Автоскролл консоли
        consoleArea.textProperty().addListener((obs, old, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                consoleArea.setScrollTop(Double.MAX_VALUE);
            }
        });
        
        // Настройка ComboBox для выбора цели запуска
        if (runTargetComboBox != null) {
            runTargetComboBox.setCellFactory(param -> new ListCell<>() {
                @Override
                protected void updateItem(RunTarget item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                    } else {
                        setText(item.getDisplayName());
                    }
                }
            });
            runTargetComboBox.setButtonCell(new ListCell<>() {
                @Override
                protected void updateItem(RunTarget item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText("Select to run...");
                    } else {
                        setText(item.getDisplayName());
                    }
                }
            });
        }
        
        updateStatus("Ready");
    }

    @FXML
    public void onNewProject() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select parent folder for new project");
        var parentDirFile = chooser.showDialog(getStage());
        if (parentDirFile == null) return;

        Dialog<NewProjectConfig> dialog = new Dialog<>();
        dialog.setTitle("New Java Project");
        dialog.setHeaderText("Create a new Gradle Java application project");

        ButtonType createButton = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButton, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField nameField = new TextField("my-app");
        TextField packageField = new TextField("com.example.app");
        CheckBox openAfterCreate = new CheckBox("Open project after create");
        openAfterCreate.setSelected(true);

        grid.add(new Label("Project name:"), 0, 0);
        grid.add(nameField, 1, 0);
        grid.add(new Label("Base package:"), 0, 1);
        grid.add(packageField, 1, 1);
        grid.add(openAfterCreate, 1, 2);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn != createButton) return null;
            String projectName = safeTrim(nameField.getText());
            String basePackage = safeTrim(packageField.getText());
            if (projectName.isEmpty()) projectName = "my-app";
            if (basePackage.isEmpty()) basePackage = "com.example.app";
            return new NewProjectConfig(projectName, basePackage, openAfterCreate.isSelected());
        });

        Optional<NewProjectConfig> result = dialog.showAndWait();
        result.ifPresent(cfg -> createGradleJavaProject(parentDirFile.toPath(), cfg));
    }

    @FXML
    public void onNewJavaClass() {
        if (projectRoot == null) {
            updateStatus("No project root set");
            return;
        }

        Path srcRoot = detectJavaSourceRoot(projectRoot);
        if (srcRoot == null) {
            updateStatus("Can't find src/main/java (or app/src/main/java)");
            return;
        }

        Dialog<NewJavaClassConfig> dialog = new Dialog<>();
        dialog.setTitle("New Java Class");
        dialog.setHeaderText("Create a new Java class file");

        ButtonType createButton = new ButtonType("Create", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButton, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField packageField = new TextField("com.example");
        TextField classField = new TextField("MyClass");
        CheckBox openAfter = new CheckBox("Open after create");
        openAfter.setSelected(true);

        grid.add(new Label("Package:"), 0, 0);
        grid.add(packageField, 1, 0);
        grid.add(new Label("Class name:"), 0, 1);
        grid.add(classField, 1, 1);
        grid.add(openAfter, 1, 2);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> {
            if (btn != createButton) return null;
            String pkg = safeTrim(packageField.getText());
            String cls = safeTrim(classField.getText());
            if (cls.isEmpty()) return null;
            return new NewJavaClassConfig(pkg, cls, openAfter.isSelected());
        });

        dialog.showAndWait().ifPresent(cfg -> {
            Path file = createJavaClassFile(srcRoot, cfg);
            if (file != null && cfg.openAfterCreate) {
                openFileInEditor(file);
            }
        });
    }

    @FXML
    public void onRunProject() {
        // Обновляем список доступных целей запуска
        refreshRunTargets();
        
        // Если есть выбранная цель, запускаем её
        RunTarget selected = runTargetComboBox != null ? runTargetComboBox.getValue() : null;
        if (selected != null) {
            runSelectedTarget(selected);
        } else {
            // Иначе используем старую логику
            onRunProjectDefault();
        }
    }
    
    @FXML
    public void onRunSelected() {
        RunTarget selected = runTargetComboBox != null ? runTargetComboBox.getValue() : null;
        if (selected != null) {
            runSelectedTarget(selected);
        }
    }
    
    private void onRunProjectDefault() {
        if (projectRoot == null) {
            updateStatus("No project root set");
            return;
        }
        
        // Определяем тип проекта и запускаем соответствующим способом
        ProjectDetector.ProjectType type = ProjectDetector.detectProjectType(projectRoot);
        switch (type) {
            case GRADLE -> runGradleIn(projectRoot, "run");
            case MAVEN -> runMaven(projectRoot, "exec:java");
            case INTELLIJ_IDEA -> {
                logToConsole("IntelliJ IDEA project detected.");
                logToConsole("Attempting to extract libraries from .idea/libraries...");
                
                // Пробуем найти библиотеки из IntelliJ IDEA конфигурации
                IntelliJProjectRunner runner = new IntelliJProjectRunner(
                    this::logToConsole,
                    () -> updateStatus("Running IntelliJ IDEA project...")
                );
                runner.run(projectRoot);
            }
            case JAVA -> {
                // Проверяем, может быть в подпапках есть Maven/Gradle
                Path foundMaven = ProjectDetector.findMavenProject(projectRoot);
                Path foundGradle = ProjectDetector.findGradleProject(projectRoot);
                
                if (foundMaven != null) {
                    logToConsole("Found Maven project in subfolder: " + foundMaven);
                    logToConsole("Switching to Maven...");
                    runMaven(foundMaven, "exec:java");
                } else if (foundGradle != null) {
                    logToConsole("Found Gradle project in subfolder: " + foundGradle);
                    logToConsole("Switching to Gradle...");
                    runGradleIn(foundGradle, "run");
                } else {
                    JavaProjectRunner runner = new JavaProjectRunner(
                        this::logToConsole,
                        () -> updateStatus("Running Java project...")
                    );
                    runner.run(projectRoot);
                }
            }
            case UNKNOWN -> {
                logToConsole("Unknown project type. Looking for:");
                logToConsole("  - Gradle: gradlew.bat or build.gradle");
                logToConsole("  - Maven: pom.xml");
                logToConsole("  - IntelliJ IDEA: .idea folder or .iml files");
                logToConsole("  - Java: .java files with main class");
                updateStatus("Unknown project type");
            }
        }
    }
    
    private void refreshRunTargets() {
        if (runTargetComboBox == null || projectRoot == null) return;
        
        List<RunTarget> targets = new ArrayList<>();
        
        // Добавляем опцию "Current File"
        Tab currentTab = editorTabs.getSelectionModel().getSelectedItem();
        if (currentTab != null) {
            EditorTabData data = (EditorTabData) currentTab.getUserData();
            if (data != null && data.path != null && data.path.toString().endsWith(".java")) {
                // Проверяем, есть ли main метод в текущем файле
                try {
                    String content = Files.readString(data.path, StandardCharsets.UTF_8);
                    if (content.contains("public static void main") && 
                        (content.contains("String[] args") || content.contains("String args"))) {
                        ApplicationType appType = detectApplicationType(data.path);
                        String typeLabel = appType == ApplicationType.GUI ? " (GUI)" : 
                                          appType == ApplicationType.CONSOLE ? " (Console)" : "";
                        targets.add(new RunTarget("Current File" + typeLabel, data.path, RunTargetType.CURRENT_FILE));
                    }
                } catch (IOException e) {
                    // Игнорируем
                }
            }
        }
        
        // Ищем все файлы с main методом в проекте
        List<Path> mainClasses = findAllMainClasses(projectRoot);
        for (Path mainClass : mainClasses) {
            String displayName = getDisplayNameForPath(mainClass);
            targets.add(new RunTarget(displayName, mainClass, RunTargetType.MAIN_CLASS));
        }
        
        // Обновляем ComboBox
        runTargetComboBox.getItems().clear();
        runTargetComboBox.getItems().addAll(targets);
        
        if (!targets.isEmpty() && runTargetComboBox.getValue() == null) {
            runTargetComboBox.setValue(targets.get(0));
        }
    }
    
    private List<Path> findAllMainClasses(Path root) {
        List<Path> mainClasses = new ArrayList<>();
        try {
            Files.walk(root, 20)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !shouldHidePath(p.getParent()))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            if (content.contains("public static void main") && 
                                (content.contains("String[] args") || content.contains("String args"))) {
                                mainClasses.add(p);
                            }
                        } catch (IOException e) {
                            // Игнорируем
                        }
                    });
        } catch (IOException e) {
            // Игнорируем
        }
        return mainClasses;
    }
    
    private String getDisplayNameForPath(Path path) {
        String baseName;
        if (projectRoot == null) {
            baseName = path.getFileName().toString();
        } else {
            try {
                Path relative = projectRoot.relativize(path);
                baseName = relative.toString().replace(File.separator, ".").replace(".java", "");
            } catch (IllegalArgumentException e) {
                baseName = path.getFileName().toString();
            }
        }
        
        // Определяем тип приложения и добавляем метку
        ApplicationType appType = detectApplicationType(path);
        String typeLabel = appType == ApplicationType.GUI ? " (GUI)" : 
                          appType == ApplicationType.CONSOLE ? " (Console)" : "";
        
        return baseName + typeLabel;
    }
    
    private enum ApplicationType {
        CONSOLE,  // Консольное приложение
        GUI,      // Оконное приложение (Swing/JavaFX)
        UNKNOWN   // Неизвестно
    }
    
    private ApplicationType detectApplicationType(Path javaFile) {
        if (!javaFile.toString().endsWith(".java")) {
            return ApplicationType.UNKNOWN;
        }
        
        try {
            String content = Files.readString(javaFile, StandardCharsets.UTF_8);
            
            // Проверяем наличие main метода
            boolean hasMain = content.contains("public static void main") && 
                             (content.contains("String[] args") || content.contains("String args"));
            
            if (!hasMain) {
                return ApplicationType.UNKNOWN;
            }
            
            // Проверяем наличие GUI библиотек
            boolean hasJavaFX = content.contains("javafx.") || 
                               content.contains("import javafx") ||
                               content.contains("extends Application") ||
                               content.contains("Application.launch");
            
            boolean hasSwing = content.contains("javax.swing") ||
                              content.contains("import javax.swing") ||
                              content.contains("JFrame") ||
                              content.contains("JDialog") ||
                              content.contains("JWindow") ||
                              content.contains("JApplet");
            
            boolean hasAWT = content.contains("java.awt") &&
                            (content.contains("Frame") || content.contains("Window") || 
                             content.contains("Dialog") || content.contains("Applet"));
            
            if (hasJavaFX || hasSwing || hasAWT) {
                return ApplicationType.GUI;
            }
            
            // Если есть main, но нет GUI библиотек - консольное
            return ApplicationType.CONSOLE;
            
        } catch (IOException e) {
            return ApplicationType.UNKNOWN;
        }
    }
    
    private void runSelectedTarget(RunTarget target) {
        if (target == null || target.getPath() == null) return;
        
        if (target.getType() == RunTargetType.CURRENT_FILE || target.getType() == RunTargetType.MAIN_CLASS) {
            runJavaFile(target.getPath());
        }
    }
    
    private void runJavaFile(Path javaFile) {
        if (projectRoot == null) {
            updateStatus("No project root set");
            return;
        }
        
        // Определяем тип проекта
        ProjectDetector.ProjectType type = ProjectDetector.detectProjectType(projectRoot);
        
        if (type == ProjectDetector.ProjectType.INTELLIJ_IDEA || type == ProjectDetector.ProjectType.JAVA) {
            // Запускаем через JavaProjectRunner или IntelliJProjectRunner
            if (type == ProjectDetector.ProjectType.INTELLIJ_IDEA) {
                IntelliJProjectRunner runner = new IntelliJProjectRunner(
                    this::logToConsole,
                    () -> updateStatus("Running: " + javaFile.getFileName()),
                    p -> {
                        currentRunningProcess = p;
                        if (p != null) {
                            try {
                                processInput = p.getOutputStream();
                            } catch (Exception e) {
                                processInput = null;
                            }
                        } else {
                            processInput = null;
                        }
                    }
                );
                runner.runFile(projectRoot, javaFile);
            } else {
                JavaProjectRunner runner = new JavaProjectRunner(
                    this::logToConsole,
                    () -> updateStatus("Running: " + javaFile.getFileName()),
                    p -> {
                        currentRunningProcess = p;
                        if (p != null) {
                            try {
                                processInput = p.getOutputStream();
                            } catch (Exception e) {
                                processInput = null;
                            }
                        } else {
                            processInput = null;
                        }
                    }
                );
                runner.runFile(projectRoot, javaFile);
            }
        } else {
            // Для Gradle/Maven используем стандартный запуск
            onRunProjectDefault();
        }
    }
    
    @FXML
    public void onConsoleKeyPressed(javafx.scene.input.KeyEvent event) {
        if (currentRunningProcess == null || processInput == null) {
            // Если процесс не запущен, разрешаем обычное редактирование
            return;
        }
        
        if (event.getCode() == KeyCode.ENTER) {
            String text = consoleArea.getText();
            int caretPos = consoleArea.getCaretPosition();
            
            // Находим последнюю строку (после последнего \n)
            int lastNewline = text.lastIndexOf('\n', caretPos - 1);
            if (lastNewline < 0) lastNewline = 0;
            
            // Берем текст от последнего \n до текущей позиции (без trim, чтобы сохранить пробелы)
            String lineToSend = text.substring(lastNewline + 1, caretPos);
            
            try {
                // Отправляем строку с переводом строки в UTF-8
                byte[] bytes = (lineToSend + "\r\n").getBytes(StandardCharsets.UTF_8);
                processInput.write(bytes);
                processInput.flush();
                
                // Добавляем новую строку в консоль для визуального отображения
                Platform.runLater(() -> {
                    int pos = consoleArea.getCaretPosition();
                    if (pos < consoleArea.getLength()) {
                        consoleArea.insertText(pos, "\n");
                        consoleArea.positionCaret(pos + 1);
                    } else {
                        consoleArea.appendText("\n");
                        consoleArea.positionCaret(consoleArea.getLength());
                    }
                });
            } catch (IOException e) {
                Platform.runLater(() -> logToConsole("Failed to send input: " + e.getMessage()));
            }
            event.consume();
        }
    }
    
    private void runMaven(Path projectDir, String goal) {
        Path root = projectDir.normalize().toAbsolutePath();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        
        Path mvnwBat = root.resolve("mvnw.cmd");
        Path mvnw = root.resolve("mvnw");
        
        List<String> command = new ArrayList<>();
        
        // Сначала проверяем Maven Wrapper (как для Gradle)
        if (isWindows && Files.exists(mvnwBat)) {
            command.addAll(List.of("cmd.exe", "/c", mvnwBat.toString(), goal));
        } else if (Files.exists(mvnw)) {
            command.addAll(List.of(mvnw.toString(), goal));
        } else if (isWindows) {
            // Пробуем системный Maven
            command.addAll(List.of("cmd.exe", "/c", "mvn.cmd", goal));
        } else {
            command.addAll(List.of("mvn", goal));
        }
        
        logToConsole("$ " + String.join(" ", command));
        
        // Если нет ни wrapper, ни системного Maven, предупреждаем
        if (!Files.exists(mvnwBat) && !Files.exists(mvnw) && isWindows) {
            // Проверяем, есть ли mvn в PATH (простая проверка)
            logToConsole("Maven Wrapper (mvnw.cmd) not found. Trying system Maven...");
            logToConsole("If Maven is not installed, please:");
            logToConsole("  1. Install Maven from https://maven.apache.org/download.cgi");
            logToConsole("  2. Or add Maven Wrapper to your project: mvn wrapper:wrapper");
        }
        
        updateStatus("Running Maven: " + goal);
        
        runCommandInDirectory(command, root, "maven-runner");
    }
    
    
    private void runCommandInDirectory(List<String> command, Path directory, String threadName) {
        runCommandInDirectory(command, directory, threadName, null);
    }
    
    private void runCommandInDirectory(List<String> command, Path directory, String threadName, Runnable onSuccess) {
        final Path finalDir = directory.normalize().toAbsolutePath();
        
        Thread t = new Thread(() -> {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(finalDir.toFile());
            pb.redirectErrorStream(true);
            try {
                Process p = pb.start();
                
                // Сохраняем OutputStream для интерактивного ввода
                Platform.runLater(() -> {
                    currentRunningProcess = p;
                    try {
                        processInput = p.getOutputStream();
                    } catch (Exception e) {
                        processInput = null;
                    }
                });
                
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String finalLine = line;
                        Platform.runLater(() -> logToConsole(finalLine));
                    }
                }
                int code = p.waitFor();
                Platform.runLater(() -> {
                    currentRunningProcess = null;
                    processInput = null;
                    logToConsole("Process finished with exit code: " + code);
                    if (code == 0) {
                        updateStatus("Done");
                        if (onSuccess != null) {
                            onSuccess.run();
                        }
                    } else {
                        updateStatus("Failed (exit " + code + ")");
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    currentRunningProcess = null;
                    processInput = null;
                    logToConsole("Failed to run command: " + ex.getMessage());
                    updateStatus("Failed");
                });
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
    }

    @FXML
    public void onOpenFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select project root folder");
        FileChooser initial = null;
        var stage = getStage();
        var dir = chooser.showDialog(stage);
        if (dir == null) return;
        setProjectRoot(dir.toPath());
    }

    @FXML
    public void onOpenFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open file");
        if (projectRoot != null && Files.isDirectory(projectRoot)) {
            chooser.setInitialDirectory(projectRoot.toFile());
        }
        var file = chooser.showOpenDialog(getStage());
        if (file == null) return;
        openFileInEditor(file.toPath());
    }

    @FXML
    public void onSave() {
        Tab tab = editorTabs.getSelectionModel().getSelectedItem();
        if (tab == null) return;
        EditorTabData data = (EditorTabData) tab.getUserData();
        if (data == null) return;
        if (data.editor == null) {
            updateStatus("This tab is not a text editor");
            return;
        }

        if (data.path == null) {
            onSaveAs();
            return;
        }
        saveTabToPath(tab, data.path);
    }

    @FXML
    public void onSaveAs() {
        Tab tab = editorTabs.getSelectionModel().getSelectedItem();
        if (tab == null) return;
        EditorTabData data = (EditorTabData) tab.getUserData();
        if (data == null) return;
        if (data.editor == null) {
            updateStatus("This tab is not a text editor");
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save file as");
        if (projectRoot != null && Files.isDirectory(projectRoot)) {
            chooser.setInitialDirectory(projectRoot.toFile());
        }
        var file = chooser.showSaveDialog(getStage());
        if (file == null) return;
        Path path = file.toPath();
        saveTabToPath(tab, path);
        data.path = path;
        tab.setText(path.getFileName() != null ? path.getFileName().toString() : path.toString());
        openTabsByPath.put(path.normalize().toAbsolutePath(), tab);
        refreshTreeIfUnderRoot(path);
    }

    @FXML public void onGradleBuild() { runGradle("build"); }
    @FXML public void onGradleRun() { runGradle("run"); }
    @FXML public void onGradleTest() { runGradle("test"); }
    @FXML public void onGradleClean() { runGradle("clean"); }

    @FXML
    public void onClearConsole() {
        consoleArea.clear();
    }
    
    @FXML
    public void onCloseTab() {
        Tab tab = editorTabs.getSelectionModel().getSelectedItem();
        if (tab != null) {
            editorTabs.getTabs().remove(tab);
        }
    }
    
    @FXML
    public void onCloseAllTabs() {
        editorTabs.getTabs().clear();
        openTabsByPath.clear();
    }
    
    @FXML
    public void onFind() {
        Tab tab = editorTabs.getSelectionModel().getSelectedItem();
        if (tab == null) {
            updateStatus("No file open");
            return;
        }
        
        EditorTabData data = (EditorTabData) tab.getUserData();
        if (data == null) return;
        
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Find");
        dialog.setHeaderText("Enter text to find:");
        dialog.setContentText("Text:");
        
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(text -> {
            CodeArea editor = data.editor;
            String content = editor.getText();
            if (content == null) return;
            
            int index = content.indexOf(text, editor.getCaretPosition());
            if (index == -1) {
                index = content.indexOf(text, 0);
            }
            
            if (index >= 0) {
                editor.selectRange(index, index + text.length());
                editor.requestFocus();
                updateStatus("Found: " + text);
            } else {
                updateStatus("Not found: " + text);
            }
        });
    }
    
    @FXML
    public void onReplace() {
        Tab tab = editorTabs.getSelectionModel().getSelectedItem();
        if (tab == null) {
            updateStatus("No file open");
            return;
        }
        
        EditorTabData data = (EditorTabData) tab.getUserData();
        if (data == null) return;
        
        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Replace");
        dialog.setHeaderText("Find and Replace");
        
        ButtonType replaceButtonType = new ButtonType("Replace", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(replaceButtonType, ButtonType.CANCEL);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField findField = new TextField();
        findField.setPromptText("Find");
        TextField replaceField = new TextField();
        replaceField.setPromptText("Replace with");
        
        grid.add(new Label("Find:"), 0, 0);
        grid.add(findField, 1, 0);
        grid.add(new Label("Replace:"), 0, 1);
        grid.add(replaceField, 1, 1);
        
        dialog.getDialogPane().setContent(grid);
        
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == replaceButtonType) {
                Map<String, String> result = new HashMap<>();
                result.put("find", findField.getText());
                result.put("replace", replaceField.getText());
                return result;
            }
            return null;
        });
        
        Optional<Map<String, String>> result = dialog.showAndWait();
        result.ifPresent(map -> {
            String findText = map.get("find");
            String replaceText = map.get("replace");
            if (findText == null || findText.isEmpty()) return;
            
            CodeArea editor = data.editor;
            String content = editor.getText();
            if (content == null) return;
            
            String newContent = content.replace(findText, replaceText);
            if (!newContent.equals(content)) {
                editor.replaceText(newContent);
                updateStatus("Replaced: " + findText + " -> " + replaceText);
            } else {
                updateStatus("Not found: " + findText);
            }
        });
    }
    
    @FXML
    public void onSelectAll() {
        Tab tab = editorTabs.getSelectionModel().getSelectedItem();
        if (tab == null) return;
        
        EditorTabData data = (EditorTabData) tab.getUserData();
        if (data == null) return;
        
        data.editor.selectAll();
    }
    
    @FXML
    public void onRefreshTree() {
        if (projectRoot != null) {
            setProjectRoot(projectRoot);
            updateStatus("Project tree refreshed");
        } else {
            updateStatus("No project root set");
        }
    }
    
    @FXML
    public void onToggleConsole() {
        consoleVisible = !consoleVisible;
        // Простое переключение - можно улучшить через SplitPane
        updateStatus("Console " + (consoleVisible ? "shown" : "hidden"));
    }
    
    @FXML
    public void onToggleTheme() {
        String currentTheme = settingsManager.get(SettingsManager.KEY_THEME, SettingsManager.THEME_LIGHT);
        String newTheme = SettingsManager.THEME_DARK.equals(currentTheme) ? 
            SettingsManager.THEME_LIGHT : SettingsManager.THEME_DARK;
        settingsManager.set(SettingsManager.KEY_THEME, newTheme);
        applyTheme(newTheme);
        updateStatus("Theme: " + newTheme);
    }
    
    @FXML
    public void onSettings() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Settings");
        dialog.setHeaderText("IDE Preferences");
        
        ButtonType saveButton = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, ButtonType.CANCEL);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        // Тема
        Label themeLabel = new Label("Theme:");
        ComboBox<String> themeCombo = new ComboBox<>();
        themeCombo.getItems().addAll(SettingsManager.THEME_LIGHT, SettingsManager.THEME_DARK);
        themeCombo.setValue(settingsManager.get(SettingsManager.KEY_THEME, SettingsManager.THEME_LIGHT));
        
        // Шрифт
        Label fontLabel = new Label("Font Family:");
        TextField fontField = new TextField(settingsManager.get(SettingsManager.KEY_FONT_FAMILY, "Consolas"));
        
        // Размер шрифта
        Label fontSizeLabel = new Label("Font Size:");
        Spinner<Integer> fontSizeSpinner = new Spinner<>(8, 24, 
            settingsManager.getInt(SettingsManager.KEY_FONT_SIZE, 13));
        fontSizeSpinner.setEditable(true);
        
        // Автодополнение
        Label autoCompleteLabel = new Label("Auto Complete:");
        CheckBox autoCompleteCheck = new CheckBox("Enable auto completion");
        autoCompleteCheck.setSelected(settingsManager.getBoolean(SettingsManager.KEY_AUTO_COMPLETE, true));
        
        // Задержка автодополнения
        Label delayLabel = new Label("Auto Complete Delay (ms):");
        Spinner<Integer> delaySpinner = new Spinner<>(100, 1000, 
            settingsManager.getInt(SettingsManager.KEY_AUTO_COMPLETE_DELAY, 300));
        delaySpinner.setEditable(true);
        
        grid.add(themeLabel, 0, 0);
        grid.add(themeCombo, 1, 0);
        grid.add(fontLabel, 0, 1);
        grid.add(fontField, 1, 1);
        grid.add(fontSizeLabel, 0, 2);
        grid.add(fontSizeSpinner, 1, 2);
        grid.add(autoCompleteLabel, 0, 3);
        grid.add(autoCompleteCheck, 1, 3);
        grid.add(delayLabel, 0, 4);
        grid.add(delaySpinner, 1, 4);
        
        dialog.getDialogPane().setContent(grid);
        
        dialog.setResultConverter(buttonType -> {
            if (buttonType == saveButton) {
                settingsManager.set(SettingsManager.KEY_THEME, themeCombo.getValue());
                settingsManager.set(SettingsManager.KEY_FONT_FAMILY, fontField.getText());
                settingsManager.setInt(SettingsManager.KEY_FONT_SIZE, fontSizeSpinner.getValue());
                settingsManager.setBoolean(SettingsManager.KEY_AUTO_COMPLETE, autoCompleteCheck.isSelected());
                settingsManager.setInt(SettingsManager.KEY_AUTO_COMPLETE_DELAY, delaySpinner.getValue());
                
                applyTheme(themeCombo.getValue());
                applyFontSettings(fontField.getText(), fontSizeSpinner.getValue());
                updateStatus("Settings saved");
            }
            return null;
        });
        
        dialog.showAndWait();
    }
    
    @FXML
    public void onFindInFiles() {
        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.setTitle("Find in Files");
        dialog.setHeaderText("Search for text in project files");
        
        ButtonType searchButton = new ButtonType("Search", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(searchButton, ButtonType.CANCEL);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));
        
        TextField searchField = new TextField();
        searchField.setPromptText("Enter text to search...");
        CheckBox caseSensitive = new CheckBox("Case sensitive");
        
        grid.add(new Label("Search:"), 0, 0);
        grid.add(searchField, 1, 0);
        grid.add(caseSensitive, 1, 1);
        
        dialog.getDialogPane().setContent(grid);
        
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
        result.ifPresent(map -> {
            String searchText = map.get("text");
            boolean caseSens = Boolean.parseBoolean(map.get("caseSensitive"));
            if (searchText != null && !searchText.isEmpty()) {
                searchInFiles(searchText, caseSens);
            }
        });
    }
    
    private void searchInFiles(String searchText, boolean caseSensitive) {
        if (projectRoot == null) {
            updateStatus("No project root set");
            return;
        }
        
        // Проверяем кэш
        String cacheKey = searchText + "|" + caseSensitive;
        if (searchCache.containsKey(cacheKey)) {
            logToConsole("Using cached results for: " + searchText);
            List<Path> cached = searchCache.get(cacheKey);
            for (Path file : cached) {
                Path relative = projectRoot.relativize(file);
                logToConsole(relative.toString());
            }
            logToConsole("---");
            logToConsole("Search completed (cached)");
            return;
        }
        
        logToConsole("Searching for: " + searchText + (caseSensitive ? " (case sensitive)" : ""));
        logToConsole("---");
        
        List<Path> foundFiles = new ArrayList<>();
        Thread searchThread = new Thread(() -> {
            try {
                Files.walk(projectRoot, 20)
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !CodeIndexer.shouldHidePath(p.getParent()))
                        .forEach(file -> {
                            try {
                                String content = Files.readString(file, StandardCharsets.UTF_8);
                                String search = caseSensitive ? searchText : searchText.toLowerCase();
                                String fileContent = caseSensitive ? content : content.toLowerCase();
                                
                                if (fileContent.contains(search)) {
                                    foundFiles.add(file);
                                    // Подсчитываем вхождения
                                    int count = 0;
                                    int index = 0;
                                    while ((index = fileContent.indexOf(search, index)) != -1) {
                                        count++;
                                        index += search.length();
                                    }
                                    
                                    Path relative = projectRoot.relativize(file);
                                    final int countFinal = count;
                                    Platform.runLater(() -> {
                                        logToConsole(relative + " (" + countFinal + " matches)");
                                    });
                                }
                            } catch (IOException e) {
                                // Игнорируем ошибки чтения
                            }
                        });
                
                // Сохраняем в кэш
                searchCache.put(cacheKey, foundFiles);
                
                Platform.runLater(() -> {
                    logToConsole("---");
                    logToConsole("Search completed. Found " + foundFiles.size() + " files.");
                });
            } catch (IOException e) {
                Platform.runLater(() -> logToConsole("Search failed: " + e.getMessage()));
            }
        }, "file-search");
        searchThread.setDaemon(true);
        searchThread.start();
    }
    
    @FXML
    public void onCloneFromGitHub() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Clone from GitHub");
        dialog.setHeaderText("Enter GitHub repository URL");
        dialog.setContentText("Repository URL:");
        dialog.getEditor().setPromptText("https://github.com/username/repo.git");
        
        Optional<String> result = dialog.showAndWait();
        result.ifPresent(url -> cloneGitHubRepository(url));
    }
    
    private void cloneGitHubRepository(String url) {
        if (url == null || url.trim().isEmpty()) {
            updateStatus("Invalid URL");
            return;
        }
        
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Select folder to clone repository");
        var stage = getStage();
        var targetDir = chooser.showDialog(stage);
        if (targetDir == null) return;
        
        logToConsole("Cloning repository: " + url);
        logToConsole("Target directory: " + targetDir);
        
        Thread cloneThread = new Thread(() -> {
            try {
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                List<String> command = new ArrayList<>();
                
                if (isWindows) {
                    command.addAll(List.of("cmd.exe", "/c", "git", "clone", url));
                } else {
                    command.addAll(List.of("git", "clone", url));
                }
                
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(targetDir);
                pb.redirectErrorStream(true);
                
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String finalLine = line;
                        Platform.runLater(() -> logToConsole(finalLine));
                    }
                }
                
                int code = p.waitFor();
                Platform.runLater(() -> {
                    if (code == 0) {
                        logToConsole("Repository cloned successfully!");
                        updateStatus("Clone completed");
                        // Предлагаем открыть проект
                        Path clonedPath = targetDir.toPath();
                        if (url.contains("/")) {
                            String repoName = url.substring(url.lastIndexOf('/') + 1).replace(".git", "");
                            clonedPath = clonedPath.resolve(repoName);
                        }
                        if (Files.exists(clonedPath)) {
                            setProjectRoot(clonedPath);
                        }
                    } else {
                        logToConsole("Clone failed with exit code: " + code);
                        updateStatus("Clone failed");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    logToConsole("Failed to clone repository: " + e.getMessage());
                    logToConsole("Make sure Git is installed and in PATH");
                    updateStatus("Clone failed");
                });
            }
        }, "git-clone");
        cloneThread.setDaemon(true);
        cloneThread.start();
    }
    
    private void updateStatus(String message) {
        if (statusLabel != null) {
            statusLabel.setText(message);
        }
    }

    private void setProjectRoot(Path root) {
        Path actualRoot = root.normalize().toAbsolutePath();
        
        // Если в корне нет проекта, ищем в подпапках
        ProjectDetector.ProjectType type = ProjectDetector.detectProjectType(actualRoot);
        if (type == ProjectDetector.ProjectType.UNKNOWN) {
            Path found = ProjectDetector.findGradleProject(actualRoot);
            if (found != null) {
                actualRoot = found;
                logToConsole("Auto-detected Gradle project: " + actualRoot);
            } else {
                found = ProjectDetector.findMavenProject(actualRoot);
                if (found != null) {
                    actualRoot = found;
                    logToConsole("Auto-detected Maven project: " + actualRoot);
                } else {
                    found = ProjectDetector.findJavaProject(actualRoot);
                    if (found != null) {
                        actualRoot = found;
                        logToConsole("Auto-detected Java project: " + actualRoot);
                    }
                }
            }
        }
        
        projectRoot = actualRoot;
        rootLabel.setText(projectRoot.toString());
        projectTree.setRoot(buildFileTreeRoot(projectRoot));
        projectTree.getRoot().setExpanded(true);
        logToConsole("Project root set to: " + projectRoot);
        
        // Показываем тип проекта
        ProjectDetector.ProjectType detectedType = ProjectDetector.detectProjectType(projectRoot);
        logToConsole("Project type: " + detectedType);
        
        // Индексируем проект для автодополнения
        codeIndexer = new CodeIndexer(projectRoot);
        Thread indexThread = new Thread(() -> {
            logToConsole("Indexing project for code completion...");
            codeIndexer.indexProject();
            Platform.runLater(() -> logToConsole("Project indexed. Code completion ready."));
        }, "code-indexer");
        indexThread.setDaemon(true);
        indexThread.start();
        
        // Обновляем список целей запуска
        refreshRunTargets();
    }
    
    private void applyTheme(String theme) {
        Stage stage = getStage();
        if (stage == null || stage.getScene() == null) return;
        
        if (SettingsManager.THEME_DARK.equals(theme)) {
            stage.getScene().getRoot().getStyleClass().add("dark-theme");
        } else {
            stage.getScene().getRoot().getStyleClass().remove("dark-theme");
        }
    }
    
    private void applyFontSettings(String fontFamily, int fontSize) {
        // Применяем настройки шрифта ко всем открытым редакторам
        for (Tab tab : editorTabs.getTabs()) {
            EditorTabData data = (EditorTabData) tab.getUserData();
            if (data != null && data.editor != null) {
                data.editor.setStyle(String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;", 
                    fontFamily, fontSize));
            }
        }
    }

    private static Path detectJavaSourceRoot(Path projectRoot) {
        Path a = projectRoot.resolve("src").resolve("main").resolve("java");
        if (Files.isDirectory(a)) return a;
        Path b = projectRoot.resolve("app").resolve("src").resolve("main").resolve("java");
        if (Files.isDirectory(b)) return b;
        return null;
    }

    private Path createJavaClassFile(Path srcRoot, NewJavaClassConfig cfg) {
        String pkg = safeTrim(cfg.packageName);
        String cls = safeTrim(cfg.className);
        if (!cls.matches("[A-Za-z_$][A-Za-z\\d_$]*")) {
            updateStatus("Invalid class name");
            return null;
        }
        if (!pkg.isEmpty() && !pkg.matches("[A-Za-z_$][A-Za-z\\d_$]*(\\.[A-Za-z_$][A-Za-z\\d_$]*)*")) {
            updateStatus("Invalid package");
            return null;
        }

        Path dir = pkg.isEmpty()
                ? srcRoot
                : srcRoot.resolve(pkg.replace('.', java.io.File.separatorChar));
        Path file = dir.resolve(cls + ".java");

        try {
            Files.createDirectories(dir);
            if (Files.exists(file)) {
                updateStatus("File already exists");
                return file;
            }

            StringBuilder sb = new StringBuilder();
            if (!pkg.isEmpty()) {
                sb.append("package ").append(pkg).append(";\n\n");
            }
            sb.append("public class ").append(cls).append(" {\n")
              .append("    \n")
              .append("}\n");

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            logToConsole("Created: " + file);
            updateStatus("Created: " + cls);
            refreshTreeIfUnderRoot(file);
            return file;
        } catch (Exception e) {
            logToConsole("Failed to create java class: " + e.getMessage());
            updateStatus("Create failed");
            return null;
        }
    }

    private TreeItem<Path> buildFileTreeRoot(Path root) {
        TreeItem<Path> treeRoot = new TreeItem<>(root);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                // Пропускаем скрытые папки
                if (shouldHidePath(child)) {
                    continue;
                }
                treeRoot.getChildren().add(buildTreeItem(child));
            }
        } catch (IOException e) {
            logToConsole("Failed to read directory: " + e.getMessage());
        }
        treeRoot.getChildren().sort((a, b) -> {
            Path pa = a.getValue();
            Path pb = b.getValue();
            boolean da = pa != null && Files.isDirectory(pa);
            boolean db = pb != null && Files.isDirectory(pb);
            if (da != db) return da ? -1 : 1;
            String sa = pa != null && pa.getFileName() != null ? pa.getFileName().toString() : String.valueOf(pa);
            String sb = pb != null && pb.getFileName() != null ? pb.getFileName().toString() : String.valueOf(pb);
            return sa.compareToIgnoreCase(sb);
        });
        return treeRoot;
    }
    
    private static boolean shouldHidePath(Path path) {
        if (path == null) return true;
        String name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        return HIDDEN_DIRS.contains(name) || name.startsWith(".");
    }

    private TreeItem<Path> buildTreeItem(Path path) {
        TreeItem<Path> item = new TreeItem<>(path);
        if (Files.isDirectory(path)) {
            item.setExpanded(false);
            // Lazy load on expand to keep UI snappy for big folders
            item.expandedProperty().addListener((obs, was, isNow) -> {
                if (!isNow) return;
                if (!item.getChildren().isEmpty()) return;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    for (Path child : stream) {
                        // Пропускаем скрытые папки
                        if (shouldHidePath(child)) {
                            continue;
                        }
                        item.getChildren().add(buildTreeItem(child));
                    }
                } catch (IOException ignored) {
                }
            });
        }
        return item;
    }

    private void openFileInEditor(Path path) {
        Path abs = path.normalize().toAbsolutePath();
        Tab existing = openTabsByPath.get(abs);
        if (existing != null) {
            editorTabs.getSelectionModel().select(existing);
            return;
        }

        if (isImageFile(abs)) {
            openImageInViewer(abs);
            return;
        }

        String text;
        try {
            text = Files.readString(abs, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logToConsole("Failed to open file: " + e.getMessage());
            return;
        }

        CodeArea editor = new CodeArea();
        
        // Применяем настройки шрифта
        String fontFamily = settingsManager.get(SettingsManager.KEY_FONT_FAMILY, "Consolas");
        int fontSize = settingsManager.getInt(SettingsManager.KEY_FONT_SIZE, 13);
        editor.setStyle(String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;", fontFamily, fontSize));
        
        // Оптимизация для больших файлов - загружаем по частям
        if (text.length() > 100000) { // Если файл больше 100KB
            editor.replaceText(text.substring(0, Math.min(50000, text.length())));
            editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
            // Загружаем остальное в фоне
            Thread loadThread = new Thread(() -> {
                String remaining = text.substring(50000);
                Platform.runLater(() -> {
                    editor.appendText(remaining);
                    applyHighlighting(editor);
                });
            }, "file-loader");
            loadThread.setDaemon(true);
            loadThread.start();
        } else {
            editor.replaceText(text);
            editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        }

        editor.textProperty().addListener((obs, old, newVal) -> applyHighlighting(editor));
        editor.caretPositionProperty().addListener((obs, old, pos) -> updateCursorPosition(editor));

        // Автоматическое автодополнение при вводе
        if (settingsManager.getBoolean(SettingsManager.KEY_AUTO_COMPLETE, true)) {
            editor.textProperty().addListener((obs, oldVal, newVal) -> {
                if (oldVal == null || newVal == null) return;
                
                // Проверяем, что был добавлен символ (не удаление)
                if (newVal.length() > oldVal.length()) {
                    int pos = editor.getCaretPosition();
                    if (pos > 0) {
                        char lastChar = newVal.charAt(pos - 1);
                        // Показываем автодополнение после ввода буквы или цифры
                        if (Character.isJavaIdentifierPart(lastChar) || lastChar == '.') {
                            scheduleAutoComplete(editor);
                        }
                    }
                }
            });
        }
        
        // Ctrl+Space: принудительный показ автодополнения
        editor.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (new KeyCodeCombination(KeyCode.SPACE, KeyCombination.CONTROL_DOWN).match(e)) {
                showCompletion(editor);
                e.consume();
            }
        });

        Tab tab = new Tab(abs.getFileName() != null ? abs.getFileName().toString() : abs.toString());
        tab.setContent(wrapContent(editor));
        tab.setUserData(new EditorTabData(abs, editor));
        tab.setOnClosed(evt -> openTabsByPath.remove(abs));

        editorTabs.getTabs().add(tab);
        editorTabs.getSelectionModel().select(tab);
        openTabsByPath.put(abs, tab);
        applyHighlighting(editor);
        updateCursorPosition(editor);
        logToConsole("Opened: " + abs);
    }

    private BorderPane wrapEditor(CodeArea editor) {
        return wrapContent(editor);
    }

    private BorderPane wrapContent(Node content) {
        BorderPane pane = new BorderPane();
        pane.setCenter(content);
        return pane;
    }

    private static boolean isImageFile(Path path) {
        if (path == null) return false;
        String name = path.getFileName() != null ? path.getFileName().toString().toLowerCase() : path.toString().toLowerCase();
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".gif")
                || name.endsWith(".bmp")
                || name.endsWith(".webp");
    }

    private void openImageInViewer(Path abs) {
        try (InputStream in = Files.newInputStream(abs)) {
            Image image = new Image(in);
            ImageView imageView = new ImageView(image);
            imageView.setPreserveRatio(true);
            imageView.setSmooth(true);

            // Вписываем в доступную область, но оставляем возможность скролла
            StackPane container = new StackPane(imageView);
            container.setStyle("-fx-background-color: transparent;");

            ScrollPane scrollPane = new ScrollPane(container);
            scrollPane.setFitToWidth(true);
            scrollPane.setFitToHeight(true);
            scrollPane.setPannable(true);

            // Когда меняется размер - подгоняем изображение под ширину/высоту
            scrollPane.viewportBoundsProperty().addListener((obs, old, bounds) -> {
                if (bounds == null) return;
                imageView.setFitWidth(Math.max(0, bounds.getWidth()));
                imageView.setFitHeight(Math.max(0, bounds.getHeight()));
            });

            Tab tab = new Tab(abs.getFileName() != null ? abs.getFileName().toString() : abs.toString());
            tab.setContent(wrapContent(scrollPane));
            tab.setUserData(new EditorTabData(abs, null));
            tab.setOnClosed(evt -> openTabsByPath.remove(abs));

            editorTabs.getTabs().add(tab);
            editorTabs.getSelectionModel().select(tab);
            openTabsByPath.put(abs, tab);
            logToConsole("Opened image: " + abs);
        } catch (Exception e) {
            logToConsole("Failed to open image: " + e.getMessage());
            updateStatus("Failed to open image");
        }
    }
    
    private void updateCursorPosition(CodeArea editor) {
        if (editor == null) return;
        int line = editor.getCurrentParagraph() + 1;
        int col = editor.getCaretColumn() + 1;
        int total = Math.max(1, editor.getParagraphs().size());
        cursorPositionLabel.setText(String.format("Ln %d/%d, Col %d", line, total, col));
    }

    private void saveTabToPath(Tab tab, Path path) {
        EditorTabData data = (EditorTabData) tab.getUserData();
        if (data == null) return;
        try {
            Files.createDirectories(Optional.ofNullable(path.getParent()).orElse(Paths.get(".")));
            Files.writeString(path, data.editor.getText(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            logToConsole("Saved: " + path);
        } catch (IOException e) {
            logToConsole("Failed to save file: " + e.getMessage());
        }
    }

    private void applyHighlighting(CodeArea area) {
        if (area == null) return;
        String text = area.getText();
        area.setStyleSpans(0, computeHighlighting(text));
    }

    private static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = JAVA_SYNTAX.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        while (matcher.find()) {
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

            spans.add(Collections.emptyList(), matcher.start() - lastEnd);
            spans.add(style == null ? Collections.emptyList() : Collections.singleton(style),
                    matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        spans.add(Collections.emptyList(), text.length() - lastEnd);
        return spans.create();
    }

    private void scheduleAutoComplete(CodeArea area) {
        if (autoCompleteTimer != null) {
            autoCompleteTimer.stop();
        }
        
        autoCompleteTimer = new javafx.animation.PauseTransition(
            javafx.util.Duration.millis(settingsManager.getInt(SettingsManager.KEY_AUTO_COMPLETE_DELAY, 300))
        );
        autoCompleteTimer.setOnFinished(e -> {
            if (completionMenu.isShowing()) return; // Уже показано
            showCompletion(area);
        });
        autoCompleteTimer.play();
    }
    
    private void showCompletion(CodeArea area) {
        if (area == null) return;
        completionMenu.getItems().clear();

        String prefix = currentWordPrefix(area);
        if (prefix.isEmpty() && !area.getText().isEmpty()) {
            // Если нет префикса, но есть точка - ищем методы/поля класса
            int pos = area.getCaretPosition();
            String text = area.getText();
            if (pos > 0 && text.charAt(pos - 1) == '.') {
                prefix = ""; // Показываем все доступные элементы
            } else {
                return; // Не показываем автодополнение без префикса
            }
        }

        List<CompletionItem> suggestions = new ArrayList<>();
        
        // Ключевые слова Java
        for (String kw : JAVA_KEYWORDS) {
            if (kw.toLowerCase().startsWith(prefix.toLowerCase())) {
                suggestions.add(new CompletionItem(kw, CompletionItemType.KEYWORD, kw));
            }
        }
        
        // Элементы из индекса проекта
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
        
        // Сниппеты
        String lowerPrefix = prefix.toLowerCase();
        if ("sys".startsWith(lowerPrefix) || "system".startsWith(lowerPrefix)) {
            suggestions.add(new CompletionItem("System.out.println()", CompletionItemType.SNIPPET, "System.out.println()"));
        }
        if ("main".startsWith(lowerPrefix)) {
            suggestions.add(new CompletionItem("main method", CompletionItemType.SNIPPET, "public static void main(String[] args) {\n    \n}"));
        }
        if ("for".startsWith(lowerPrefix)) {
            suggestions.add(new CompletionItem("for loop", CompletionItemType.SNIPPET, "for (int i = 0; i < length; i++) {\n    \n}"));
        }
        if ("if".startsWith(lowerPrefix)) {
            suggestions.add(new CompletionItem("if statement", CompletionItemType.SNIPPET, "if (condition) {\n    \n}"));
        }

        // Удаляем дубликаты и сортируем
        suggestions = suggestions.stream()
            .distinct()
            .sorted((a, b) -> {
                int typeCompare = a.getType().compareTo(b.getType());
                if (typeCompare != 0) return typeCompare;
                return a.getText().compareToIgnoreCase(b.getText());
            })
            .limit(30)
            .toList();

        if (suggestions.isEmpty()) {
            completionMenu.hide();
            return;
        }

        final String finalPrefix = prefix;
        for (CompletionItem item : suggestions) {
            MenuItem menuItem = new MenuItem(item.getDisplayName());
            final String completion = item.getCompletion();
            menuItem.setOnAction(e -> insertCompletion(area, finalPrefix, completion));
            completionMenu.getItems().add(menuItem);
        }

        area.requestFocus();
        area.getCaretBounds().ifPresent(bounds -> {
            completionMenu.show(area, bounds.getMaxX(), bounds.getMaxY());
        });
    }
    
    private static class CompletionItem {
        private final String displayName;
        private final CompletionItemType type;
        private final String completion;
        
        public CompletionItem(String displayName, CompletionItemType type, String completion) {
            this.displayName = displayName;
            this.type = type;
            this.completion = completion;
        }
        
        public String getDisplayName() { return displayName; }
        public CompletionItemType getType() { return type; }
        public String getText() { return displayName; }
        public String getCompletion() { return completion; }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CompletionItem that = (CompletionItem) o;
            return Objects.equals(completion, that.completion);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(completion);
        }
    }
    
    private enum CompletionItemType {
        KEYWORD, CLASS, INTERFACE, METHOD, VARIABLE, SNIPPET;
        
        public static CompletionItemType fromCodeElementType(CodeIndexer.CodeElementType type) {
            return switch (type) {
                case CLASS -> CLASS;
                case INTERFACE -> INTERFACE;
                case METHOD -> METHOD;
                case VARIABLE -> VARIABLE;
            };
        }
    }

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

    private static void insertCompletion(CodeArea area, String prefix, String completion) {
        int pos = area.getCaretPosition();
        int start = pos - (prefix == null ? 0 : prefix.length());
        if (start < 0) start = pos;
        area.replaceText(start, pos, completion);
        // place caret inside println parentheses if applicable
        int caret = start + completion.length();
        int paren = completion.indexOf("()");
        if (paren >= 0) {
            caret = start + paren + 1;
        }
        area.displaceCaret(caret);
    }

    private void refreshTreeIfUnderRoot(Path path) {
        if (projectRoot == null) return;
        Path abs = path.normalize().toAbsolutePath();
        if (!abs.startsWith(projectRoot)) return;
        // Quick approach for MVP: rebuild root. (We can optimize later.)
        projectTree.setRoot(buildFileTreeRoot(projectRoot));
        projectTree.getRoot().setExpanded(true);
    }

    private void runGradle(String task) {
        if (projectRoot == null) {
            // default to current project folder (where gradlew is)
            setProjectRoot(Paths.get(System.getProperty("user.dir")));
        }
        runGradleIn(projectRoot, task);
    }

    private void runGradleIn(Path projectDir, String task) {
        if (projectDir == null) return;
        Path root = projectDir.normalize().toAbsolutePath();

        Path gradlewBat = root.resolve("gradlew.bat");
        Path gradlew = root.resolve("gradlew");
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        // Если в корне нет gradlew, ищем в подпапках
        if (!Files.exists(gradlewBat) && !Files.exists(gradlew)) {
            Path found = ProjectDetector.findGradleProject(root);
            if (found != null) {
                root = found;
                gradlewBat = root.resolve("gradlew.bat");
                gradlew = root.resolve("gradlew");
                logToConsole("Found Gradle project in: " + root);
            }
        }

        final Path finalRoot = root; // для использования в лямбде

        List<String> command = new ArrayList<>();
        if (isWindows && Files.exists(gradlewBat)) {
            command.addAll(List.of("cmd.exe", "/c", gradlewBat.toString(), task));
        } else if (Files.exists(gradlew)) {
            command.addAll(List.of(gradlew.toString(), task));
        } else {
            logToConsole("No gradlew/gradlew.bat found in: " + root);
            logToConsole("Hint: Open the project folder that contains gradlew.bat (usually the project root)");
            return;
        }

        logToConsole("$ " + String.join(" ", command));
        updateStatus("Running: " + task);

        Thread t = new Thread(() -> {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(finalRoot.toFile());
            pb.redirectErrorStream(true);
            try {
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String finalLine = line;
                        Platform.runLater(() -> logToConsole(finalLine));
                    }
                }
                int code = p.waitFor();
                Platform.runLater(() -> {
                    logToConsole("Process finished with exit code: " + code);
                    updateStatus(code == 0 ? "Done: " + task : "Failed: " + task + " (exit " + code + ")");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    logToConsole("Failed to run gradle: " + ex.getMessage());
                    updateStatus("Failed: " + task);
                });
            }
        }, "gradle-runner");
        t.setDaemon(true);
        t.start();
    }

    private void createGradleJavaProject(Path parentDir, NewProjectConfig cfg) {
        Path targetDir = parentDir.resolve(cfg.projectName).normalize().toAbsolutePath();
        if (Files.exists(targetDir)) {
            logToConsole("Folder already exists: " + targetDir);
            updateStatus("Create failed: folder exists");
            return;
        }

        try {
            Files.createDirectories(targetDir);
        } catch (IOException e) {
            logToConsole("Failed to create folder: " + e.getMessage());
            updateStatus("Create failed");
            return;
        }

        // Используем wrapper текущей IDE, чтобы создать новый проект (gradle init) в targetDir
        Path ideGradlewBat = ideRoot.resolve("gradlew.bat");
        Path ideGradlew = ideRoot.resolve("gradlew");
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");

        List<String> cmd = new ArrayList<>();
        if (isWindows && Files.exists(ideGradlewBat)) {
            cmd.addAll(List.of(
                    "cmd.exe", "/c",
                    ideGradlewBat.toString(),
                    "-p", targetDir.toString(),
                    "init",
                    "--type", "java-application",
                    "--dsl", "kotlin",
                    "--project-name", cfg.projectName,
                    "--package", cfg.basePackage,
                    "--test-framework", "junit-jupiter",
                    "--use-defaults"
            ));
        } else if (Files.exists(ideGradlew)) {
            cmd.addAll(List.of(
                    ideGradlew.toString(),
                    "-p", targetDir.toString(),
                    "init",
                    "--type", "java-application",
                    "--dsl", "kotlin",
                    "--project-name", cfg.projectName,
                    "--package", cfg.basePackage,
                    "--test-framework", "junit-jupiter",
                    "--use-defaults"
            ));
        } else {
            logToConsole("IDE gradle wrapper not found in: " + ideRoot);
            updateStatus("Create failed");
            return;
        }

        logToConsole("$ " + String.join(" ", cmd));
        updateStatus("Creating project...");

        Thread t = new Thread(() -> {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(ideRoot.toFile());
            pb.redirectErrorStream(true);

            try {
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String finalLine = line;
                        Platform.runLater(() -> logToConsole(finalLine));
                    }
                }

                int code = p.waitFor();
                Platform.runLater(() -> {
                    logToConsole("Create project finished with exit code: " + code);
                    if (code == 0) {
                        // Fix: gradle init (8.13) may default toolchain to Java 21.
                        // We patch generated build to Java 17 to match user's JDK and avoid toolchain download timeouts.
                        patchGeneratedProjectToJava17(targetDir);

                        updateStatus("Project created: " + cfg.projectName);
                        if (cfg.openAfterCreate) {
                            setProjectRoot(targetDir);
                        }
                    } else {
                        updateStatus("Create failed (exit " + code + ")");
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    logToConsole("Failed to create project: " + ex.getMessage());
                    updateStatus("Create failed");
                });
            }
        }, "project-creator");
        t.setDaemon(true);
        t.start();
    }

    private void patchGeneratedProjectToJava17(Path projectDir) {
        // Gradle init for java-application creates either:
        // - app/build.gradle.kts (multi-project) OR
        // - build.gradle.kts (single project)
        List<Path> candidates = List.of(
                projectDir.resolve("app").resolve("build.gradle.kts"),
                projectDir.resolve("build.gradle.kts")
        );

        for (Path buildFile : candidates) {
            if (!Files.exists(buildFile)) continue;
            try {
                String s = Files.readString(buildFile, StandardCharsets.UTF_8);
                String updated = s;

                // Replace toolchain version if present
                updated = updated.replace("JavaLanguageVersion.of(21)", "JavaLanguageVersion.of(17)");
                updated = updated.replace("JavaLanguageVersion.of(22)", "JavaLanguageVersion.of(17)");

                // If toolchain block missing, add a simple one (Kotlin DSL)
                if (!updated.contains("toolchain") && updated.contains("java {")) {
                    updated = updated.replace(
                            "java {\n",
                            "java {\n    toolchain {\n        languageVersion = JavaLanguageVersion.of(17)\n    }\n"
                    );
                }

                if (!updated.equals(s)) {
                    Files.writeString(buildFile, updated, StandardCharsets.UTF_8,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    logToConsole("Patched toolchain to Java 17: " + buildFile);
                }
            } catch (Exception e) {
                logToConsole("Failed to patch generated project: " + e.getMessage());
            }
        }
    }


    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private void logToConsole(String text) {
        if (consoleArea == null) return;
        if (!Objects.equals(consoleArea.getText(), "") && !consoleArea.getText().endsWith("\n")) {
            consoleArea.appendText("\n");
        }
        consoleArea.appendText(text + "\n");
    }

    private Stage getStage() {
        if (projectTree == null) return null;
        return (Stage) projectTree.getScene().getWindow();
    }

    private static final class EditorTabData {
        private Path path;
        private final CodeArea editor;

        private EditorTabData(Path path, CodeArea editor) {
            this.path = path;
            this.editor = editor;
        }
    }

    private static final class NewProjectConfig {
        private final String projectName;
        private final String basePackage;
        private final boolean openAfterCreate;

        private NewProjectConfig(String projectName, String basePackage, boolean openAfterCreate) {
            this.projectName = projectName;
            this.basePackage = basePackage;
            this.openAfterCreate = openAfterCreate;
        }
    }

    private static final class NewJavaClassConfig {
        private final String packageName;
        private final String className;
        private final boolean openAfterCreate;

        private NewJavaClassConfig(String packageName, String className, boolean openAfterCreate) {
            this.packageName = packageName;
            this.className = className;
            this.openAfterCreate = openAfterCreate;
        }
    }
    
    private static final class RunTarget {
        private final String displayName;
        private final Path path;
        private final RunTargetType type;
        
        private RunTarget(String displayName, Path path, RunTargetType type) {
            this.displayName = displayName;
            this.path = path;
            this.type = type;
        }
        
        public String getDisplayName() { return displayName; }
        public Path getPath() { return path; }
        public RunTargetType getType() { return type; }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
    
    private enum RunTargetType {
        CURRENT_FILE,
        MAIN_CLASS
    }
}

