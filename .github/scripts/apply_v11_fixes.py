from pathlib import Path
import re

ROOT = Path('.')
JAVA = ROOT / 'app/src/main/java/com/austinstrat/teamtexts'


def replace_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f'Could not patch {label}; matches={count}')
    return updated

# 1) Preview should work from current on-screen sections and without a CSV.
settings_path = JAVA / 'SettingsActivity.java'
settings = settings_path.read_text()
if 'import java.util.LinkedHashMap;' not in settings:
    settings = settings.replace('import java.util.ArrayList;', 'import java.util.ArrayList;\nimport java.util.LinkedHashMap;')

settings_methods = r'''    private boolean saveSettings(boolean showToast) {
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
        if (config.hasImportedCsv(this)
                && (phoneSpinner.getSelectedItem() == null || nameSpinner.getSelectedItem() == null)) {
            showError("CSV columns required", "Select both the phone-number and name columns.");
            return false;
        }

        List<MessageSection> sections = readSectionsFromEditors();
        if (sections.isEmpty()) {
            showError("Message required", "Add at least one section with at least one option.");
            return false;
        }
        if (phoneSpinner.getSelectedItem() != null) config.phoneColumn = phoneSpinner.getSelectedItem().toString();
        if (nameSpinner.getSelectedItem() != null) config.nameColumn = nameSpinner.getSelectedItem().toString();
        config.minDelaySeconds = min;
        config.maxDelaySeconds = max;
        config.batchSize = batch;
        config.sections.clear();
        config.sections.addAll(sections);
        AppConfig.save(this, config);
        if (showToast) Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        return true;
    }

    private List<MessageSection> readSectionsFromEditors() {
        List<MessageSection> sections = new ArrayList<>();
        for (SectionEditor editor : sectionEditors) {
            MessageSection section = editor.read();
            if (!section.options.isEmpty()) sections.add(section);
        }
        return sections;
    }

    private void previewMessage() {
        List<MessageSection> previewSections = readSectionsFromEditors();
        if (previewSections.isEmpty()) {
            showError("Message required", "Add at least one section with at least one option.");
            return;
        }
        String previewPhoneColumn = phoneSpinner.getSelectedItem() == null
                ? config.phoneColumn : phoneSpinner.getSelectedItem().toString();
        String previewNameColumn = nameSpinner.getSelectedItem() == null
                ? config.nameColumn : nameSpinner.getSelectedItem().toString();
        setBusy(true);
        io.execute(() -> {
            try {
                Map<String, String> row = new LinkedHashMap<>();
                boolean usedCsvRow = false;
                if (config.hasImportedCsv(this)) {
                    File file = new File(getFilesDir(), AppConfig.CONTACTS_FILE);
                    List<Map<String, String>> rows = CsvUtils.readRange(file,
                            Math.min(config.progress, Math.max(0, config.totalRows - 1)), 1);
                    if (rows.isEmpty()) rows = CsvUtils.readRange(file, 0, 1);
                    if (!rows.isEmpty()) {
                        row.putAll(rows.get(0));
                        usedCsvRow = true;
                    }
                }
                if (!usedCsvRow) {
                    for (String header : config.headers) row.put(header, "[" + header + "]");
                    if (!previewNameColumn.isEmpty()) row.put(previewNameColumn, "Alex Morgan");
                    if (!previewPhoneColumn.isEmpty()) row.put(previewPhoneColumn, "2075550100");
                    row.put("first_name", "Alex");
                    row.put("name", "Alex Morgan");
                    row.put("phone", "2075550100");
                    row.put("phone_number", "2075550100");
                }
                String message = MessageComposer.compose(previewSections, row,
                        previewNameColumn, previewPhoneColumn, new Random());
                if (message.trim().isEmpty()) throw new IllegalStateException("The generated preview was blank.");
                if (MessageComposer.containsUnresolvedPlaceholder(message)) {
                    message += "\\n\\nWarning: an unknown {placeholder} remains. Match it to a CSV heading before sending.";
                }
                String finalMessage = message;
                boolean finalUsedCsvRow = usedCsvRow;
                runOnUiThread(() -> {
                    setBusy(false);
                    new AlertDialog.Builder(this)
                            .setTitle(finalUsedCsvRow ? "Generated preview — next contact" : "Generated preview — sample values")
                            .setMessage(finalMessage)
                            .setPositiveButton("Close", null)
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError("Preview failed", error.getMessage());
                });
            }
        });
    }

'''
settings = replace_once(
    settings,
    r'    private boolean saveSettings\(boolean showToast\) \{.*?\n    private static int parseInt',
    settings_methods + '    private static int parseInt',
    'SettingsActivity preview/save methods')
settings_path.write_text(settings)

# 2) Replace the fire-and-forget send with a carrier/modem-confirmed send.
service_path = JAVA / 'BatchSmsService.java'
service = service_path.read_text().replace('sendSms(number, message);', 'SmsSendHelper.sendAndWait(this, number, message);')
service_path.write_text(service)

