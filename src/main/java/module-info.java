module com.example.f_ex {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;

    requires org.controlsfx.controls;
    requires com.dlsc.formsfx;
    requires net.synedra.validatorfx;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;
    requires org.fxmisc.richtext;
    requires org.fxmisc.flowless;
    requires reactfx;

    opens com.example.f_ex to javafx.fxml;
    exports com.example.f_ex;
}