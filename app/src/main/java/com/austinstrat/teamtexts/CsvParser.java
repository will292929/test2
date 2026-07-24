package com.austinstrat.teamtexts;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CsvParser {
    private CsvParser() {}

    public static List<String> readRow(PushbackReader reader) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean sawAnyCharacter = false;
        while (true) {
            int value = reader.read();
            if (value == -1) {
                if (!sawAnyCharacter && field.length() == 0 && fields.isEmpty()) return null;
                fields.add(field.toString());
                return fields;
            }
            sawAnyCharacter = true;
            char ch = (char) value;
            if (inQuotes) {
                if (ch == '"') {
                    int next = reader.read();
                    if (next == '"') field.append('"');
                    else {
                        inQuotes = false;
                        if (next != -1) reader.unread(next);
                    }
                } else field.append(ch);
                continue;
            }
            if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (ch == '"' && field.length() == 0) inQuotes = true;
            else if (ch == '\n') {
                fields.add(field.toString());
                return fields;
            } else if (ch == '\r') {
                int next = reader.read();
                if (next != '\n' && next != -1) reader.unread(next);
                fields.add(field.toString());
                return fields;
            } else field.append(ch);
        }
    }

    public static List<String> makeUniqueHeaders(List<String> rawHeaders) {
        List<String> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < rawHeaders.size(); i++) {
            String base = rawHeaders.get(i);
            if (i == 0 && base.startsWith("\uFEFF")) base = base.substring(1);
            base = base.trim();
            if (base.isEmpty()) base = "Column " + (i + 1);
            String candidate = base;
            int suffix = 2;
            while (used.contains(candidate.toLowerCase())) candidate = base + " (" + suffix++ + ")";
            used.add(candidate.toLowerCase());
            result.add(candidate);
        }
        return result;
    }

    public static PushbackReader buffered(Reader reader) {
        return new PushbackReader(reader, 2);
    }
}