sms_helper = r'''package com.austinstrat.teamtexts;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class SmsSendHelper {
    private SmsSendHelper() {}

    static void sendAndWait(Context context, String number, String message) throws InterruptedException {
        SmsManager base = context.getSystemService(SmsManager.class);
        if (base == null) throw new IllegalStateException("Android SMS service is unavailable.");

        int subId = SmsManager.getDefaultSmsSubscriptionId();
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            throw new IllegalStateException("No default SMS SIM is selected. Choose a default SIM for SMS in Android Settings, then try again.");
        }
        SmsManager manager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? base.createForSubscriptionId(subId)
                : SmsManager.getSmsManagerForSubscriptionId(subId);

        ArrayList<String> parts = manager.divideMessage(message);
        if (parts == null || parts.isEmpty()) {
            parts = new ArrayList<>();
            parts.add(message);
        }

        CountDownLatch latch = new CountDownLatch(parts.size());
        AtomicInteger firstFailure = new AtomicInteger(Activity.RESULT_OK);
        AtomicInteger radioError = new AtomicInteger(0);
        AtomicBoolean noDefault = new AtomicBoolean(false);
        String token = Long.toHexString(System.nanoTime()) + "_" + Integer.toHexString(number.hashCode());
        String action = context.getPackageName() + ".SMS_SENT." + token;

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                int result = getResultCode();
                if (result != Activity.RESULT_OK) firstFailure.compareAndSet(Activity.RESULT_OK, result);
                if (intent != null) {
                    if (intent.getBooleanExtra("noDefault", false)) noDefault.set(true);
                    if (intent.hasExtra("errorCode")) radioError.compareAndSet(0, intent.getIntExtra("errorCode", 0));
                }
                latch.countDown();
            }
        };

        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter);
        try {
            if (parts.size() == 1) {
                manager.sendTextMessage(number, null, message, sentIntent(context, action, token, 0), null);
            } else {
                ArrayList<PendingIntent> sent = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) sent.add(sentIntent(context, action, token, i));
                manager.sendMultipartTextMessage(number, null, parts, sent, null);
            }
            if (!latch.await(90, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Android did not return an SMS result within 90 seconds. The saved position was not advanced.");
            }
            int result = firstFailure.get();
            if (result != Activity.RESULT_OK) throw new IllegalStateException(describe(result, noDefault.get(), radioError.get()));
        } finally {
            try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {}
        }
    }

    private static PendingIntent sentIntent(Context context, String action, String token, int part) {
        Intent intent = new Intent(action)
                .setPackage(context.getPackageName())
                .setData(Uri.parse("teamtexts://sms-sent/" + token + "/" + part));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(context, (token + ':' + part).hashCode(), intent, flags);
    }

    private static String describe(int result, boolean noDefault, int radioError) {
        if (noDefault) return "Android could not choose an SMS SIM. Set a default SIM for text messages and try again.";
        String detail;
        if (result == SmsManager.RESULT_ERROR_RADIO_OFF) detail = "The phone radio is off or airplane mode is enabled.";
        else if (result == SmsManager.RESULT_ERROR_NO_SERVICE) detail = "There is no cellular service.";
        else if (result == SmsManager.RESULT_ERROR_LIMIT_EXCEEDED) detail = "Android or the carrier blocked the send because too many texts were attempted.";
        else if (result == SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE) detail = "The SIM's fixed-dialing rules blocked this number.";
        else if (result == SmsManager.RESULT_RIL_SIM_ABSENT) detail = "No active SIM was available.";
        else if (result == SmsManager.RESULT_RIL_REQUEST_RATE_LIMITED) detail = "The modem rate-limited the SMS request.";
        else detail = "Android or the carrier rejected the SMS request.";
        String codes = " Result code: " + result;
        if (radioError != 0) codes += ", radio error: " + radioError;
        return detail + codes + ". The saved position was not advanced.";
    }
}
'''
(JAVA / 'SmsSendHelper.java').write_text(sms_helper)

# 3) Make service-launch failures visible instead of silently closing.
go_path = JAVA / 'GoActivity.java'
go = go_path.read_text()
go_method = r'''    private void startBatch() {
        status.setText("Starting SMS service…");
        Intent service = new Intent(this, BatchSmsService.class).setAction(BatchSmsService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            status.setText("Batch started");
            finishSoon();
        } catch (RuntimeException error) {
            status.setText("Could not start the batch");
            new AlertDialog.Builder(this)
                    .setTitle("Batch could not start")
                    .setMessage(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                    .setPositiveButton("Close", (dialog, which) -> finish())
                    .show();
        }
    }

'''
go = replace_once(go, r'    private void startBatch\(\) \{.*?\n    private void finishSoon', go_method + '    private void finishSoon', 'GoActivity startBatch')
go_path.write_text(go)

# 4) Version bump.
gradle_path = ROOT / 'app/build.gradle'
gradle = gradle_path.read_text().replace('versionCode 1', 'versionCode 2').replace("versionName '1.0.0'", "versionName '1.1.0'")
gradle_path.write_text(gradle)

print('Applied Team Morning Texts v1.1 fixes')
