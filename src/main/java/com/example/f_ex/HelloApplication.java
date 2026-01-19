package com.example.f_ex;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public class HelloApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(HelloApplication.class.getResource("ide-view.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1100, 750);
        scene.getStylesheets().add(Objects.requireNonNull(
                HelloApplication.class.getResource("ide.css")
        ).toExternalForm());
        stage.setTitle("F_EX Java IDE (MVP)");
        stage.setScene(scene);
        stage.show();
    }
}
