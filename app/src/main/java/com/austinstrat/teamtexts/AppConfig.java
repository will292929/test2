package com.austinstrat.teamtexts;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class AppConfig {
    private static final String PREFS = "team_text_settings";
    public static final String CONTACTS_FILE = "contacts.csv";

    private AppConfig() {}

    public static final class Config {
        public String sourceFileName = "";
        public final List<String> headers = new ArrayList<>();
        public String phoneColumn = "";
        public String nameColumn = "";
        public int minDelaySeconds = 30;
        public int maxDelaySeconds = 60;
        public int batchSize = 10;
        public int progress = 0;
        public int totalRows = 0;
        public final List<MessageSection> sections = new ArrayList<>();

        public boolean hasImportedCsv(Context context) {
            return totalRows > 0 && new File(context.getFilesDir(), CONTACTS_FILE).isFile();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Config load(Context context) {
        SharedPreferences p = prefs(context);
        Config c = new Config();
        c.sourceFileName = safeString(p, "source_file_name");
        c.phoneColumn = safeString(p, "phone_column");
        c.nameColumn = safeString(p, "name_column");
        c.minDelaySeconds = clamp(p.getInt("min_delay", 30), 0, 86400);
        c.maxDelaySeconds = clamp(p.getInt("max_delay", 60), c.minDelaySeconds, 86400);
        c.batchSize = clamp(p.getInt("batch_size", 10), 1, 10000);
        c.progress = Math.max(0, p.getInt("progress", 0));
        c.totalRows = Math.max(0, p.getInt("total_rows", 0));
        if (c.progress > c.totalRows) c.progress = c.totalRows;

        try {
            JSONArray headers = new JSONArray(p.getString("headers", "[]"));
            for (int i = 0; i < headers.length(); i++) {
                String header = headers.optString(i, "").trim();
                if (!header.isEmpty()) c.headers.add(header);
            }
        } catch (JSONException ignored) {
            c.headers.clear();
        }
        try {
            JSONArray sections = new JSONArray(p.getString("sections", "[]"));
            for (int i = 0; i < sections.length(); i++) {
                JSONObject object = sections.optJSONObject(i);
                if (object == null) continue;
                MessageSection section = new MessageSection(
                        object.optString("name", "Section " + (i + 1)));
                JSONArray options = object.optJSONArray("options");
                if (options != null) {
                    for (int j = 0; j < options.length(); j++) {
                        String option = options.optString(j, "");
                        if (!option.trim().isEmpty()) section.options.add(option);
                    }
                }
                if (!section.options.isEmpty()) c.sections.add(section);
            }
        } catch (JSONException ignored) {
            c.sections.clear();
        }
        if (c.sections.isEmpty()) addDefaults(c);
        return c;
    }

    private static void addDefaults(Config c) {
        MessageSection greeting = new MessageSection("Greeting");
        greeting.options.add("Good morning, {first_name}!");
        greeting.options.add("Morning, {first_name}!");
        c.sections.add(greeting);
        MessageSection message = new MessageSection("Message");
        message.options.add("Thank you for everything you are doing today.");
        c.sections.add(message);
    }

    public static boolean save(Context context, Config c) {
        JSONArray headers = new JSONArray();
        for (String header : c.headers) headers.put(header);
        JSONArray sections = new JSONArray();
        for (MessageSection section : c.sections) {
            JSONObject object = new JSONObject();
            JSONArray options = new JSONArray();
            for (String option : section.options) options.put(option);
            try {
                object.put("name", section.name);
                object.put("options", options);
                sections.put(object);
            } catch (JSONException ignored) {}
        }
        return prefs(context).edit()
                .putString("source_file_name", nullToEmpty(c.sourceFileName))
                .putString("headers", headers.toString())
                .putString("phone_column", nullToEmpty(c.phoneColumn))
                .putString("name_column", nullToEmpty(c.nameColumn))
                .putInt("min_delay", clamp(c.minDelaySeconds, 0, 86400))
                .putInt("max_delay", clamp(c.maxDelaySeconds, 0, 86400))
                .putInt("batch_size", clamp(c.batchSize, 1, 10000))
                .putInt("progress", Math.max(0, c.progress))
                .putInt("total_rows", Math.max(0, c.totalRows))
                .putString("sections", sections.toString())
                .commit();
    }

    public static boolean setProgress(Context context, int progress) {
        return prefs(context).edit().putInt("progress", Math.max(0, progress)).commit();
    }

    private static String safeString(SharedPreferences preferences, String key) {
        String value = preferences.getString(key, "");
        return value == null ? "" : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
