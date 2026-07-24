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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public final class GoActivity extends Activity {
    private static final int PERMISSIONS_REQUEST = 73;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildScreen());
        beginIfReady();
    }

    private LinearLayout buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = Ui.dp(this, 28);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(getColor(R.color.background));
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        status = Ui.heading(this, "Preparing batch…", 21);
        status.setGravity(Gravity.CENTER);
        root.addView(status, Ui.matchWrap(16, this));
        return root;
    }

    private void beginIfReady() {
        AppConfig.Config config = AppConfig.load(this);
        if (!config.hasImportedCsv(this) || config.phoneColumn.isEmpty() || config.sections.isEmpty()) {
            status.setText("Settings are incomplete");
            new AlertDialog.Builder(this)
                    .setTitle("Set up the app first")
                    .setMessage("Import the CSV and save the message settings before sending.")
                    .setNegativeButton("Close", (d, w) -> finish())
                    .setPositiveButton("Open settings", (d, w) -> {
                        startActivity(new Intent(this, SettingsActivity.class));
                        finish();
                    }).show();
            return;
        }
        if (config.progress >= config.totalRows) {
            status.setText("The imported list is complete");
            Toast.makeText(this, "Reset the saved position in Message Settings to begin again.", Toast.LENGTH_LONG).show();
            finishSoon();
            return;
        }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            status.setText("This device cannot send carrier SMS messages");
            finishSoon();
            return;
        }
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.SEND_SMS);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!missing.isEmpty()) {
            status.setText("Permission required");
            requestPermissions(missing.toArray(new String[0]), PERMISSIONS_REQUEST);
            return;
        }
        startBatch();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSIONS_REQUEST) return;
        boolean smsGranted = checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        if (!smsGranted) {
            status.setText("SMS permission was not granted");
            Toast.makeText(this, "The app cannot auto-send without SMS permission.", Toast.LENGTH_LONG).show();
            finishSoon();
            return;
        }
        startBatch();
    }

    private void startBatch() {
        status.setText("Batch started");
        Intent service = new Intent(this, BatchSmsService.class).setAction(BatchSmsService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
        else startService(service);
        finishSoon();
    }

    private void finishSoon() {
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1100);
    }
}
