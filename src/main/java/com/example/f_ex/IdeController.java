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
import javafx.scene.control.Alert.AlertType;
import javafx.scene.text.Text;
import java.util.function.IntFunction;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
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
import org.fxmisc.richtext.NavigationActions;

public class IdeController {
    @FXML private TreeView<Path> projectTree;
    @FXML private TabPane editorTabs;
    @FXML private TextArea consoleArea;
    @FXML private ListView<Problem> problemsList;
    @FXML private ListView<SearchHit> searchResultsList;
    @FXML private VBox bottomPanel;
    @FXML private TabPane bottomTabs;
    @FXML private TextArea debugArea;
    @FXML private ListView<DebugParsers.ThreadItem> debugThreadsList;
    @FXML private ListView<DebugParsers.FrameItem> debugStackList;
    @FXML private ListView<DebugParsers.VarItem> debugVarsList;
    @FXML private Label rootLabel;
    @FXML private Label statusLabel;
    @FXML private Label cursorPositionLabel;
    @FXML private ComboBox<RunTarget> runTargetComboBox;
    @FXML private Label errorCountLabel;
    @FXML private Label warningCountLabel;

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

    private javafx.animation.PauseTransition diagnosticsTimer;
    private final Map<Path, List<Problem>> problemsByFile = new ConcurrentHashMap<>();
    private final ProjectModelResolver modelResolver = new ProjectModelResolver();
    private volatile ProjectModelResolver.ProjectModel projectModel = new ProjectModelResolver.ProjectModel(List.of(), List.of());
    private final RecentFilesManager recentFiles = new RecentFilesManager(25);
    private final RefactorRenameService renameService = new RefactorRenameService();
    private final RefactorUndoManager undoManager = new RefactorUndoManager();
    private final FileOperationsService fileOps = new FileOperationsService();
    private final ExePackager exePackager = new ExePackager(this::logToConsole, this::updateStatus);
    private final Map<Path, Set<Integer>> breakpoints = new ConcurrentHashMap<>();
    private final DebugSession debugSession = new DebugSession(this::appendDebugLine);
    private WatchService fileWatcher;
    private Thread fileWatcherThread;
    private javafx.animation.PauseTransition treeRefreshTimer;

    private static final class SearchHit {
        private final Path file;
        private final int line;
        private final String preview;

        private SearchHit(Path file, int line, String preview) {
            this.file = file;
            this.line = line;
            this.preview = preview;
        }

        @Override
        public String toString() {
            String fn = file != null && file.getFileName() != null ? file.getFileName().toString() : String.valueOf(file);
            return fn + ":" + line + "  " + preview;
        }
    }

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

