package com.austinstrat.teamtexts;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class GoActivity extends Activity {
    private static final int PERMISSIONS_REQUEST = 73;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView details;
    private ProgressBar progress;
    private Button stopButton;
    private boolean launchAttempted;

    private final Runnable pollStatus = new Runnable() {
        @Override public void run() {
            renderStatus(BatchStatus.read(GoActivity.this));
            handler.postDelayed(this, 500L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(buildScreen());
            launchAttempted = savedInstanceState != null && savedInstanceState.getBoolean("launch_attempted", false);
            handler.post(pollStatus);
            if (!launchAttempted) beginIfReady();
        } catch (Throwable error) {
            showFatalScreen(error);
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("launch_attempted", launchAttempted);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(pollStatus);
        super.onDestroy();
    }

    private LinearLayout buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 24);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(getColor(R.color.background));

        TextView heading = Ui.heading(this, "Send Next Batch", 28);
        heading.setGravity(Gravity.CENTER);
        root.addView(heading, Ui.matchWrap(8, this));

        status = Ui.heading(this, "Checking settings…", 21);
        status.setGravity(Gravity.CENTER);
        root.addView(status, Ui.matchWrap(18, this));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 20)));

        details = Ui.body(this, "The app will remain open and show the actual SMS result.");
        details.setGravity(Gravity.CENTER);
        root.addView(details, Ui.matchWrap(18, this));

        stopButton = Ui.button(this, "Stop batch");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopBatch());
        root.addView(stopButton, Ui.matchWrap(12, this));

        Button settingsButton = Ui.button(this, "Open Message Settings");
        settingsButton.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(settingsButton, Ui.matchWrap(8, this));

        Button closeButton = Ui.button(this, "Close");
        closeButton.setOnClickListener(v -> finish());
        root.addView(closeButton, Ui.matchWrap(8, this));
        return root;
    }

    private void beginIfReady() {
        launchAttempted = true;
        try {
            BatchStatus.Snapshot existing = BatchStatus.read(this);
            if (existing.running) {
                renderStatus(existing);
                return;
            }

            AppConfig.Config config = AppConfig.load(this);
            if (!config.hasImportedCsv(this) || config.phoneColumn.isEmpty() || config.sections.isEmpty()) {
                showSetupRequired();
                return;
            }
            if (config.progress >= config.totalRows) {
                status.setText("The imported list is complete");
                details.setText("Reset the saved position in Message Settings to begin again.");
                return;
            }
            if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
                status.setText("Carrier SMS is unavailable");
                details.setText("This Android device does not report SMS messaging hardware.");
                return;
            }

            List<String> missing = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.SEND_SMS);
            }
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!missing.isEmpty()) {
                status.setText("Permission required");
                details.setText("Grant SMS permission so the app can send automatically.");
                requestPermissions(missing.toArray(new String[0]), PERMISSIONS_REQUEST);
                return;
            }
            startBatch();
        } catch (Throwable error) {
            showStartError(error);
        }
    }

    private void showSetupRequired() {
        status.setText("Settings are incomplete");
        details.setText("Import the CSV and save the message settings before sending.");
        new AlertDialog.Builder(this)
                .setTitle("Set up the app first")
                .setMessage("Import the CSV, select the phone column, and save at least one message section.")
                .setNegativeButton("Stay here", null)
                .setPositiveButton("Open settings", (d, w) ->
                        startActivity(new Intent(this, SettingsActivity.class)))
                .show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSIONS_REQUEST) return;
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            status.setText("SMS permission was not granted");
            details.setText("Open Android App Info → Permissions and allow SMS.");
            Toast.makeText(this, "The app cannot auto-send without SMS permission.", Toast.LENGTH_LONG).show();
            return;
        }
        startBatch();
    }

    private void startBatch() {
        try {
            AppConfig.Config config = AppConfig.load(this);
            BatchStatus.update(this, BatchStatus.State.STARTING, "Starting batch…",
                    0, Math.min(config.batchSize, Math.max(0, config.totalRows - config.progress)),
                    config.progress, config.totalRows, true);
            Intent service = new Intent(this, BatchSmsService.class).setAction(BatchSmsService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            status.setText("Starting batch…");
            details.setText("Waiting for Android's SMS service.");
            stopButton.setEnabled(true);
        } catch (Throwable error) {
            showStartError(error);
        }
    }

    private void stopBatch() {
        try {
            Intent service = new Intent(this, BatchSmsService.class).setAction(BatchSmsService.ACTION_STOP);
            startService(service);
            status.setText("Stopping…");
            stopButton.setEnabled(false);
        } catch (Throwable error) {
            showStartError(error);
        }
    }

    private void renderStatus(BatchStatus.Snapshot snapshot) {
        if (status == null || details == null || progress == null || stopButton == null) return;
        String heading;
        switch (snapshot.state) {
            case STARTING: heading = "Starting batch…"; break;
            case SENDING: heading = "Sending text…"; break;
            case WAITING: heading = "Waiting for next text…"; break;
            case COMPLETE: heading = "Batch complete"; break;
            case STOPPED: heading = "Batch stopped"; break;
            case ERROR: heading = "Batch error"; break;
            default: heading = snapshot.running ? "Batch running" : "Ready"; break;
        }
        status.setText(heading);
        String detail = snapshot.message;
        if (snapshot.overallTotal > 0) {
            detail += "\nOverall: " + snapshot.overallProgress + " of " + snapshot.overallTotal;
        }
        details.setText(detail);
        if (snapshot.batchTotal > 0) {
            progress.setIndeterminate(false);
            progress.setMax(snapshot.batchTotal);
            progress.setProgress(Math.min(snapshot.sentThisBatch, snapshot.batchTotal));
        } else {
            progress.setIndeterminate(snapshot.running);
            if (!snapshot.running) {
                progress.setIndeterminate(false);
                progress.setMax(1);
                progress.setProgress(snapshot.state == BatchStatus.State.COMPLETE ? 1 : 0);
            }
        }
        stopButton.setEnabled(snapshot.running);
    }

    private void showStartError(Throwable error) {
        String message = safeMessage(error);
        BatchStatus.update(this, BatchStatus.State.ERROR, message, 0, 0, 0, 0, false);
        if (status != null) status.setText("Could not start the batch");
        if (details != null) details.setText(message);
        if (stopButton != null) stopButton.setEnabled(false);
    }

    private void showFatalScreen(Throwable error) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        TextView title = new TextView(this);
        title.setText("The send screen could not open");
        title.setTextSize(22);
        root.addView(title);
        TextView body = new TextView(this);
        body.setText(safeMessage(error));
        root.addView(body);
        Button settings = new Button(this);
        settings.setText("Open Message Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(settings);
        setContentView(root);
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "Unknown error";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
