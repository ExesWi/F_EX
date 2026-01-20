package com.example.f_ex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DebugParsers {
    static final class ThreadItem {
        final String id;
        final String name;
        final String raw;
        ThreadItem(String id, String name, String raw) {
            this.id = id;
            this.name = name;
            this.raw = raw;
        }
        @Override public String toString() { return raw; }
    }

    static final class FrameItem {
        final int index;
        final String raw;
        FrameItem(int index, String raw) { this.index = index; this.raw = raw; }
        @Override public String toString() { return raw; }
    }

    static final class VarItem {
        final String name;
        final String value;
        VarItem(String name, String value) { this.name = name; this.value = value; }
        @Override public String toString() { return name + " = " + value; }
    }

    static List<ThreadItem> parseThreads(List<String> lines) {
        List<ThreadItem> out = new ArrayList<>();
        // examples:
        // (java.lang.Thread)0x1 main running
        Pattern p = Pattern.compile("^\\(java\\.lang\\.Thread\\)(\\S+)\\s+(.*)$");
        for (String l : lines) {
            String t = l.trim();
            Matcher m = p.matcher(t);
            if (m.find()) {
                String id = m.group(1);
                String rest = m.group(2);
                String name = rest.split("\\s+")[0];
                out.add(new ThreadItem(id, name, rest));
            }
        }
        return out;
    }

    static List<FrameItem> parseWhere(List<String> lines) {
        List<FrameItem> out = new ArrayList<>();
        // examples:
        // [1] com.example.Main.main (Main.java:10)
        Pattern p = Pattern.compile("^\\[(\\d+)\\]\\s+(.*)$");
        for (String l : lines) {
            Matcher m = p.matcher(l.trim());
            if (m.find()) out.add(new FrameItem(Integer.parseInt(m.group(1)), m.group(2)));
        }
        return out;
    }

    static List<VarItem> parseLocals(List<String> lines) {
        List<VarItem> out = new ArrayList<>();
        // examples:
        // i = 10
        Pattern p = Pattern.compile("^([A-Za-z_$][A-Za-z\\d_$]*)\\s*=\\s*(.*)$");
        for (String l : lines) {
            Matcher m = p.matcher(l.trim());
            if (m.find()) out.add(new VarItem(m.group(1), m.group(2)));
        }
        return out;
    }
}