        if (debugThreadsList != null) {
            debugThreadsList.setOnMouseClicked(e -> {
                if (e.getClickCount() != 1) return;
                DebugParsers.ThreadItem t = debugThreadsList.getSelectionModel().getSelectedItem();
                if (t == null) return;
                debugSession.request("thread " + t.id, lines -> refreshDebugStackAndVars());
            });
        }
        if (debugStackList != null) {
            debugStackList.setOnMouseClicked(e -> {
                if (e.getClickCount() != 1) return;
                DebugParsers.FrameItem f = debugStackList.getSelectionModel().getSelectedItem();
                if (f == null) return;
                debugSession.request("frame " + f.index, lines -> refreshDebugVars());
            });
        }
    }

    @FXML public void onDebugContinue() { sendJdb("cont"); scheduleDebugRefresh(); }
    @FXML public void onDebugStepOver() { sendJdb("next"); scheduleDebugRefresh(); }
    @FXML public void onDebugStepInto() { sendJdb("step"); scheduleDebugRefresh(); }
    @FXML public void onDebugStepOut() { sendJdb("step up"); scheduleDebugRefresh(); }
    @FXML public void onDebugPause() { sendJdb("suspend"); scheduleDebugRefresh(); }
    @FXML public void onDebugStop() { debugSession.stop(); }
    @FXML public void onDebugRefresh() { refreshDebugAll(); }

    @FXML
    public void onShowBreakpoints() {
        StringBuilder sb = new StringBuilder();
        for (var e : breakpoints.entrySet()) {
            Path f = e.getKey();
            for (Integer ln : e.getValue()) {
                sb.append(f.getFileName()).append(":").append(ln).append("\n");
            }
        }
        Alert a = new Alert(AlertType.INFORMATION);
        a.setTitle("Breakpoints");
        a.setHeaderText("Active breakpoints");
        a.setContentText(sb.length() == 0 ? "(none)" : sb.toString());
        a.showAndWait();
    }

    private static final class Problem {
        private final Path file;
        private final int line; // 1-based
        private final String kind; // error|warning|note
        private final String message;

        private Problem(Path file, int line, String kind, String message) {
            this.file = file;
            this.line = line;
            this.kind = kind;
            this.message = message;
        }

        @Override
        public String toString() {
            String fn = file != null && file.getFileName() != null ? file.getFileName().toString() : String.valueOf(file);
            return fn + ":" + line + " [" + kind + "] " + message;
        }
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

        if (searchResultsList != null) {
            searchResultsList.setOnMouseClicked(e -> {
                if (e.getClickCount() != 2) return;
                SearchHit hit = searchResultsList.getSelectionModel().getSelectedItem();
                if (hit == null) return;
                openFileAndGoTo(hit.file, hit.line);
            });
        }

        if (problemsList != null) {
            problemsList.setOnMouseClicked(e -> {
                if (e.getClickCount() != 2) return;
                Problem p = problemsList.getSelectionModel().getSelectedItem();
                if (p == null || p.file == null) return;
                openFileAndGoTo(p.file, p.line);
            });

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
                javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
                c.putString(p.toString());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
            });

            problemsList.setContextMenu(cm);
        }
    }

    private void runQuickFix(Problem p) {
        if (p == null || p.file == null) return;

        String msg = p.message == null ? "" : p.message;
        if ("warning".equalsIgnoreCase(p.kind) && msg.toLowerCase().contains("import") && msg.toLowerCase().contains("never used")) {
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

        if ("error".equalsIgnoreCase(p.kind) && msg.toLowerCase().contains("cannot find symbol")) {
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

    private static String extractUnusedImportFqn(String message) {
        if (message == null) return null;
        Matcher m = Pattern.compile("\\bThe import\\s+([\\w\\.]+)\\s+is never used\\b").matcher(message);
        if (m.find()) return m.group(1);
        m = Pattern.compile("\\bimport\\s+([\\w\\.]+)\\b").matcher(message);
        if (m.find()) return m.group(1);
        return null;
    }

    private boolean removeUnusedImport(Path file, String fqn) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String needle = "import " + fqn + ";";
            boolean changed = false;
            List<String> out = new ArrayList<>(lines.size());
            for (String l : lines) {
                if (!changed && l.trim().equals(needle)) {
                    changed = true;
                    continue;
                }
                out.add(l);
            }
            if (!changed) return false;
            Files.write(file, out, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean addImport(Path file, String fqn) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String imp = "import " + fqn + ";";
            for (String l : lines) {
                if (l.trim().equals(imp)) return true;
            }

            int insertAt = 0;
            for (int i = 0; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("package ")) insertAt = i + 1;
            }
            while (insertAt < lines.size() && lines.get(insertAt).trim().isEmpty()) insertAt++;
            while (insertAt < lines.size() && lines.get(insertAt).trim().startsWith("import ")) insertAt++;

            List<String> out = new ArrayList<>(lines.size() + 2);
            out.addAll(lines.subList(0, insertAt));
            out.add(imp);
            out.addAll(lines.subList(insertAt, lines.size()));
            Files.write(file, out, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshDiagnosticsForOpenTabIfAny(Path file) {
        if (file == null) return;
        Tab tab = openTabsByPath.get(file.normalize().toAbsolutePath());
        if (tab == null) return;
        EditorTabData data = (EditorTabData) tab.getUserData();
        if (data == null || data.editor == null) return;
        scheduleDiagnostics(file, data.editor.getText());
    }

    @FXML
    public void onGoToClass() {
        goToFromIndex(Set.of(CodeIndexer.CodeElementType.CLASS, CodeIndexer.CodeElementType.INTERFACE), "Go to Class");
    }

    @FXML
    public void onGoToSymbol() {
        goToFromIndex(Set.of(CodeIndexer.CodeElementType.CLASS, CodeIndexer.CodeElementType.INTERFACE, CodeIndexer.CodeElementType.METHOD), "Go to Symbol");
    }

    @FXML
    public void onActionSearch() {
        List<ActionItem> actions = buildActions();

        Dialog<ActionItem> dialog = new Dialog<>();
        dialog.setTitle("Action Search");
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField filter = new TextField();
        filter.setPromptText("Type action name...");
        ListView<ActionItem> list = new ListView<>();

        filter.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.trim().toLowerCase();
            if (q.isEmpty()) {
                list.getItems().setAll(actions.stream().limit(40).toList());
                return;
            }
            List<ActionItem> found = actions.stream()
                    .filter(x -> x.name.toLowerCase().contains(q))
                    .limit(200)
                    .toList();
            list.getItems().setAll(found);
        });

        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) dialog.setResult(list.getSelectionModel().getSelectedItem());
        });

        VBox box = new VBox(8, filter, list);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);

        dialog.setResultConverter(bt -> bt == ButtonType.OK ? list.getSelectionModel().getSelectedItem() : null);

        Platform.runLater(filter::requestFocus);
        list.getItems().setAll(actions.stream().limit(40).toList());

        Optional<ActionItem> picked = dialog.showAndWait();
        picked.ifPresent(a -> a.run.run());
    }

    @FXML
    public void onFindUsages() {
        Tab tab = editorTabs != null ? editorTabs.getSelectionModel().getSelectedItem() : null;
        EditorTabData data = tab != null ? (EditorTabData) tab.getUserData() : null;
        String initial = "";
        if (data != null && data.editor != null) {
            initial = wordAt(data.editor.getText(), data.editor.getCaretPosition());
            if (initial == null) initial = "";
        }

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
            Path found = findFileByName(projectRoot, s);
            if (found != null) openFileInEditor(found);
            else updateStatus("File not found");
        });
    }

    @FXML
    public void onRecentFiles() {
        List<Path> items = recentFiles.list();
        if (items.isEmpty()) {
            updateStatus("No recent files");
            return;
        }
        ChoiceDialog<Path> d = new ChoiceDialog<>(items.get(0), items);
        d.setTitle("Recent Files");
        d.setHeaderText(null);
        d.setContentText("Open:");
        d.showAndWait().ifPresent(this::openFileInEditor);
    }

    @FXML
    public void onRename() {
        Tab tab = editorTabs != null ? editorTabs.getSelectionModel().getSelectedItem() : null;
        EditorTabData data = tab != null ? (EditorTabData) tab.getUserData() : null;
        if (data == null || data.path == null || data.editor == null) {
            updateStatus("No editor file selected");
            return;
        }
        String from = wordAt(data.editor.getText(), data.editor.getCaretPosition());
        if (from == null || from.isBlank()) {
            updateStatus("Place caret on identifier");
            return;
        }
        Dialog<Map<String, Object>> d = new Dialog<>();
        d.setTitle("Rename");
        d.setHeaderText("Rename identifier");
        ButtonType ok = new ButtonType("Rename", ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        TextField newNameField = new TextField(from);
        CheckBox wholeProject = new CheckBox("Whole project");
        wholeProject.setSelected(false);

        grid.add(new Label("New name:"), 0, 0);
        grid.add(newNameField, 1, 0);
        grid.add(wholeProject, 1, 1);

        d.getDialogPane().setContent(grid);
        d.setResultConverter(bt -> {
            if (bt != ok) return null;
            Map<String, Object> m = new HashMap<>();
            m.put("to", newNameField.getText());
            m.put("project", wholeProject.isSelected());
            return m;
        });

        d.showAndWait().ifPresent(m -> {
            String newName = m.get("to") == null ? "" : m.get("to").toString().trim();
            boolean proj = Boolean.TRUE.equals(m.get("project"));
            if (newName.isEmpty()) return;

            boolean renameFileToo = false;
            Path fileForRename = data.path;
            if (!proj && fileForRename != null && fileForRename.toString().endsWith(".java")) {
                String base = fileForRename.getFileName() != null ? fileForRename.getFileName().toString() : "";
                if (base.endsWith(".java")) base = base.substring(0, base.length() - 5);
                if (base.equals(from) && newName.matches("[A-Za-z_$][A-Za-z\\d_$]*")) {
                    Alert ask = new Alert(AlertType.CONFIRMATION);
                    ask.setTitle("Rename File");
                    ask.setHeaderText(null);
                    ask.setContentText("Also rename file to " + newName + ".java ?");
                    Optional<ButtonType> ans = ask.showAndWait();
                    renameFileToo = ans.isPresent() && ans.get() == ButtonType.OK;
                }
            }

            List<Path> files = proj ? listAllJavaFiles(projectRoot) : List.of(data.path);
            RefactorRenameService.RenamePlan plan = renameService.planRename(files, from, newName);
            if (plan.changes.isEmpty()) {
                updateStatus("Nothing to rename");
                return;
            }

            if (!confirmRenamePlan(plan)) return;

            undoManager.rememberBackup(plan);
            RefactorRenameService.RenameResult res = renameService.applyPlan(plan);
            updateStatus("Rename: " + res.occurrences + " occurrences in " + res.filesChanged + " files");
            reopenAllOpenEditors();

            if (renameFileToo && fileForRename != null) {
                try {
                    Path oldAbs = fileForRename.normalize().toAbsolutePath();
                    Path newAbs = fileOps.renameFile(oldAbs, newName + ".java").normalize().toAbsolutePath();
                    Tab opened = openTabsByPath.remove(oldAbs);
                    if (opened != null) {
                        openTabsByPath.put(newAbs, opened);
                        opened.setText(newAbs.getFileName() != null ? newAbs.getFileName().toString() : newAbs.toString());
                        EditorTabData td = (EditorTabData) opened.getUserData();
                        if (td != null) td.path = newAbs;
                    }
                    refreshTreeIfUnderRoot(newAbs);
                    updateStatus("Renamed file to: " + newAbs.getFileName());
                } catch (Exception e) {
                    updateStatus("File rename failed: " + e.getMessage());
                }
            }
        });
    }

    private boolean confirmRenamePlan(RefactorRenameService.RenamePlan plan) {
        int files = plan.changes.size();
        int occ = plan.changes.stream().mapToInt(c -> c.occurrences).sum();
        StringBuilder sb = new StringBuilder();
        sb.append("Rename ").append(plan.from).append(" -> ").append(plan.to).append("\n");
        sb.append("Files: ").append(files).append(", occurrences: ").append(occ).append("\n\n");
        int shown = 0;
        for (RefactorRenameService.FileChange ch : plan.changes) {
            if (shown >= 20) break;
            sb.append(ch.file.getFileName()).append(" (").append(ch.occurrences).append(")\n");
            shown++;
        }
        if (files > shown) sb.append("... +").append(files - shown).append(" more\n");

        Alert a = new Alert(AlertType.CONFIRMATION);
        a.setTitle("Rename Preview");
        a.setHeaderText(null);
        a.setContentText(sb.toString());
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.OK;
    }

    @FXML
    public void onUndoRefactor() {
        int restored = undoManager.undo();
        if (restored == 0) {
            updateStatus("Nothing to undo");
            return;
        }
        updateStatus("Undo: restored " + restored + " files");
        reopenAllOpenEditors();
    }

    @FXML
    public void onRenameFile() {
        Path target = null;

        Tab tab = editorTabs != null ? editorTabs.getSelectionModel().getSelectedItem() : null;
        EditorTabData data = tab != null ? (EditorTabData) tab.getUserData() : null;
        if (data != null && data.path != null) {
            target = data.path;
        } else if (projectTree != null && projectTree.getSelectionModel().getSelectedItem() != null) {
            target = projectTree.getSelectionModel().getSelectedItem().getValue();
        }

        if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
            updateStatus("Select a file to rename");
            return;
        }

        String current = target.getFileName() != null ? target.getFileName().toString() : target.toString();
        final Path targetFinal = target;
        TextInputDialog d = new TextInputDialog(current);
        d.setTitle("Rename File");
        d.setHeaderText(null);
        d.setContentText("New file name:");
        d.showAndWait().ifPresent(newName -> {
            try {
                Path oldAbs = targetFinal.normalize().toAbsolutePath();
                Path newAbs = fileOps.renameFile(oldAbs, newName).normalize().toAbsolutePath();

                Tab opened = openTabsByPath.remove(oldAbs);
                if (opened != null) {
                    openTabsByPath.put(newAbs, opened);
                    opened.setText(newAbs.getFileName() != null ? newAbs.getFileName().toString() : newAbs.toString());
                    EditorTabData td = (EditorTabData) opened.getUserData();
                    if (td != null) td.path = newAbs;
                }

                // Если это Java-файл и имя класса совпадало с именем файла — переименуем класс внутри
                String oldName = current;
                String newFile = newAbs.getFileName() != null ? newAbs.getFileName().toString() : null;
                if (oldName != null && newFile != null && oldName.endsWith(".java") && newFile.endsWith(".java")) {
                    String oldBase = oldName.substring(0, oldName.length() - 5);
                    String newBase = newFile.substring(0, newFile.length() - 5);
                    if (!oldBase.equals(newBase) && newBase.matches("[A-Za-z_$][A-Za-z\\d_$]*")) {
                        try {
                            String src = Files.readString(newAbs, StandardCharsets.UTF_8);
                            if (src.contains("class " + oldBase) || src.contains("interface " + oldBase) || src.contains("enum " + oldBase) || src.contains("record " + oldBase)) {
                                RefactorRenameService.RenamePlan plan = renameService.planRename(List.of(newAbs), oldBase, newBase);
                                if (!plan.changes.isEmpty()) {
                                    undoManager.rememberBackup(plan);
                                    renameService.applyPlan(plan);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }

                refreshTreeIfUnderRoot(newAbs);
                updateStatus("Renamed: " + current + " -> " + newAbs.getFileName());
            } catch (Exception e) {
                updateStatus("Rename failed: " + e.getMessage());
            }
        });
    }

    private List<Path> listAllJavaFiles(Path root) {
        if (root == null) return List.of();
        try {
            return Files.walk(root, 20)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !CodeIndexer.shouldHidePath(p.getParent()))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private void reopenAllOpenEditors() {
        List<Path> toReopen = new ArrayList<>();
        for (Tab t : new ArrayList<>(editorTabs.getTabs())) {
            EditorTabData d = (EditorTabData) t.getUserData();
            if (d != null && d.path != null && d.editor != null) {
                toReopen.add(d.path);
            }
        }
        editorTabs.getTabs().removeIf(t -> {
            EditorTabData d = (EditorTabData) t.getUserData();
            return d != null && d.editor != null;
        });
        for (Path p : toReopen) {
            openTabsByPath.remove(p.normalize().toAbsolutePath());
        }
        for (Path p : toReopen) openFileInEditor(p);
    }

    private static Path findFileByName(Path root, String needleLower) {
        try {
            return Files.walk(root, 20)
                    .filter(Files::isRegularFile)
                    .filter(p -> !CodeIndexer.shouldHidePath(p.getParent()))
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().toLowerCase().contains(needleLower))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static final class ActionItem {
        private final String name;
        private final Runnable run;

        private ActionItem(String name, Runnable run) {
            this.name = name;
            this.run = run;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private List<ActionItem> buildActions() {
        List<ActionItem> a = new ArrayList<>();
        a.add(new ActionItem("Go to Class...", this::onGoToClass));
        a.add(new ActionItem("Go to Symbol...", this::onGoToSymbol));
        a.add(new ActionItem("Go to File...", this::onGoToFile));
        a.add(new ActionItem("Recent Files...", this::onRecentFiles));
        a.add(new ActionItem("Find Usages...", this::onFindUsages));
        a.add(new ActionItem("Find in Files...", this::onFindInFiles));
        a.add(new ActionItem("Toggle Theme", this::onToggleTheme));
        a.add(new ActionItem("Toggle Bottom Panel", this::onToggleBottomPanel));
        a.add(new ActionItem("Preferences...", this::onSettings));
        a.add(new ActionItem("Run Selected", this::onRunSelected));
        a.add(new ActionItem("Debug Project", this::onDebugProject));
        a.add(new ActionItem("Rename...", this::onRename));
        a.add(new ActionItem("Rename File...", this::onRenameFile));
        a.add(new ActionItem("Undo Refactor", this::onUndoRefactor));
        a.add(new ActionItem("Open Project...", this::onOpenFolder));
        a.add(new ActionItem("Open File...", this::onOpenFile));
        a.add(new ActionItem("Save", this::onSave));
        a.add(new ActionItem("Save As...", this::onSaveAs));
        a.add(new ActionItem("Clone from GitHub...", this::onCloneFromGitHub));
        a.add(new ActionItem("Gradle: build", this::onGradleBuild));
        a.add(new ActionItem("Gradle: run", this::onGradleRun));
        a.add(new ActionItem("Gradle: test", this::onGradleTest));
        a.add(new ActionItem("Gradle: clean", this::onGradleClean));
        a.add(new ActionItem("Package as EXE...", this::onPackageAsExe));
        return a;
    }

    private void goToFromIndex(Set<CodeIndexer.CodeElementType> types, String title) {
        if (codeIndexer == null) {
            updateStatus("Index not ready");
            return;
        }

        Dialog<CodeIndexer.CodeElement> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField filter = new TextField();
        filter.setPromptText("Type to search...");
        ListView<CodeIndexer.CodeElement> list = new ListView<>();

        filter.textProperty().addListener((o, a, b) -> {
            String q = b == null ? "" : b.trim();
            if (q.isEmpty()) {
                list.getItems().clear();
                return;
            }
            List<CodeIndexer.CodeElement> found = codeIndexer.findCompletions(q).stream()
                    .filter(e -> types.contains(e.getType()))
                    .limit(200)
                    .toList();
            list.getItems().setAll(found);
        });

        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) dialog.setResult(list.getSelectionModel().getSelectedItem());
        });

        VBox box = new VBox(8, filter, list);
        box.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(box);

        dialog.setResultConverter(bt -> bt == ButtonType.OK ? list.getSelectionModel().getSelectedItem() : null);

        Optional<CodeIndexer.CodeElement> picked = dialog.showAndWait();
        picked.ifPresent(e -> openFileAndGoTo(e.getFile(), e.getLine()));
    }

    private void findUsagesInProject(String symbol) {
        if (projectRoot == null) {
            updateStatus("No project root set");
            return;
        }
        if (searchResultsList == null) return;

        if (bottomPanel != null) {
            bottomPanel.setVisible(true);
            bottomPanel.setManaged(true);
        }
        if (bottomTabs != null) bottomTabs.getSelectionModel().select(2); // Search tab index

        Pattern pat = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
        searchResultsList.getItems().clear();
        updateStatus("Searching usages: " + symbol);

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
                                    if (!pat.matcher(ln).find()) continue;
                                    String prev = ln.trim();
                                    if (prev.length() > 160) prev = prev.substring(0, 160) + "...";
                                    hits.add(new SearchHit(file, i + 1, prev));
                                    if (hits.size() >= 2000) return;
                                }
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
            Platform.runLater(() -> {
                searchResultsList.getItems().setAll(hits);
                updateStatus("Usages: " + hits.size());
            });
        }, "find-usages");
        t.setDaemon(true);
        t.start();
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

    @FXML
    public void onDebugProject() {
        RunTarget selected = runTargetComboBox != null ? runTargetComboBox.getValue() : null;
        if (selected == null) {
            refreshRunTargets();
            selected = runTargetComboBox != null ? runTargetComboBox.getValue() : null;
        }
        if (selected == null) {
            updateStatus("No run target selected");
            return;
        }
        logToConsole("[DEBUG] Starting (JDWP): " + selected.getDisplayName());
        debugSelectedTarget(selected);
        Platform.runLater(() -> {
            if (bottomTabs != null) bottomTabs.getSelectionModel().select(3);
        });
        startJdbAttach();
    }

    private void startJdbAttach() {
        debugSession.connectSocket("localhost", 5005);
        debugSession.send("suspend");
        applyBreakpointsToJdb();
        RunTarget selected = runTargetComboBox != null ? runTargetComboBox.getValue() : null;
        if (selected != null && selected.getPath() != null) {
            String cls = inferClassName(selected.getPath());
            if (cls != null && !cls.isBlank()) debugSession.send("stop in " + cls + ".main");
        }
        refreshDebugAll();
    }

    private void appendDebugLine(String s) {
        if (debugArea == null) return;
        debugArea.appendText(s + "\n");
    }

    private void sendJdb(String cmd) {
        debugSession.send(cmd);
    }

    private void applyBreakpointsToJdb() {
        RunTarget selected = runTargetComboBox != null ? runTargetComboBox.getValue() : null;
        if (selected == null) return;
        Path javaFile = selected.getPath();
        if (javaFile == null) return;

        String cls = inferClassName(javaFile);
        if (cls == null || cls.isBlank()) return;

        Set<Integer> lines = breakpoints.getOrDefault(javaFile, Set.of());
        for (Integer ln : lines) {
            debugSession.send("stop at " + cls + ":" + ln);
        }
    }

    private void refreshDebugAll() {
        refreshDebugThreads();
        refreshDebugStackAndVars();
    }

    private void refreshDebugThreads() {
        if (debugThreadsList == null) return;
        debugSession.request("threads", lines -> debugThreadsList.getItems().setAll(DebugParsers.parseThreads(lines)));
    }

    private void refreshDebugStackAndVars() {
        refreshDebugStack();
        refreshDebugVars();
    }

    private void refreshDebugStack() {
        if (debugStackList == null) return;
        debugSession.request("where", lines -> debugStackList.getItems().setAll(DebugParsers.parseWhere(lines)));
    }

    private void refreshDebugVars() {
        if (debugVarsList == null) return;
        debugSession.request("locals", lines -> debugVarsList.getItems().setAll(DebugParsers.parseLocals(lines)));
    }

    private void scheduleDebugRefresh() {
        javafx.animation.PauseTransition pt = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
        pt.setOnFinished(e -> refreshDebugAll());
        pt.play();
    }

    private String inferClassName(Path javaFile) {
        try {
            String content = Files.readString(javaFile, StandardCharsets.UTF_8);
            Matcher pm = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;").matcher(content);
            String pkg = pm.find() ? pm.group(1) : "";
            String name = javaFile.getFileName() != null ? javaFile.getFileName().toString() : "";
            if (name.endsWith(".java")) name = name.substring(0, name.length() - 5);
            return pkg.isEmpty() ? name : (pkg + "." + name);
        } catch (Exception e) {
            return null;
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

    private void debugSelectedTarget(RunTarget target) {
        if (target == null || target.getPath() == null) return;
        if (target.getType() == RunTargetType.CURRENT_FILE || target.getType() == RunTargetType.MAIN_CLASS) {
            debugJavaFile(target.getPath());
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

    private void debugJavaFile(Path javaFile) {
        if (projectRoot == null) {
            updateStatus("No project root set");
            return;
        }

        ProjectDetector.ProjectType type = ProjectDetector.detectProjectType(projectRoot);

        if (type == ProjectDetector.ProjectType.INTELLIJ_IDEA || type == ProjectDetector.ProjectType.JAVA) {
            if (type == ProjectDetector.ProjectType.INTELLIJ_IDEA) {
                IntelliJProjectRunner runner = new IntelliJProjectRunner(
                        this::logToConsole,
                        () -> updateStatus("Debug: " + javaFile.getFileName()),
                        p -> {
                            currentRunningProcess = p;
                            if (p != null) {
                                try { processInput = p.getOutputStream(); } catch (Exception e) { processInput = null; }
                            } else {
                                processInput = null;
                            }
                        }
                );
                runner.debugFile(projectRoot, javaFile);
            } else {
                JavaProjectRunner runner = new JavaProjectRunner(
                        this::logToConsole,
                        () -> updateStatus("Debug: " + javaFile.getFileName()),
                        p -> {
                            currentRunningProcess = p;
                            if (p != null) {
                                try { processInput = p.getOutputStream(); } catch (Exception e) { processInput = null; }
                            } else {
                                processInput = null;
                            }
                        }
                );
                runner.debugFile(projectRoot, javaFile);
            }
            logToConsole("[DEBUG] Attach debugger to localhost:5005");
        } else {
            logToConsole("[DEBUG] For Gradle/Maven projects: JDWP debug is not wired yet.");
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
    public void onPackageAsExe() {
        if (projectRoot == null) {
            updateStatus("No project root set");
            return;
        }
        ProjectDetector.ProjectType type = ProjectDetector.detectProjectType(projectRoot);
        if (type != ProjectDetector.ProjectType.GRADLE && type != ProjectDetector.ProjectType.MAVEN) {
            updateStatus("EXE packaging supported only for Gradle/Maven projects");
            return;
        }
        exePackager.packageAsExe(projectRoot, type);
    }

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
    public void onToggleBottomPanel() {
        if (bottomPanel == null) return;
        boolean show = !bottomPanel.isVisible();
        bottomPanel.setVisible(show);
        bottomPanel.setManaged(show);
        if (show) {
            bottomPanel.setPrefHeight(260);
        } else {
            bottomPanel.setPrefHeight(0);
        }
        updateStatus(show ? "Bottom panel shown" : "Bottom panel hidden");
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
        
        stopFileWatcher();
        projectRoot = actualRoot;
        rootLabel.setText(projectRoot.toString());
        projectTree.setRoot(buildFileTreeRoot(projectRoot));
        projectTree.getRoot().setExpanded(true);
        logToConsole("Project root set to: " + projectRoot);
        
        // Показываем тип проекта
        ProjectDetector.ProjectType detectedType = ProjectDetector.detectProjectType(projectRoot);
        logToConsole("Project type: " + detectedType);

        Thread pm = new Thread(() -> {
            ProjectModelResolver.ProjectModel m = modelResolver.resolve(projectRoot, detectedType);
            projectModel = m;
            Platform.runLater(() -> logToConsole("Project model: srcRoots=" + m.sourceRoots.size() + ", cp=" + m.classpath.size()));
        }, "project-model");
        pm.setDaemon(true);
        pm.start();
        
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
        
        // Запускаем отслеживание изменений файловой системы
        startFileWatcher();
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
            editor.setParagraphGraphicFactory(createGutter(editor, abs));
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
            editor.setParagraphGraphicFactory(createGutter(editor, abs));
        }

        editor.textProperty().addListener((obs, old, newVal) -> applyHighlighting(editor));
        editor.caretPositionProperty().addListener((obs, old, pos) -> updateCursorPosition(editor));

        editor.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (!e.isControlDown()) return;
            int pos = editor.hit(e.getX(), e.getY()).getInsertionIndex();
            String word = wordAt(editor.getText(), pos);
            if (word == null || word.isBlank()) return;
            goToDefinition(word);
            e.consume();
        });

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

        // Реалтайм диагностика (javac) для Java файлов
        if (abs.toString().toLowerCase().endsWith(".java")) {
            editor.textProperty().addListener((obs, oldVal, newVal) -> scheduleDiagnostics(abs, newVal));
            // Первичная диагностика при открытии
            scheduleDiagnostics(abs, editor.getText());
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
        recentFiles.markOpened(abs);
    }

    private static String wordAt(String text, int pos) {
        if (text == null || text.isEmpty()) return null;
        if (pos < 0) pos = 0;
        if (pos > text.length()) pos = text.length();

        int l = pos;
        while (l > 0 && Character.isJavaIdentifierPart(text.charAt(l - 1))) l--;
        int r = pos;
        while (r < text.length() && Character.isJavaIdentifierPart(text.charAt(r))) r++;
        if (r <= l) return null;
        return text.substring(l, r);
    }

    private void goToDefinition(String symbol) {
        if (codeIndexer == null || symbol == null || symbol.isBlank()) return;
        List<CodeIndexer.CodeElement> hits = codeIndexer.findCompletions(symbol).stream()
                .filter(e -> e.getName().equals(symbol))
                .toList();
        if (hits.isEmpty()) {
            updateStatus("Definition not found: " + symbol);
            return;
        }
        CodeIndexer.CodeElement best = hits.get(0);
        openFileAndGoTo(best.getFile(), best.getLine());
    }

    private void openFileAndGoTo(Path file, int line) {
        if (file == null) return;
        openFileInEditor(file);
        Platform.runLater(() -> {
            Tab tab = openTabsByPath.get(file.normalize().toAbsolutePath());
            if (tab == null) return;
            EditorTabData data = (EditorTabData) tab.getUserData();
            if (data == null || data.editor == null) return;
            int ln = Math.max(1, line);
            int para = ln - 1;
            if (para >= data.editor.getParagraphs().size()) para = data.editor.getParagraphs().size() - 1;
            data.editor.moveTo(para, 0);
            data.editor.requestFocus();
        });
    }

    private IntFunction<Node> createGutter(CodeArea editor, Path file) {
        IntFunction<Node> base = LineNumberFactory.get(editor);
        return line -> {
            Node lnNode = base.apply(line);
            int ln = line + 1;

            Text dot = new Text("●");
            dot.setStyle("-fx-fill: transparent;");
            if (file != null && breakpoints.getOrDefault(file, Set.of()).contains(ln)) {
                dot.setStyle("-fx-fill: #e51400;");
            }

            HBox box = new HBox(6, dot, lnNode);
            box.setOnMouseClicked(e -> {
                if (file == null) return;
                toggleBreakpoint(file, ln);
                dot.setStyle(breakpoints.getOrDefault(file, Set.of()).contains(ln) ? "-fx-fill: #e51400;" : "-fx-fill: transparent;");
            });
            return box;
        };
    }

    private void toggleBreakpoint(Path file, int line) {
        breakpoints.compute(file, (k, v) -> {
            Set<Integer> s = (v == null) ? new HashSet<>() : new HashSet<>(v);
            if (!s.add(line)) s.remove(line);
            return s;
        });
        updateStatus("Breakpoint " + (breakpoints.getOrDefault(file, Set.of()).contains(line) ? "set" : "removed") + ": " + file.getFileName() + ":" + line);
        if (debugSession.isReady()) applyBreakpointsToJdb();
    }

    private void scheduleDiagnostics(Path file, String content) {
        if (file == null) return;
        if (diagnosticsTimer != null) diagnosticsTimer.stop();
        diagnosticsTimer = new javafx.animation.PauseTransition(javafx.util.Duration.millis(800));
        diagnosticsTimer.setOnFinished(e -> runDiagnosticsInBackground(file, content));
        diagnosticsTimer.play();
    }

    private void runDiagnosticsInBackground(Path file, String content) {
        if (file == null) return;
        // Если projectRoot не задан, всё равно попробуем на одном файле
        Thread t = new Thread(() -> {
            List<Problem> problems = compileWithJavacAndParseProblems(file, content);
            problemsByFile.put(file, problems);
            Platform.runLater(() -> updateProblemsPanel());
        }, "javac-diagnostics");
        t.setDaemon(true);
        t.start();
    }

    private void updateProblemsPanel() {
        if (problemsList == null) return;
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
        items.sort((a, b) -> {
            int ka = "error".equalsIgnoreCase(a.kind) ? 0 : "warning".equalsIgnoreCase(a.kind) ? 1 : 2;
            int kb = "error".equalsIgnoreCase(b.kind) ? 0 : "warning".equalsIgnoreCase(b.kind) ? 1 : 2;
            if (ka != kb) return Integer.compare(ka, kb);
            String fa = a.file != null ? a.file.toString() : "";
            String fb = b.file != null ? b.file.toString() : "";
            int fc = fa.compareToIgnoreCase(fb);
            if (fc != 0) return fc;
            return Integer.compare(a.line, b.line);
        });
        problemsList.getItems().setAll(items);
        if (errorCountLabel != null) errorCountLabel.setText(String.valueOf(err));
        if (warningCountLabel != null) warningCountLabel.setText(String.valueOf(warn));
        updateStatus((err == 0 && warn == 0) ? "Ready" : ("⛔ " + err + "  ⚠ " + warn));
    }

    private List<Problem> compileWithJavacAndParseProblems(Path file, String content) {
        List<Problem> result = new ArrayList<>();
        try {
            Path tmpDir = Files.createTempDirectory("f_ex_javac_");
            tmpDir.toFile().deleteOnExit();

            Path tmpFile = tmpDir.resolve(file.getFileName().toString());
            Files.writeString(tmpFile, content == null ? "" : content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            List<String> cmd = new ArrayList<>();
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            if (isWindows) {
                cmd.addAll(List.of("cmd.exe", "/c", "javac"));
            } else {
                cmd.add("javac");
            }
            cmd.addAll(List.of(
                    "-encoding", "UTF-8",
                    "-Xlint:all",
                    "-proc:none",
                    "-d", tmpDir.toString()
            ));

            if (projectModel != null && !projectModel.sourceRoots.isEmpty()) {
                cmd.add("-sourcepath");
                cmd.add(joinPaths(projectModel.sourceRoots));
            }
            if (projectModel != null && !projectModel.classpath.isEmpty()) {
                cmd.add("-cp");
                cmd.add(joinPaths(projectModel.classpath));
            }
            cmd.add(tmpFile.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            p.waitFor();

            // javac обычно печатает блоками:
            // <file>:<line>: <kind>: <message>
            // <source line>
            // <caret line>
            Pattern head = Pattern.compile("^(.*?):(\\d+):\\s*(error|warning|note):\\s*(.*)$");
            String[] lines = out.toString().split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String raw = lines[i].trim();
                Matcher m = head.matcher(raw);
                if (!m.matches()) continue;
                int ln = safeParseInt(m.group(2));
                String kind = m.group(3);
                String msg = m.group(4);
                // добираем продолжение сообщения, если javac переносит строки (редко, но бывает)
                while (i + 1 < lines.length) {
                    String next = lines[i + 1];
                    String nextTrim = next.trim();
                    if (nextTrim.isEmpty()) {
                        i++;
                        break;
                    }
                    if (head.matcher(nextTrim).matches()) break;
                    // пропускаем строки с исходником и ^, но если это текст — добавим в msg
                    boolean looksLikeCaret = nextTrim.chars().allMatch(ch -> ch == '^');
                    if (!looksLikeCaret && !nextTrim.equals(lines[i].trim())) {
                        if (!nextTrim.startsWith(tmpFile.toString()) && !nextTrim.startsWith(file.toString())) {
                            // исходная строка кода обычно содержит символы ;(){} и т.п. — не добавляем её
                            if (!nextTrim.contains(";") && !nextTrim.contains("{") && !nextTrim.contains("}")) {
                                msg = msg + " " + nextTrim;
                            }
                        }
                    }
                    i++;
                }
                result.add(new Problem(file, ln, kind, msg));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static String joinPaths(List<Path> paths) {
        String sep = java.io.File.pathSeparator;
        StringBuilder sb = new StringBuilder();
        for (Path p : paths) {
            if (p == null) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p.toString());
        }
        return sb.toString();
    }

    private static int safeParseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
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
        // Пробуем применить также подсветку ошибок/предупреждений для открытого файла
        Path file = null;
        Tab tab = editorTabs != null ? editorTabs.getSelectionModel().getSelectedItem() : null;
        if (tab != null) {
            EditorTabData data = (EditorTabData) tab.getUserData();
            if (data != null && data.editor == area) {
                file = data.path;
            }
        }
        area.setStyleSpans(0, computeHighlightingWithProblems(text, file));
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

    private StyleSpans<Collection<String>> computeHighlightingWithProblems(String text, Path file) {
        StyleSpans<Collection<String>> syntax = computeHighlighting(text);
        if (file == null) return syntax;
        List<Problem> probs = problemsByFile.get(file);
        if (probs == null || probs.isEmpty()) return syntax;

        Set<Integer> errLines = new HashSet<>();
        Set<Integer> warnLines = new HashSet<>();
        for (Problem p : probs) {
            if (p.line <= 0) continue;
            if ("error".equalsIgnoreCase(p.kind)) errLines.add(p.line);
            else if ("warning".equalsIgnoreCase(p.kind)) warnLines.add(p.line);
        }
        if (errLines.isEmpty() && warnLines.isEmpty()) return syntax;

        StyleSpans<Collection<String>> overlay = buildLineOverlaySpans(text, errLines, warnLines);
        return mergeStyleSpans(syntax, overlay);
    }

    private static StyleSpans<Collection<String>> buildLineOverlaySpans(String text, Set<Integer> errLines, Set<Integer> warnLines) {
        Map<Integer, String> lineStyle = new HashMap<>();
        for (Integer l : warnLines) lineStyle.put(l, "warnLine");
        for (Integer l : errLines) lineStyle.put(l, "errLine");

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
                if (len > 0) spans.add(ls == null ? Collections.emptyList() : Collections.singleton(ls), len);
                if (!isEnd) spans.add(Collections.emptyList(), 1);
                line++;
                lineStart = idx + 1;
            }
            idx++;
        }
        return spans.create();
    }

    private static StyleSpans<Collection<String>> mergeStyleSpans(
            StyleSpans<Collection<String>> a,
            StyleSpans<Collection<String>> b
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

        // Слова из текущего файла (локальные идентификаторы)
        if (!prefix.isEmpty()) {
            for (String w : extractWordsForCompletion(area.getText(), prefix, 60)) {
                suggestions.add(new CompletionItem(w, CompletionItemType.VARIABLE, w));
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

    private static List<String> extractWordsForCompletion(String text, String prefix, int limit) {
        if (text == null || prefix == null || prefix.isEmpty()) return List.of();
        String p = prefix.toLowerCase();
        Set<String> out = new HashSet<>();
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
    
    private void startFileWatcher() {
        stopFileWatcher();
        if (projectRoot == null) return;
        
        try {
            fileWatcher = FileSystems.getDefault().newWatchService();
            registerDirectory(projectRoot);
            
            fileWatcherThread = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        WatchKey key = fileWatcher.take();
                        if (key == null) continue;
                        
                        boolean shouldRefresh = false;
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                            
                            @SuppressWarnings("unchecked")
                            WatchEvent<Path> ev = (WatchEvent<Path>) event;
                            Path path = ev.context();
                            Path fullPath = ((Path) key.watchable()).resolve(path);
                            
                            if (shouldHidePath(fullPath)) continue;
                            
                            if (kind == StandardWatchEventKinds.ENTRY_CREATE || 
                                kind == StandardWatchEventKinds.ENTRY_DELETE ||
                                kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                                shouldRefresh = true;
                                
                                if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
                                    registerDirectory(fullPath);
                                }
                            }
                        }
                        
                        if (shouldRefresh) {
                            Platform.runLater(() -> {
                                if (projectRoot != null) {
                                    scheduleTreeRefresh();
                                }
                            });
                        }
                        
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
    
    private void registerDirectory(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        if (shouldHidePath(dir)) return;
        
        try {
            dir.register(fileWatcher, 
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
            
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    if (Files.isDirectory(child) && !shouldHidePath(child)) {
                        registerDirectory(child);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }
    
    private void stopFileWatcher() {
        if (fileWatcherThread != null) {
            fileWatcherThread.interrupt();
            fileWatcherThread = null;
        }
        if (fileWatcher != null) {
            try {
                fileWatcher.close();
            } catch (IOException ignored) {
            }
            fileWatcher = null;
        }
    }
    
    private void scheduleTreeRefresh() {
        if (treeRefreshTimer == null) {
            treeRefreshTimer = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
            treeRefreshTimer.setOnFinished(e -> {
                if (projectRoot != null) {
                    refreshTreeIfUnderRoot(projectRoot);
                }
            });
        }
        treeRefreshTimer.stop();
        treeRefreshTimer.play();
    }
}

