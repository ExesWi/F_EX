package com.example.f_ex;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) {
        try {
            FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("ide-view.fxml"));
            if (fxmlLoader.getLocation() == null) {
                System.err.println("ERROR: Cannot find ide-view.fxml resource");
                System.err.println("Class location: " + HelloApplication.class.getProtectionDomain().getCodeSource().getLocation());
                return;
            }
            Scene scene = new Scene(fxmlLoader.load(), 1100, 750);
            var cssUrl = HelloApplication.class.getResource("ide.css");
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl.toExternalForm());
            } else {
                System.err.println("WARNING: Cannot find ide.css resource");
            }
            stage.setTitle("F_EX Java IDE (MVP)");
            stage.setScene(scene);
            stage.show();
        } catch (Exception e) {
            System.err.println("FATAL ERROR starting application:");
            e.printStackTrace();
            try {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
                alert.setTitle("Application Error");
                alert.setHeaderText("Failed to start application");
                alert.setContentText(e.getMessage() + "\n\nCheck console for details.");
                alert.showAndWait();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }
}
