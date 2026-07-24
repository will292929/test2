package com.austinstrat.teamtexts;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CsvUtils {
    private CsvUtils() {}

    public static final class ImportResult {
        public final String displayName;
        public final List<String> headers;
        public final int rowCount;
        public ImportResult(String displayName, List<String> headers, int rowCount) {
            this.displayName = displayName;
            this.headers = headers;
            this.rowCount = rowCount;
        }
    }

    public static ImportResult importCsv(Context context, Uri uri) throws IOException {
        File target = new File(context.getFilesDir(), AppConfig.CONTACTS_FILE);
        File temp = new File(context.getFilesDir(), AppConfig.CONTACTS_FILE + ".tmp");
        try (InputStream input = new BufferedInputStream(requireStream(context, uri));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(temp))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
        int count = 0;
        List<String> headers;
        try (PushbackReader reader = CsvParser.buffered(new InputStreamReader(
                new FileInputStream(temp), StandardCharsets.UTF_8))) {
            List<String> rawHeaders = CsvParser.readRow(reader);
            if (rawHeaders == null || rawHeaders.isEmpty()) throw new IOException("The CSV has no header row.");
            headers = CsvParser.makeUniqueHeaders(rawHeaders);
            List<String> row;
            while ((row = CsvParser.readRow(reader)) != null) if (!isBlankRow(row)) count++;
        }
        if (count == 0) throw new IOException("The CSV has headers but no contact rows.");
        if (target.exists() && !target.delete()) throw new IOException("Could not replace the previous CSV.");
        if (!temp.renameTo(target)) {
            copyFile(temp, target);
            if (!temp.delete()) temp.deleteOnExit();
        }
        return new ImportResult(getDisplayName(context, uri), headers, count);
    }

    public static List<Map<String, String>> readRange(File file, int startIndex, int limit) throws IOException {
        List<Map<String, String>> records = new ArrayList<>();
        if (limit <= 0) return records;
        try (PushbackReader reader = CsvParser.buffered(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            List<String> rawHeaders = CsvParser.readRow(reader);
            if (rawHeaders == null) throw new IOException("CSV header is missing.");
            List<String> headers = CsvParser.makeUniqueHeaders(rawHeaders);
            int dataIndex = 0;
            List<String> row;
            while ((row = CsvParser.readRow(reader)) != null) {
                if (isBlankRow(row)) continue;
                if (dataIndex >= startIndex && records.size() < limit) {
                    Map<String, String> record = new LinkedHashMap<>();
                    for (int i = 0; i < headers.size(); i++)
                        record.put(headers.get(i), i < row.size() ? row.get(i).trim() : "");
                    records.add(record);
                }
                dataIndex++;
                if (records.size() >= limit) break;
            }
        }
        return records;
    }

    private static InputStream requireStream(Context context, Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Android could not open that file.");
        return input;
    }

    private static boolean isBlankRow(List<String> row) {
        for (String value : row) if (value != null && !value.trim().isEmpty()) return false;
        return true;
    }

    private static String getDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (RuntimeException ignored) {}
        String last = uri.getLastPathSegment();
        return last == null ? "contacts.csv" : last;
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             OutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }
}
