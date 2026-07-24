package com.austinstrat.teamtexts;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends Activity {
    private static final int PICK_CSV = 41;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppConfig.Config config;
    private TextView csvStatus;
    private TextView progressStatus;
    private Spinner phoneSpinner;
    private Spinner nameSpinner;
    private EditText minDelay;
    private EditText maxDelay;
    private EditText batchSize;
    private LinearLayout sectionsContainer;
    private ProgressBar busy;
    private Button importButton;
    private final List<SectionEditor> sectionEditors = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = AppConfig.load(this);
        setContentView(buildScreen());
        populateFromConfig();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.background));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        root.setPadding(pad, pad, pad, Ui.dp(this, 40));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.heading(this, "Message Settings", 28));
        root.addView(Ui.body(this, "Import the employee CSV, choose the name and phone columns, build randomized message sections, and set the batch pacing."), Ui.matchWrap(6, this));

        LinearLayout csvCard = Ui.card(this);
        root.addView(csvCard, Ui.matchWrap(18, this));
        csvCard.addView(Ui.heading(this, "Contact spreadsheet", 20));
        csvStatus = Ui.body(this, "No CSV imported");
        csvCard.addView(csvStatus, Ui.matchWrap(8, this));
        progressStatus = Ui.body(this, "Progress: 0 of 0");
        csvCard.addView(progressStatus, Ui.matchWrap(4, this));
        importButton = Ui.button(this, "Import CSV");
        importButton.setOnClickListener(v -> pickCsv());
        csvCard.addView(importButton, Ui.matchWrap(12, this));
        busy = new ProgressBar(this);
        busy.setIndeterminate(true);
        busy.setVisibility(View.GONE);
        LinearLayout.LayoutParams busyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        busyParams.gravity = Gravity.CENTER_HORIZONTAL;
        busyParams.topMargin = Ui.dp(this, 10);
        csvCard.addView(busy, busyParams);
        csvCard.addView(label("Phone-number column"), Ui.matchWrap(14, this));
        phoneSpinner = new Spinner(this);
        csvCard.addView(phoneSpinner, Ui.matchWrap(4, this));
        csvCard.addView(label("Name column used by {first_name}"), Ui.matchWrap(12, this));
        nameSpinner = new Spinner(this);
        csvCard.addView(nameSpinner, Ui.matchWrap(4, this));
        Button reset = Ui.button(this, "Reset saved position to first row");
        reset.setOnClickListener(v -> confirmReset());
        csvCard.addView(reset, Ui.matchWrap(12, this));

        LinearLayout pacingCard = Ui.card(this);
        root.addView(pacingCard, Ui.matchWrap(14, this));
        pacingCard.addView(Ui.heading(this, "Batch size and pacing", 20));
        pacingCard.addView(Ui.body(this, "A fresh random delay is selected between the minimum and maximum after each text."), Ui.matchWrap(6, this));
        minDelay = numberField("Minimum delay in seconds");
        maxDelay = numberField("Maximum delay in seconds");
        batchSize = numberField("Texts per button press");
        pacingCard.addView(minDelay, Ui.matchWrap(12, this));
        pacingCard.addView(maxDelay, Ui.matchWrap(8, this));
        pacingCard.addView(batchSize, Ui.matchWrap(8, this));

        LinearLayout messageCard = Ui.card(this);
        root.addView(messageCard, Ui.matchWrap(14, this));
        messageCard.addView(Ui.heading(this, "Message sections", 20));
        messageCard.addView(Ui.body(this, "One option is chosen at random from every section. Use any CSV heading inside braces, such as {first_name}, {City}, or {Shift}."), Ui.matchWrap(6, this));
        sectionsContainer = new LinearLayout(this);
        sectionsContainer.setOrientation(LinearLayout.VERTICAL);
        messageCard.addView(sectionsContainer, Ui.matchWrap(8, this));
        Button addSection = Ui.button(this, "Add section");
        addSection.setOnClickListener(v -> addSection(new MessageSection("Section " + (sectionEditors.size() + 1))));
        messageCard.addView(addSection, Ui.matchWrap(10, this));

        Button preview = Ui.button(this, "Preview a generated message");
        preview.setOnClickListener(v -> previewMessage());
        root.addView(preview, Ui.matchWrap(16, this));
        Button save = Ui.button(this, "Save settings");
        save.setOnClickListener(v -> saveSettings(true));
        root.addView(save, Ui.matchWrap(8, this));
        root.addView(Ui.body(this, "The Send Next Batch icon sends through the phone's normal SMS service. Carrier message charges and carrier anti-spam limits still apply."), Ui.matchWrap(14, this));
        return scroll;
    }

    private TextView label(String text) {
        TextView view = Ui.body(this, text);
        view.setTextColor(getColor(R.color.text_primary));
        return view;
    }

    private EditText numberField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        return field;
    }

    private void populateFromConfig() {
        csvStatus.setText(config.sourceFileName.isEmpty() ? "No CSV imported"
                : config.sourceFileName + " — " + config.totalRows + " contacts");
        progressStatus.setText("Saved position: " + Math.min(config.progress, config.totalRows)
                + " of " + config.totalRows + " completed");
        minDelay.setText(String.valueOf(config.minDelaySeconds));
        maxDelay.setText(String.valueOf(config.maxDelaySeconds));
        batchSize.setText(String.valueOf(config.batchSize));
        updateSpinners(config.headers, config.phoneColumn, config.nameColumn);
        sectionsContainer.removeAllViews();
        sectionEditors.clear();
        for (MessageSection section : config.sections) addSection(section);
    }

    private void updateSpinners(List<String> headers, String selectedPhone, String selectedName) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, headers);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        phoneSpinner.setAdapter(adapter);
        ArrayAdapter<String> nameAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, headers);
        nameAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        nameSpinner.setAdapter(nameAdapter);
        selectSpinner(phoneSpinner, headers, selectedPhone);
        selectSpinner(nameSpinner, headers, selectedName);
    }

    private static void selectSpinner(Spinner spinner, List<String> items, String value) {
        for (int i = 0; i < items.size(); i++) if (items.get(i).equals(value)) {
            spinner.setSelection(i);
            return;
        }
    }

    private void pickCsv() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"text/csv", "text/comma-separated-values", "text/plain"});
        startActivityForResult(intent, PICK_CSV);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_CSV || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        setBusy(true);
        io.execute(() -> {
            try {
                CsvUtils.ImportResult result = CsvUtils.importCsv(this, uri);
                runOnUiThread(() -> {
                    config.sourceFileName = result.displayName;
                    config.headers.clear();
                    config.headers.addAll(result.headers);
                    config.totalRows = result.rowCount;
                    config.progress = 0;
                    config.phoneColumn = findBestHeader(result.headers, "phone", "mobile", "cell", "telephone", "number");
                    config.nameColumn = findBestHeader(result.headers, "first_name", "firstname", "first name", "name");
                    AppConfig.save(this, config);
                    populateFromConfig();
                    setBusy(false);
                    Toast.makeText(this, "CSV imported. Saved position reset.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError("Could not import CSV", error.getMessage());
                });
            }
        });
    }

    private String findBestHeader(List<String> headers, String... terms) {
        for (String term : terms) for (String header : headers) {
            String normalized = header.toLowerCase().replaceAll("[^a-z0-9]", "");
            String wanted = term.toLowerCase().replaceAll("[^a-z0-9]", "");
            if (normalized.equals(wanted)) return header;
        }
        for (String term : terms) for (String header : headers)
            if (header.toLowerCase().contains(term.toLowerCase())) return header;
        return headers.isEmpty() ? "" : headers.get(0);
    }

    private void setBusy(boolean value) {
        busy.setVisibility(value ? View.VISIBLE : View.GONE);
        importButton.setEnabled(!value);
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Reset saved position?")
                .setMessage("The next batch will begin with the first contact in the imported CSV.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (dialog, which) -> {
                    config.progress = 0;
                    AppConfig.setProgress(this, 0);
                    progressStatus.setText("Saved position: 0 of " + config.totalRows + " completed");
                    Toast.makeText(this, "Position reset", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void addSection(MessageSection section) {
        SectionEditor editor = new SectionEditor(section);
        sectionEditors.add(editor);
        sectionsContainer.addView(editor.root, Ui.matchWrap(10, this));
    }

    private boolean saveSettings(boolean showToast) {
        if (!config.hasImportedCsv(this)) {
            showError("CSV required", "Import the employee CSV before saving.");
            return false;
        }
        int min = parseInt(minDelay, -1);
        int max = parseInt(maxDelay, -1);
        int batch = parseInt(batchSize, -1);
        if (min < 0 || max < 0 || min > 86400 || max > 86400) {
            showError("Invalid delay", "Use a delay from 0 to 86,400 seconds.");
            return false;
        }
        if (min > max) {
            showError("Invalid delay range", "Minimum delay cannot be greater than maximum delay.");
            return false;
        }
        if (batch < 1 || batch > 10000) {
            showError("Invalid batch size", "Texts per button press must be between 1 and 10,000.");
            return false;
        }
        if (phoneSpinner.getSelectedItem() == null || nameSpinner.getSelectedItem() == null) {
            showError("CSV columns required", "Select both the phone-number and name columns.");
            return false;
        }
        List<MessageSection> sections = new ArrayList<>();
        for (SectionEditor editor : sectionEditors) {
            MessageSection section = editor.read();
            if (!section.options.isEmpty()) sections.add(section);
        }
        if (sections.isEmpty()) {
            showError("Message required", "Add at least one section with at least one option.");
            return false;
        }
        config.phoneColumn = phoneSpinner.getSelectedItem().toString();
        config.nameColumn = nameSpinner.getSelectedItem().toString();
        config.minDelaySeconds = min;
        config.maxDelaySeconds = max;
        config.batchSize = batch;
        config.sections.clear();
        config.sections.addAll(sections);
        AppConfig.save(this, config);
        if (showToast) Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        return true;
    }

    private void previewMessage() {
        if (!saveSettings(false)) return;
        setBusy(true);
        io.execute(() -> {
            try {
                File file = new File(getFilesDir(), AppConfig.CONTACTS_FILE);
                List<Map<String, String>> rows = CsvUtils.readRange(file,
                        Math.min(config.progress, Math.max(0, config.totalRows - 1)), 1);
                if (rows.isEmpty()) rows = CsvUtils.readRange(file, 0, 1);
                if (rows.isEmpty()) throw new IllegalStateException("No contact row could be read.");
                String message = MessageComposer.compose(config.sections, rows.get(0),
                        config.nameColumn, config.phoneColumn, new Random());
                if (MessageComposer.containsUnresolvedPlaceholder(message))
                    throw new IllegalStateException("The preview contains an unknown {placeholder}. Check the CSV headings.");
                String finalMessage = message;
                runOnUiThread(() -> {
                    setBusy(false);
                    new AlertDialog.Builder(this).setTitle("Generated preview")
                            .setMessage(finalMessage).setPositiveButton("Close", null).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError("Preview failed", error.getMessage());
                });
            }
        });
    }

    private static int parseInt(EditText field, int fallback) {
        try { return Integer.parseInt(field.getText().toString().trim()); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage(message == null ? "Unknown error" : message)
                .setPositiveButton("OK", null).show();
    }

    private final class SectionEditor {
        final LinearLayout root;
        final EditText name;
        final LinearLayout options;
        final List<EditText> optionFields = new ArrayList<>();

        SectionEditor(MessageSection section) {
            root = Ui.card(SettingsActivity.this);
            name = new EditText(SettingsActivity.this);
            name.setHint("Section name");
            name.setSingleLine(true);
            name.setText(section.name);
            root.addView(name);
            options = new LinearLayout(SettingsActivity.this);
            options.setOrientation(LinearLayout.VERTICAL);
            root.addView(options, Ui.matchWrap(6, SettingsActivity.this));
            for (String option : section.options) addOption(option);
            if (section.options.isEmpty()) addOption("");
            LinearLayout actions = new LinearLayout(SettingsActivity.this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            Button add = Ui.button(SettingsActivity.this, "Add option");
            add.setOnClickListener(v -> addOption(""));
            Button remove = Ui.button(SettingsActivity.this, "Remove section");
            remove.setOnClickListener(v -> {
                if (sectionEditors.size() <= 1) {
                    Toast.makeText(SettingsActivity.this, "At least one section is required", Toast.LENGTH_SHORT).show();
                    return;
                }
                sectionsContainer.removeView(root);
                sectionEditors.remove(this);
            });
            actions.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            actions.addView(remove, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            root.addView(actions, Ui.matchWrap(8, SettingsActivity.this));
        }

        void addOption(String value) {
            LinearLayout row = new LinearLayout(SettingsActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            EditText field = new EditText(SettingsActivity.this);
            field.setHint("Message option");
            field.setMinLines(2);
            field.setMaxLines(6);
            field.setText(value);
            Button delete = Ui.button(SettingsActivity.this, "×");
            delete.setOnClickListener(v -> {
                if (optionFields.size() <= 1) {
                    field.setText("");
                    return;
                }
                options.removeView(row);
                optionFields.remove(field);
            });
            row.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(delete, new LinearLayout.LayoutParams(Ui.dp(SettingsActivity.this, 56), ViewGroup.LayoutParams.WRAP_CONTENT));
            optionFields.add(field);
            options.addView(row, Ui.matchWrap(4, SettingsActivity.this));
        }

        MessageSection read() {
            String sectionName = name.getText().toString().trim();
            if (sectionName.isEmpty()) sectionName = "Section";
            MessageSection section = new MessageSection(sectionName);
            for (EditText field : optionFields) {
                String option = field.getText().toString();
                if (!option.trim().isEmpty()) section.options.add(option);
            }
            return section;
        }
    }
}
