package com.austinstrat.teamtexts;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.SmsManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class BatchSmsService extends Service {
    public static final String ACTION_START = "com.austinstrat.teamtexts.START_BATCH";
    public static final String ACTION_STOP = "com.austinstrat.teamtexts.STOP_BATCH";
    private static final String CHANNEL_ID = "team_text_batch";
    private static final int FOREGROUND_ID = 1201;
    private static final int RESULT_ID = 1202;
    private static final Pattern VALID_PHONE = Pattern.compile("^\\+?[0-9]{7,15}$");
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean cancelRequested;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Text batch status", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progress and completion notices for manually started SMS batches.");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            cancelRequested = true;
            updateForeground("Stopping after the current step…", 0, 0, true);
            return START_NOT_STICKY;
        }
        if (!running.compareAndSet(false, true)) {
            updateForeground("A batch is already running", 0, 0, true);
            return START_NOT_STICKY;
        }
        cancelRequested = false;
        startInForeground(buildNotification("Starting batch…", 0, 0, true));
        worker.execute(this::runBatch);
        return START_NOT_STICKY;
    }

    private void runBatch() {
        acquireWakeLock();
        String title = "Batch stopped";
        String result = "No messages were sent.";
        boolean error = false;
        try {
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
                throw new IllegalStateException("SMS permission is not granted.");
            AppConfig.Config config = AppConfig.load(this);
            validateConfig(config);
            List<Map<String, String>> rows = CsvUtils.readRange(
                    new File(getFilesDir(), AppConfig.CONTACTS_FILE), config.progress, config.batchSize);
            if (rows.isEmpty()) {
                title = "List complete";
                result = "All " + config.totalRows + " contacts have been processed.";
                return;
            }
            int sent = 0;
            Random random = new Random();
            for (int i = 0; i < rows.size(); i++) {
                if (cancelRequested) {
                    title = "Batch stopped";
                    result = "Stopped after " + sent + " text" + plural(sent) + ".";
                    return;
                }
                Map<String, String> row = rows.get(i);
                int csvRow = config.progress + 2;
                String raw = row.get(config.phoneColumn);
                String number = normalizePhone(raw);
                if (!VALID_PHONE.matcher(number).matches())
                    throw new IllegalStateException("Invalid phone number on CSV row " + csvRow + ": "
                            + (raw == null ? "blank" : raw));
                String message = MessageComposer.compose(config.sections, row,
                        config.nameColumn, config.phoneColumn, random);
                if (message.isEmpty())
                    throw new IllegalStateException("The generated message for CSV row " + csvRow + " was blank.");
                if (MessageComposer.containsUnresolvedPlaceholder(message))
                    throw new IllegalStateException("An unknown {placeholder} remains for CSV row " + csvRow + ".");
                sendSms(number, message);
                config.progress++;
                sent++;
                AppConfig.setProgress(this, config.progress);
                int total = Math.min(config.batchSize, rows.size());
                updateForeground("Sent " + sent + " of " + total + " • " + config.progress
                        + " of " + config.totalRows + " total", sent, total, false);
                if (i < rows.size() - 1 && config.progress < config.totalRows) {
                    int delay = randomDelay(config.minDelaySeconds, config.maxDelaySeconds);
                    sleepWithCancellation(delay, sent, total, config);
                }
            }
            if (config.progress >= config.totalRows) {
                title = "List complete";
                result = "Sent " + sent + " text" + plural(sent) + ". All "
                        + config.totalRows + " contacts are complete.";
            } else {
                title = "Batch complete";
                result = "Sent " + sent + " text" + plural(sent) + ". Next position: "
                        + (config.progress + 1) + " of " + config.totalRows + ".";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            title = "Batch stopped";
            result = "The batch was interrupted. Saved progress was preserved.";
        } catch (Exception e) {
            error = true;
            title = "Batch error";
            result = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            releaseWakeLock();
            running.set(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            showResult(title, result, error);
            stopSelf();
        }
    }

    private void validateConfig(AppConfig.Config c) {
        if (!c.hasImportedCsv(this)) throw new IllegalStateException("No CSV is imported.");
        if (c.phoneColumn.isEmpty()) throw new IllegalStateException("Phone-number column is not selected.");
        if (c.sections.isEmpty()) throw new IllegalStateException("No message sections are configured.");
        if (c.batchSize < 1) throw new IllegalStateException("Batch size must be at least 1.");
        if (c.minDelaySeconds < 0 || c.maxDelaySeconds < c.minDelaySeconds)
            throw new IllegalStateException("The random delay range is invalid.");
        if (c.progress >= c.totalRows) throw new IllegalStateException("The imported list is already complete.");
    }

    private void sendSms(String number, String message) {
        SmsManager manager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? getSystemService(SmsManager.class) : SmsManager.getDefault();
        if (manager == null) throw new IllegalStateException("Android SMS service is unavailable.");
        ArrayList<String> parts = manager.divideMessage(message);
        if (parts.size() <= 1) manager.sendTextMessage(number, null, message, null, null);
        else manager.sendMultipartTextMessage(number, null, parts, null, null);
    }

    private static String normalizePhone(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        String value = raw.trim();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isDigit(ch)) out.append(ch);
            else if (ch == '+' && out.length() == 0) out.append(ch);
        }
        return out.toString();
    }

    private static int randomDelay(int min, int max) {
        return max <= min ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private void sleepWithCancellation(int seconds, int sent, int batchTotal,
            AppConfig.Config config) throws InterruptedException {
        long remaining = seconds * 1000L;
        while (remaining > 0) {
            if (cancelRequested) return;
            long shown = (remaining + 999L) / 1000L;
            updateForeground("Sent " + sent + " of " + batchTotal + " • next text in "
                    + shown + "s • " + config.progress + " of " + config.totalRows + " total",
                    sent, batchTotal, false);
            long chunk = Math.min(1000L, remaining);
            Thread.sleep(chunk);
            remaining -= chunk;
        }
    }

    private void startInForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(FOREGROUND_ID, notification);
    }

    private void updateForeground(String message, int progress, int max, boolean indeterminate) {
        getSystemService(NotificationManager.class).notify(
                FOREGROUND_ID, buildNotification(message, progress, max, indeterminate));
    }

    private Notification buildNotification(String message, int progress, int max, boolean indeterminate) {
        PendingIntent open = PendingIntent.getActivity(this, 10,
                new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 11,
                new Intent(this, BatchSmsService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle("Sending team texts")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        if (max > 0) b.setProgress(max, progress, indeterminate);
        return b.build();
    }

    private void showResult(String title, String message, boolean error) {
        PendingIntent open = PendingIntent.getActivity(this, 12,
                new Intent(this, SettingsActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(error ? Notification.CATEGORY_ERROR : Notification.CATEGORY_STATUS)
                .build();
        getSystemService(NotificationManager.class).notify(RESULT_ID, n);
    }

    private void acquireWakeLock() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":TextBatch");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private static String plural(int value) { return value == 1 ? "" : "s"; }

    @Override public void onDestroy() {
        cancelRequested = true;
        worker.shutdownNow();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
