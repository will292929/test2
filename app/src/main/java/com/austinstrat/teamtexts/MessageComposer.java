package com.austinstrat.teamtexts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageComposer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    private MessageComposer() {}

    public static String compose(List<MessageSection> sections, Map<String, String> row,
            String nameColumn, String phoneColumn, Random random) {
        StringBuilder message = new StringBuilder();
        for (MessageSection section : sections) {
            List<String> usable = new ArrayList<>();
            for (String option : section.options) {
                if (option != null && !option.trim().isEmpty()) usable.add(option);
            }
            if (usable.isEmpty()) continue;
            String chosen = usable.get(random.nextInt(usable.size()));
            appendSmart(message, replacePlaceholders(chosen, row, nameColumn, phoneColumn));
        }
        return message.toString().trim();
    }

    public static boolean containsUnresolvedPlaceholder(String text) {
        return text != null && PLACEHOLDER.matcher(text).find();
    }

    private static String replacePlaceholders(String template, Map<String, String> row,
            String nameColumn, String phoneColumn) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1).trim();
            String replacement = lookup(row, token, nameColumn, phoneColumn);
            if (replacement == null) replacement = matcher.group(0);
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String lookup(Map<String, String> row, String token,
            String nameColumn, String phoneColumn) {
        String normalizedToken = normalize(token);
        if (normalizedToken.equals("first_name") || normalizedToken.equals("name")) {
            String value = getCaseInsensitive(row, nameColumn);
            if (value != null) return firstName(value);
        }
        if (normalizedToken.equals("phone") || normalizedToken.equals("phone_number")) {
            String value = getCaseInsensitive(row, phoneColumn);
            if (value != null) return value;
        }
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(token)
                    || normalize(entry.getKey()).equals(normalizedToken)) return entry.getValue();
        }
        return null;
    }

    private static String firstName(String value) {
        String trimmed = value == null ? "" : value.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    private static String getCaseInsensitive(Map<String, String> row, String key) {
        if (key == null || key.isEmpty()) return null;
        for (Map.Entry<String, String> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static void appendSmart(StringBuilder target, String part) {
        if (part == null || part.isEmpty()) return;
        if (target.length() > 0) {
            char left = target.charAt(target.length() - 1);
            char right = part.charAt(0);
            if (!Character.isWhitespace(left) && !Character.isWhitespace(right)) target.append(' ');
        }
        target.append(part);
    }
}
