package com.example.f_ex;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

final class RefactorRenameService {
    static final class RenameResult {
        final int filesChanged;
        final int occurrences;

        RenameResult(int filesChanged, int occurrences) {
            this.filesChanged = filesChanged;
            this.occurrences = occurrences;
        }
    }

    static final class RenamePlan {
        final String from;
        final String to;
        final List<FileChange> changes;

        RenamePlan(String from, String to, List<FileChange> changes) {
            this.from = from;
            this.to = to;
            this.changes = changes;
        }
    }

    static final class FileChange {
        final Path file;
        final int occurrences;
        final String before;
        final String after;

        FileChange(Path file, int occurrences, String before, String after) {
            this.file = file;
            this.occurrences = occurrences;
            this.before = before;
            this.after = after;
        }
    }

    RenameResult renameInFile(Path file, String from, String to) {
        return renameInFiles(List.of(file), from, to);
    }

    RenameResult renameInFiles(List<Path> files, String from, String to) {
        RenamePlan plan = planRename(files, from, to);
        return applyPlan(plan);
    }

    RenamePlan planRename(List<Path> files, String from, String to) {
        if (files == null || files.isEmpty()) return new RenamePlan(from, to, List.of());
        if (!isIdentifier(from) || !isIdentifier(to)) return new RenamePlan(from, to, List.of());
        if (from.equals(to)) return new RenamePlan(from, to, List.of());

        List<FileChange> changes = new ArrayList<>();
        for (Path f : files) {
            if (f == null) continue;
            try {
                String s = Files.readString(f, StandardCharsets.UTF_8);
                ReplaceResult rr = replaceIdentifiersOutsideStringsAndComments(s, from, to);
                if (rr.count == 0) continue;
                changes.add(new FileChange(f, rr.count, s, rr.text));
            } catch (Exception ignored) {
            }
        }

        return new RenamePlan(from, to, changes);
    }

    RenameResult applyPlan(RenamePlan plan) {
        if (plan == null || plan.changes == null || plan.changes.isEmpty()) return new RenameResult(0, 0);
        int changedFiles = 0;
        int occ = 0;

        for (FileChange ch : plan.changes) {
            if (ch == null || ch.file == null) continue;
            try {
                Files.writeString(ch.file, ch.after, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                changedFiles++;
                occ += ch.occurrences;
            } catch (Exception ignored) {
            }
        }

        return new RenameResult(changedFiles, occ);
    }

    private static boolean isIdentifier(String s) {
        return s != null && s.matches("[A-Za-z_$][A-Za-z\\d_$]*");
    }

    private static final class ReplaceResult {
        final String text;
        final int count;

        ReplaceResult(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }

    private static ReplaceResult replaceIdentifiersOutsideStringsAndComments(String src, String from, String to) {
        if (src == null || src.isEmpty()) return new ReplaceResult(src, 0);

        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int count = 0;

        State st = State.CODE;
        while (i < src.length()) {
            char c = src.charAt(i);

            if (st == State.CODE) {
                if (c == '"' ) { out.append(c); i++; st = State.STRING; continue; }
                if (c == '\'') { out.append(c); i++; st = State.CHAR; continue; }
                if (c == '/' && i + 1 < src.length()) {
                    char n = src.charAt(i + 1);
                    if (n == '/') { out.append(c).append(n); i += 2; st = State.LINE_COMMENT; continue; }
                    if (n == '*') { out.append(c).append(n); i += 2; st = State.BLOCK_COMMENT; continue; }
                }

                if (isIdentStart(c)) {
                    int start = i;
                    i++;
                    while (i < src.length() && isIdentPart(src.charAt(i))) i++;
                    String ident = src.substring(start, i);
                    if (ident.equals(from)) {
                        out.append(to);
                        count++;
                    } else {
                        out.append(ident);
                    }
                    continue;
                }

                out.append(c);
                i++;
                continue;
            }

            if (st == State.STRING) {
                out.append(c);
                i++;
                if (c == '\\' && i < src.length()) { out.append(src.charAt(i)); i++; continue; }
                if (c == '"') st = State.CODE;
                continue;
            }

            if (st == State.CHAR) {
                out.append(c);
                i++;
                if (c == '\\' && i < src.length()) { out.append(src.charAt(i)); i++; continue; }
                if (c == '\'') st = State.CODE;
                continue;
            }

            if (st == State.LINE_COMMENT) {
                out.append(c);
                i++;
                if (c == '\n') st = State.CODE;
                continue;
            }

            // BLOCK_COMMENT
            out.append(c);
            i++;
            if (c == '*' && i < src.length() && src.charAt(i) == '/') {
                out.append('/');
                i++;
                st = State.CODE;
            }
        }

        return new ReplaceResult(out.toString(), count);
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private enum State { CODE, STRING, CHAR, LINE_COMMENT, BLOCK_COMMENT }
}

