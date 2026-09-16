package br.com.wondertravel.passagens;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONTokener;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MonitorService extends Service {
    public static final String ACTION_STOP =
            "br.com.wondertravel.passagens.STOP_MONITOR";
    private static final String STATUS_CHANNEL = "monitor_status_visible_v2";
    private static final String ALERT_CHANNEL = "price_alerts";
    private static final int STATUS_NOTIFICATION_ID = 7001;
    private static final int TARGET_MILES = 31_000;
    private static final long ONE_HOUR = 60L * 60L * 1000L;
    private static final Pattern DATE_PRICE_PATTERN = Pattern.compile(
            "(?i)(\\d{1,2})\\s+out\\s+(\\d{1,3}(?:\\.\\d{3})+)\\s+milhas"
    );

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, PriceResult> results = new LinkedHashMap<>();
    private final List<SearchTask> tasks = Arrays.asList(
            new SearchTask("GRU", "BPS", "GRU → BPS",
                    "1791601200000", "1792206000000", 9, 10, 11),
            new SearchTask("CGH", "BPS", "CGH → BPS",
                    "1791601200000", "1792206000000", 9, 10, 11),
            new SearchTask("BPS", "GRU", "BPS → GRU",
                    "1792206000000", "1792292400000", 17, 18),
            new SearchTask("BPS", "CGH", "BPS → CGH",
                    "1792206000000", "1792292400000", 17, 18)
    );

    private WebView webView;
    private int taskIndex = -1;
    private boolean scanning;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        createWebView();
        startForeground(
                STATUS_NOTIFICATION_ID,
                buildStatusNotification("Monitor iniciando...")
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!scanning && taskIndex < 0) {
            startScan();
        }
        return START_STICKY;
    }

    private void createWebView() {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (scanning && url.contains("/mfe/emissao-passagem")) {
                    handler.postDelayed(() -> inspectTask(0), 7_000);
                }
            }
        });
    }

    private void startScan() {
        handler.removeCallbacks(startNextScheduledScan);
        scanning = true;
        results.clear();
        taskIndex = 0;
        updateStatus("Verificando 1/4...");
        loadTask();
    }

    private void loadTask() {
        if (taskIndex >= tasks.size()) {
            finishScan();
            return;
        }
        updateStatus("Verificando " + (taskIndex + 1) + "/4: "
                + tasks.get(taskIndex).label);
        webView.loadUrl(buildSearchUrl(tasks.get(taskIndex)));
    }

    private String buildSearchUrl(SearchTask task) {
        return "https://www.smiles.com.br/mfe/emissao-passagem/"
                + "?adults=2&cabin=ECONOMIC&children=2"
                + "&departureDate=" + task.departureTimestamp
                + "&infants=0&isElegible=false"
                + "&isFlexibleDateChecked=false"
                + "&returnDate=" + task.returnTimestamp
                + "&searchType=g3&segments=1&tripType=1"
                + "&originAirport=" + task.from
                + "&originCity=&originCountry=&originAirportIsAny=false"
                + "&destinationAirport=" + task.to
                + "&destinCity=&destinCountry=&destinAirportIsAny=false"
                + "&novo-resultado-voos=true";
    }

    private void inspectTask(int attempt) {
        if (!scanning || taskIndex < 0 || taskIndex >= tasks.size()) {
            return;
        }

        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                encoded -> {
                    String text = decode(encoded);
                    if (text == null) {
                        retryOrAdvance(attempt);
                        return;
                    }

                    int matches = collectPrices(tasks.get(taskIndex), text);
                    if (matches > 0
                            && !text.contains("Aguarde enquanto buscamos")) {
                        advance();
                    } else {
                        retryOrAdvance(attempt);
                    }
                }
        );
    }

    private String decode(String encoded) {
        try {
            Object value = new JSONTokener(encoded).nextValue();
            return value instanceof String ? (String) value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int collectPrices(SearchTask task, String text) {
        Matcher matcher = DATE_PRICE_PATTERN.matcher(text);
        int matches = 0;

        while (matcher.find()) {
            try {
                int day = Integer.parseInt(matcher.group(1));
                int miles = Integer.parseInt(
                        matcher.group(2).replace(".", "")
                );
                if (!task.accepts(day)) {
                    continue;
                }

                String key = task.label + "|" + day;
                PriceResult existing = results.get(key);
                if (existing == null || miles < existing.miles) {
                    results.put(
                            key,
                            new PriceResult(task.label, day, miles)
                    );
                }
                matches++;
            } catch (NumberFormatException ignored) {
                // Ignora textos que não representam tarifas válidas.
            }
        }
        return matches;
    }

    private void retryOrAdvance(int attempt) {
        if (attempt >= 19) {
            advance();
        } else {
            handler.postDelayed(() -> inspectTask(attempt + 1), 3_000);
        }
    }

    private void advance() {
        taskIndex++;
        handler.postDelayed(this::loadTask, 1_500);
    }

    private void finishScan() {
        scanning = false;
        taskIndex = -1;

        String checkedAt = new SimpleDateFormat(
                "dd/MM/yyyy 'às' HH:mm",
                new Locale("pt", "BR")
        ).format(new Date());
        StringBuilder summary = new StringBuilder(
                "Varredura automática em " + checkedAt + ".\n"
        );
        PriceResult lowest = null;

        for (SearchTask task : tasks) {
            for (int day : task.days) {
                PriceResult result =
                        results.get(task.label + "|" + day);
                summary.append(task.label)
                        .append(" | ")
                        .append(String.format(
                                Locale.getDefault(), "%02d/10", day
                        ))
                        .append(": ");

                if (result == null) {
                    summary.append("sem tarifa lida\n");
                    continue;
                }

                summary.append(format(result.miles))
                        .append(" milhas\n");
                if (lowest == null || result.miles < lowest.miles) {
                    lowest = result;
                }
                if (result.miles < TARGET_MILES) {
                    showOffer(result);
                }
            }
        }

        if (lowest == null) {
            summary.append("Nenhuma tarifa foi identificada.");
            updateStatus("Monitor ativo — nenhuma tarifa lida.");
        } else {
            summary.append("Menor valor: ")
                    .append(format(lowest.miles))
                    .append(" milhas — ")
                    .append(lowest.miles < TARGET_MILES
                            ? "OPORTUNIDADE!"
                            : "acima de 31.000.");
            updateStatus("Monitor ativo — menor valor: "
                    + format(lowest.miles) + " milhas.");
        }

        getSharedPreferences("monitor", MODE_PRIVATE)
                .edit()
                .putString("last_scan", summary.toString().trim())
                .putLong("last_scan_at", System.currentTimeMillis())
                .apply();

        webView.loadUrl("about:blank");
        handler.postDelayed(startNextScheduledScan, ONE_HOUR);
    }

    private final Runnable startNextScheduledScan = this::startScan;

    private String format(int miles) {
        return NumberFormat.getIntegerInstance(
                new Locale("pt", "BR")
        ).format(miles);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            NotificationChannel status = new NotificationChannel(
                    STATUS_CHANNEL,
                    "Monitor de passagens",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            status.setSound(null, null);
            status.enableVibration(false);
            status.setShowBadge(false);
            status.setDescription(
                    "Mantém a verificação horária ativa."
            );
            manager.createNotificationChannel(status);

            NotificationChannel alerts = new NotificationChannel(
                    ALERT_CHANNEL,
                    "Alertas de oportunidades",
                    NotificationManager.IMPORTANCE_HIGH
            );
            manager.createNotificationChannel(alerts);
        }
    }

    private Notification buildStatusNotification(String message) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, MonitorService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
                        | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(this, STATUS_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("WonderTravel monitor")
                .setContentText(message)
                .setContentIntent(openPending)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Parar",
                        stopPending
                )
                .build();
    }

    private void updateStatus(String message) {
        NotificationManager manager =
                (NotificationManager) getSystemService(
                        Context.NOTIFICATION_SERVICE
                );
        manager.notify(
                STATUS_NOTIFICATION_ID,
                buildStatusNotification(message)
        );
    }

    private void showOffer(PriceResult result) {
        String date = String.format(
                Locale.getDefault(), "%02d/10", result.day
        );
        Notification notification =
                new Notification.Builder(this, ALERT_CHANNEL)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Passagem abaixo de 31 mil")
                        .setContentText(result.route + " em " + date
                                + ": " + format(result.miles)
                                + " milhas.")
                        .setAutoCancel(true)
                        .build();

        NotificationManager manager =
                (NotificationManager) getSystemService(
                        Context.NOTIFICATION_SERVICE
                );
        manager.notify(
                (result.route + result.day + result.miles).hashCode(),
                notification
        );
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static final class SearchTask {
        final String from;
        final String to;
        final String label;
        final String departureTimestamp;
        final String returnTimestamp;
        final int[] days;

        SearchTask(
                String from,
                String to,
                String label,
                String departureTimestamp,
                String returnTimestamp,
                int... days
        ) {
            this.from = from;
            this.to = to;
            this.label = label;
            this.departureTimestamp = departureTimestamp;
            this.returnTimestamp = returnTimestamp;
            this.days = days;
        }

        boolean accepts(int day) {
            for (int accepted : days) {
                if (accepted == day) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class PriceResult {
        final String route;
        final int day;
        final int miles;

        PriceResult(String route, int day, int miles) {
            this.route = route;
            this.day = day;
            this.miles = miles;
        }
    }
}
