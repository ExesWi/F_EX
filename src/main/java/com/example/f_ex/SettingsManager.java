package com.example.f_ex;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Менеджер настроек IDE
 */
public class SettingsManager {
    private static final String SETTINGS_FILE = ".ide-settings.properties";
    private final Properties settings;
    private final Path settingsPath;
    
    public SettingsManager(Path projectRoot) {
        this.settings = new Properties();
        this.settingsPath = projectRoot != null ? 
            projectRoot.resolve(SETTINGS_FILE) : 
            Paths.get(System.getProperty("user.home")).resolve(SETTINGS_FILE);
        loadSettings();
    }
    
    private void loadSettings() {
        if (Files.exists(settingsPath)) {
            try (var reader = Files.newBufferedReader(settingsPath)) {
                settings.load(reader);
            } catch (IOException e) {
                // Используем настройки по умолчанию
            }
        }
    }
    
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
    
    public String get(String key, String defaultValue) {
        return settings.getProperty(key, defaultValue);
    }
    
    public void set(String key, String value) {
        settings.setProperty(key, value);
        saveSettings();
    }
    
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = settings.getProperty(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
    
    public void setBoolean(String key, boolean value) {
        settings.setProperty(key, String.valueOf(value));
        saveSettings();
    }
    
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
    
    // Настройки по умолчанию
    public static final String KEY_THEME = "theme";
    public static final String KEY_FONT_FAMILY = "font.family";
    public static final String KEY_FONT_SIZE = "font.size";
    public static final String KEY_AUTO_COMPLETE = "auto.complete.enabled";
    public static final String KEY_AUTO_COMPLETE_DELAY = "auto.complete.delay";
    
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
}
