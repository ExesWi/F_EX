package com.example.f_ex;

import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

final class DebugSession {
    interface Handler {
        void onOutput(List<String> lines);
    }

    private final Consumer<String> log;
    private Process proc;
    private OutputStream in;
    private final StringBuilder buffer = new StringBuilder();
    private final Queue<Pending> pending = new ArrayDeque<>();
    private volatile boolean ready;

    DebugSession(Consumer<String> log) {
        this.log = log;
    }

    boolean isReady() {
        return ready && proc != null && proc.isAlive() && in != null;
    }

    void connectSocket(String host, int port) {
        if (proc != null && proc.isAlive()) return;
        Thread t = new Thread(() -> {
            try {
                List<String> cmd = new ArrayList<>();
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                if (isWindows) cmd.addAll(List.of("cmd.exe", "/c", "jdb"));
                else cmd.add("jdb");
                cmd.add("-connect");
                cmd.add("com.sun.jdi.SocketAttach:hostname=" + host + ",port=" + port);

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                proc = pb.start();
                in = proc.getOutputStream();
                ready = true;

                Platform.runLater(() -> log.accept("[jdb] connected to " + host + ":" + port));

                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        onLine(line);
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

    void request(String cmd, Handler handler) {
        if (!isReady()) return;
        pending.add(new Pending(handler));
        send(cmd);
    }

    void stop() {
        try { send("exit"); } catch (Exception ignored) {}
        try { if (proc != null) proc.destroyForcibly(); } catch (Exception ignored) {}
        ready = false;
    }

    private void onLine(String line) {
        Platform.runLater(() -> log.accept(line));
        buffer.append(line).append('\n');

        if (looksLikePrompt(line)) {
            flushOne();
        }
    }

    private void flushOne() {
        if (pending.isEmpty()) {
            buffer.setLength(0);
            return;
        }
        Pending p = pending.poll();
        if (p == null) return;

        String all = buffer.toString();
        buffer.setLength(0);

        List<String> lines = new ArrayList<>();
        for (String l : all.split("\\R")) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            if (looksLikePrompt(t)) continue;
            lines.add(l);
        }
        Platform.runLater(() -> p.handler.onOutput(lines));
    }

    private static boolean looksLikePrompt(String line) {
        String t = line.trim();
        return t.matches(".*\\[\\d+\\].*") || t.endsWith(">") || t.endsWith("]") && t.contains("[");
    }

    private static final class Pending {
        final Handler handler;
        Pending(Handler handler) { this.handler = handler; }
    }
}

