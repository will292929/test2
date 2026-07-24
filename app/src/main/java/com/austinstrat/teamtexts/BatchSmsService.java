package com.austinstrat.teamtexts;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.SmsManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
            BatchStatus.Snapshot snapshot = BatchStatus.read(this);
            BatchStatus.update(this, BatchStatus.State.STOPPED,
                    "Stopping after the current SMS operation…",
                    snapshot.sentThisBatch, snapshot.batchTotal,
                    snapshot.overallProgress, snapshot.overallTotal, true);
            updateForeground("Stopping after the current SMS operation…", 0, 0, true);
            return START_NOT_STICKY;
        }
        if (!running.compareAndSet(false, true)) {
            return START_NOT_STICKY;
        }
        cancelRequested = false;
        try {
            startInForeground(buildNotification("Starting batch…", 0, 0, true));
            worker.execute(this::runBatch);
        } catch (Throwable error) {
            running.set(false);
            String message = safeMessage(error);
            BatchStatus.update(this, BatchStatus.State.ERROR, message, 0, 0, 0, 0, false);
            showResult("Batch error", message, true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void runBatch() {
        acquireWakeLock();
        String title = "Batch stopped";
        String result = "No messages were sent.";
        boolean error = false;
        int sent = 0;
        int batchTotal = 0;
        AppConfig.Config config = null;
        try {
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                throw new IllegalStateException("SMS permission is not granted.");
            }
            config = AppConfig.load(this);
            validateConfig(config);
            List<Map<String, String>> rows = CsvUtils.readRange(
                    new File(getFilesDir(), AppConfig.CONTACTS_FILE), config.progress, config.batchSize);
            if (rows.isEmpty()) {
                title = "List complete";
                result = "All " + config.totalRows + " contacts have been processed.";
                BatchStatus.update(this, BatchStatus.State.COMPLETE, result,
                        0, 0, config.progress, config.totalRows, false);
                return;
            }
            batchTotal = rows.size();
            BatchStatus.update(this, BatchStatus.State.STARTING,
                    "Preparing " + batchTotal + " text" + plural(batchTotal) + "…",
                    0, batchTotal, config.progress, config.totalRows, true);
            Random random = new Random();
            for (int i = 0; i < rows.size(); i++) {
                if (cancelRequested) {
                    title = "Batch stopped";
                    result = "Stopped after " + sent + " text" + plural(sent) + ".";
                    BatchStatus.update(this, BatchStatus.State.STOPPED, result,
                            sent, batchTotal, config.progress, config.totalRows, false);
                    return;
                }
                Map<String, String> row = rows.get(i);
                int csvRow = config.progress + 2;
                String raw = row.get(config.phoneColumn);
                String number = normalizePhone(raw);
                if (!VALID_PHONE.matcher(number).matches()) {
                    throw new IllegalStateException("Invalid phone number on CSV row " + csvRow + ": "
                            + (raw == null || raw.trim().isEmpty() ? "blank" : raw));
                }
                String message = MessageComposer.compose(config.sections, row,
                        config.nameColumn, config.phoneColumn, random);
                if (message.isEmpty()) {
                    throw new IllegalStateException("The generated message for CSV row " + csvRow + " was blank.");
                }
                if (MessageComposer.containsUnresolvedPlaceholder(message)) {
                    throw new IllegalStateException("An unknown {placeholder} remains for CSV row " + csvRow + ".");
                }

                String sendingMessage = "Sending to row " + csvRow + "…";
                BatchStatus.update(this, BatchStatus.State.SENDING, sendingMessage,
                        sent, batchTotal, config.progress, config.totalRows, true);
                updateForeground(sendingMessage, sent, batchTotal, false);

                sendSmsAndWait(number, message);
                config.progress++;
                sent++;
                AppConfig.setProgress(this, config.progress);
                String sentMessage = "Sent " + sent + " of " + batchTotal;
                BatchStatus.update(this, BatchStatus.State.SENDING, sentMessage,
                        sent, batchTotal, config.progress, config.totalRows, true);
                updateForeground(sentMessage + " • " + config.progress + " of "
                        + config.totalRows + " total", sent, batchTotal, false);

                if (i < rows.size() - 1 && config.progress < config.totalRows) {
                    int delay = randomDelay(config.minDelaySeconds, config.maxDelaySeconds);
                    sleepWithCancellation(delay, sent, batchTotal, config);
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
            BatchStatus.update(this, BatchStatus.State.COMPLETE, result,
                    sent, batchTotal, config.progress, config.totalRows, false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            title = "Batch stopped";
            result = "The batch was interrupted. Saved progress was preserved.";
            int progress = config == null ? 0 : config.progress;
            int total = config == null ? 0 : config.totalRows;
            BatchStatus.update(this, BatchStatus.State.STOPPED, result,
                    sent, batchTotal, progress, total, false);
        } catch (Throwable failure) {
            error = true;
            title = "Batch error";
            result = safeMessage(failure);
            int progress = config == null ? 0 : config.progress;
            int total = config == null ? 0 : config.totalRows;
            BatchStatus.update(this, BatchStatus.State.ERROR, result,
                    sent, batchTotal, progress, total, false);
        } finally {
            releaseWakeLock();
            running.set(false);
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (RuntimeException ignored) {}
            showResult(title, result, error);
            stopSelf();
        }
    }

    private void validateConfig(AppConfig.Config c) {
        if (!c.hasImportedCsv(this)) throw new IllegalStateException("No CSV is imported.");
        if (c.phoneColumn.isEmpty()) throw new IllegalStateException("Phone-number column is not selected.");
        if (c.sections.isEmpty()) throw new IllegalStateException("No message sections are configured.");
        if (c.batchSize < 1) throw new IllegalStateException("Batch size must be at least 1.");
        if (c.minDelaySeconds < 0 || c.maxDelaySeconds < c.minDelaySeconds) {
            throw new IllegalStateException("The random delay range is invalid.");
        }
        if (c.progress >= c.totalRows) throw new IllegalStateException("The imported list is already complete.");
    }

    private void sendSmsAndWait(String number, String message) throws Exception {
        SmsManager manager = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? getSystemService(SmsManager.class) : SmsManager.getDefault();
        if (manager == null) throw new IllegalStateException("Android SMS service is unavailable.");
        ArrayList<String> parts = manager.divideMessage(message);
        if (parts == null || parts.isEmpty()) {
            parts = new ArrayList<>();
            parts.add(message);
        }

        String action = getPackageName() + ".SMS_SENT." + System.nanoTime();
        CountDownLatch latch = new CountDownLatch(parts.size());
        AtomicInteger firstError = new AtomicInteger(Activity.RESULT_OK);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int code = getResultCode();
                if (code != Activity.RESULT_OK) firstError.compareAndSet(Activity.RESULT_OK, code);
                latch.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        try {
            ArrayList<PendingIntent> sentIntents = new ArrayList<>();
            int requestBase = (int) (System.nanoTime() & 0x3fffffff);
            for (int i = 0; i < parts.size(); i++) {
                Intent sent = new Intent(action).setPackage(getPackageName()).putExtra("part", i);
                sentIntents.add(PendingIntent.getBroadcast(this, requestBase + i, sent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            }
            if (parts.size() == 1) {
                manager.sendTextMessage(number, null, message, sentIntents.get(0), null);
            } else {
                manager.sendMultipartTextMessage(number, null, parts, sentIntents, null);
            }
            if (!latch.await(90, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Android did not return an SMS result within 90 seconds.");
            }
            int result = firstError.get();
            if (result != Activity.RESULT_OK) {
                throw new IllegalStateException(describeSmsError(result));
            }
        } finally {
            try { unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        }
    }

    private static String describeSmsError(int code) {
        switch (code) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                return "Android reported a generic SMS failure. Check the active SIM and carrier service.";
            case SmsManager.RESULT_ERROR_RADIO_OFF:
                return "The phone radio is off. Disable airplane mode and try again.";
            case SmsManager.RESULT_ERROR_NULL_PDU:
                return "Android could not create the SMS data packet.";
            case SmsManager.RESULT_ERROR_NO_SERVICE:
                return "No cellular SMS service is currently available.";
            case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED:
                return "Android or the carrier blocked the text because an SMS sending limit was reached.";
            case SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE:
                return "The SIM's fixed-dialing restrictions blocked this number.";
            case SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED:
                return "The carrier did not allow this short-code SMS.";
            case SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED:
                return "Short-code SMS is disabled on this phone.";
            default:
                return "Android rejected the SMS with result code " + code + ".";
        }
    }

    private static String normalizePhone(String raw) {
        if (raw == null) return "";
        String value = raw.trim();
        if (value.matches("[0-9]+\\.0+")) value = value.substring(0, value.indexOf('.'));
        StringBuilder out = new StringBuilder();
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
            String message = "Next text in " + shown + " second" + (shown == 1 ? "" : "s");
            BatchStatus.update(this, BatchStatus.State.WAITING, message,
                    sent, batchTotal, config.progress, config.totalRows, true);
            updateForeground("Sent " + sent + " of " + batchTotal + " • " + message,
                    sent, batchTotal, false);
            long chunk = Math.min(1000L, remaining);
            Thread.sleep(chunk);
            remaining -= chunk;
        }
    }

    private void startInForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FOREGROUND_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(FOREGROUND_ID, notification);
        }
    }

    private void updateForeground(String message, int progress, int max, boolean indeterminate) {
        getSystemService(NotificationManager.class).notify(
                FOREGROUND_ID, buildNotification(message, progress, max, indeterminate));
    }

    private Notification buildNotification(String message, int progress, int max, boolean indeterminate) {
        PendingIntent open = PendingIntent.getActivity(this, 10,
                new Intent(this, GoActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 11,
                new Intent(this, BatchSmsService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_stat_sms), "Stop", stop).build();
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle("Sending team texts")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(stopAction);
        if (max > 0) builder.setProgress(max, progress, indeterminate);
        return builder.build();
    }

    private void showResult(String title, String message, boolean error) {
        try {
            PendingIntent open = PendingIntent.getActivity(this, 12,
                    new Intent(this, GoActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_sms)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(error ? Notification.CATEGORY_ERROR : Notification.CATEGORY_STATUS)
                    .build();
            getSystemService(NotificationManager.class).notify(RESULT_ID, notification);
        } catch (RuntimeException ignored) {}
    }

    private void acquireWakeLock() {
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (power == null) return;
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":TextBatch");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private static String plural(int value) { return value == 1 ? "" : "s"; }

    private static String safeMessage(Throwable error) {
        if (error == null) return "Unknown error";
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Override public void onDestroy() {
        cancelRequested = true;
        worker.shutdownNow();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
