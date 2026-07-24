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
        c.sourceFileName = p.getString("source_file_name", "");
        c.phoneColumn = p.getString("phone_column", "");
        c.nameColumn = p.getString("name_column", "");
        c.minDelaySeconds = p.getInt("min_delay", 30);
        c.maxDelaySeconds = p.getInt("max_delay", 60);
        c.batchSize = p.getInt("batch_size", 10);
        c.progress = Math.max(0, p.getInt("progress", 0));
        c.totalRows = Math.max(0, p.getInt("total_rows", 0));
        try {
            JSONArray headers = new JSONArray(p.getString("headers", "[]"));
            for (int i = 0; i < headers.length(); i++) c.headers.add(headers.optString(i, ""));
        } catch (JSONException ignored) { c.headers.clear(); }
        try {
            JSONArray sections = new JSONArray(p.getString("sections", "[]"));
            for (int i = 0; i < sections.length(); i++) {
                JSONObject obj = sections.optJSONObject(i);
                if (obj == null) continue;
                MessageSection section = new MessageSection(obj.optString("name", "Section " + (i + 1)));
                JSONArray options = obj.optJSONArray("options");
                if (options != null) for (int j = 0; j < options.length(); j++) {
                    String option = options.optString(j, "");
                    if (!option.trim().isEmpty()) section.options.add(option);
                }
                if (!section.options.isEmpty()) c.sections.add(section);
            }
        } catch (JSONException ignored) { c.sections.clear(); }
        if (c.sections.isEmpty()) {
            MessageSection greeting = new MessageSection("Greeting");
            greeting.options.add("Good morning, {first_name}!");
            greeting.options.add("Morning, {first_name}!");
            c.sections.add(greeting);
            MessageSection message = new MessageSection("Message");
            message.options.add("Thank you for everything you are doing today.");
            c.sections.add(message);
        }
        return c;
    }

    public static void save(Context context, Config c) {
        JSONArray headers = new JSONArray();
        for (String header : c.headers) headers.put(header);
        JSONArray sections = new JSONArray();
        for (MessageSection section : c.sections) {
            JSONObject obj = new JSONObject();
            JSONArray options = new JSONArray();
            for (String option : section.options) options.put(option);
            try {
                obj.put("name", section.name);
                obj.put("options", options);
                sections.put(obj);
            } catch (JSONException ignored) {}
        }
        prefs(context).edit()
                .putString("source_file_name", c.sourceFileName)
                .putString("headers", headers.toString())
                .putString("phone_column", c.phoneColumn)
                .putString("name_column", c.nameColumn)
                .putInt("min_delay", c.minDelaySeconds)
                .putInt("max_delay", c.maxDelaySeconds)
                .putInt("batch_size", c.batchSize)
                .putInt("progress", Math.max(0, c.progress))
                .putInt("total_rows", Math.max(0, c.totalRows))
                .putString("sections", sections.toString())
                .apply();
    }

    public static void setProgress(Context context, int progress) {
        prefs(context).edit().putInt("progress", Math.max(0, progress)).apply();
    }
}
