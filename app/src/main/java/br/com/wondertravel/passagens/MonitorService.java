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
import java.time.LocalDate;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MonitorService extends Service {
    public static final String ACTION_STOP = "br.com.wondertravel.passagens.STOP_MONITOR";
    public static final String ACTION_RELOAD = "br.com.wondertravel.passagens.RELOAD_MONITOR";
    private static final String STATUS_CHANNEL = "monitor_status_visible_v2";
    private static final String ALERT_CHANNEL = "price_alerts";
    private static final int STATUS_NOTIFICATION_ID = 7001;
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?i)(\\d{1,2})\\s+(jan|fev|mar|abr|mai|jun|jul|ago|set|out|nov|dez)"
                    + "\\s+(\\d{1,3}(?:\\.\\d{3})+)\\s+milhas"
    );

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, PriceResult> results = new LinkedHashMap<>();
    private WebView webView;
    private SearchConfig config;
    private List<SearchConfig.Task> tasks;
    private int taskIndex = -1;
    private boolean scanning;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        createWebView();
        startForeground(STATUS_NOTIFICATION_ID, buildStatusNotification("Monitor iniciando..."));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            handler.removeCallbacksAndMessages(null);
            scanning = false;
            taskIndex = -1;
        }
        if (!scanning && taskIndex < 0) startScan();
        return START_STICKY;
    }

    private void createWebView() {
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (scanning && url.contains("/mfe/emissao-passagem")) {
                    handler.postDelayed(() -> inspect(0), 7000);
                }
            }
        });
    }

    private void startScan() {
        handler.removeCallbacks(nextScan);
        config = SearchConfig.load(this);
        try {
            tasks = config.createTasks();
        } catch (IllegalArgumentException error) {
            updateStatus("Configuração inválida — abra o aplicativo.");
            handler.postDelayed(nextScan, 60L * 60L * 1000L);
            return;
        }
        scanning = true;
        results.clear();
        taskIndex = 0;
        updateStatus("Verificando 1/" + tasks.size() + "...");
        loadTask();
    }

    private void loadTask() {
        if (taskIndex >= tasks.size()) {
            finishScan();
            return;
        }
        SearchConfig.Task task = tasks.get(taskIndex);
        updateStatus("Verificando " + (taskIndex + 1) + "/" + tasks.size()
                + ": " + task.label);
        webView.loadUrl(buildUrl(task));
    }

    private String buildUrl(SearchConfig.Task task) {
        return "https://www.smiles.com.br/mfe/emissao-passagem/"
                + "?adults=" + config.adults + "&cabin=ECONOMIC&children=" + config.children
                + "&departureDate=" + task.departureTimestamp()
                + "&infants=0&isElegible=false&isFlexibleDateChecked=false"
                + "&returnDate=" + task.returnTimestamp()
                + "&searchType=g3&segments=1&tripType=1"
                + "&originAirport=" + task.from
                + "&originCity=&originCountry=&originAirportIsAny=false"
                + "&destinationAirport=" + task.to
                + "&destinCity=&destinCountry=&destinAirportIsAny=false"
                + "&novo-resultado-voos=true";
    }

    private void inspect(int attempt) {
        if (!scanning || taskIndex < 0 || taskIndex >= tasks.size()) return;
        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                encoded -> {
                    String text = decode(encoded);
                    int matches = text == null ? 0 : collectPrices(tasks.get(taskIndex), text);
                    if (matches > 0 && !text.contains("Aguarde enquanto buscamos")) advance();
                    else if (attempt >= 19) advance();
                    else handler.postDelayed(() -> inspect(attempt + 1), 3000);
                });
    }

    private String decode(String encoded) {
        try {
            Object value = new JSONTokener(encoded).nextValue();
            return value instanceof String ? (String) value : null;
        } catch (Exception error) {
            return null;
        }
    }

    private int collectPrices(SearchConfig.Task task, String text) {
        Matcher matcher = PRICE_PATTERN.matcher(text);
        int matches = 0;
        while (matcher.find()) {
            try {
                int day = Integer.parseInt(matcher.group(1));
                int month = monthNumber(matcher.group(2));
                int miles = Integer.parseInt(matcher.group(3).replace(".", ""));
                if (!task.accepts(day, month)) continue;
                for (LocalDate date : task.dates) {
                    if (date.getDayOfMonth() == day && date.getMonthValue() == month) {
                        String key = task.label + "|" + date;
                        PriceResult old = results.get(key);
                        if (old == null || miles < old.miles) {
                            results.put(key, new PriceResult(task.label, date, miles));
                        }
                        matches++;
                        break;
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return matches;
    }

    private int monthNumber(String value) {
        String[] months = {"jan","fev","mar","abr","mai","jun","jul","ago","set","out","nov","dez"};
        for (int i = 0; i < months.length; i++) {
            if (months[i].equals(value.toLowerCase(Locale.ROOT))) return i + 1;
        }
        return 0;
    }

    private void advance() {
        taskIndex++;
        handler.postDelayed(this::loadTask, 1500);
    }

    private void finishScan() {
        scanning = false;
        taskIndex = -1;
        String checkedAt = new SimpleDateFormat("dd/MM/yyyy 'às' HH:mm",
                new Locale("pt", "BR")).format(new Date());
        StringBuilder summary = new StringBuilder("Varredura automática em ")
                .append(checkedAt).append(".\n");
        PriceResult lowest = null;

        for (SearchConfig.Task task : tasks) {
            for (LocalDate date : task.dates) {
                String key = task.label + "|" + date;
                PriceResult result = results.get(key);
                summary.append(task.label).append(" | ").append(task.displayDate(date)).append(": ");
                if (result == null) summary.append("sem tarifa lida\n");
                else {
                    summary.append(format(result.miles)).append(" milhas\n");
                    if (lowest == null || result.miles < lowest.miles) lowest = result;
                    if (result.miles < config.targetMiles) showOffer(result);
                }
            }
        }

        if (lowest == null) {
            summary.append("Nenhuma tarifa foi identificada.");
            updateStatus("Monitor ativo — nenhuma tarifa lida.");
        } else {
            summary.append("Menor valor: ").append(format(lowest.miles)).append(" milhas — ")
                    .append(lowest.miles < config.targetMiles ? "OPORTUNIDADE!" :
                            "acima de " + format(config.targetMiles) + ".");
            updateStatus("Monitor ativo — menor valor: " + format(lowest.miles)
                    + " milhas. Próxima em " + config.intervalMinutes + " min.");
        }

        getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                .putString("last_scan", summary.toString().trim())
                .putLong("last_scan_at", System.currentTimeMillis())
                .apply();
        webView.loadUrl("about:blank");
        handler.postDelayed(nextScan, config.intervalMinutes * 60L * 1000L);
    }

    private final Runnable nextScan = this::startScan;

    private void showOffer(PriceResult result) {
        String alertKey = "alert_" + (result.route + "|" + result.date).hashCode();
        int previous = getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE)
                .getInt(alertKey, -1);
        if (previous == result.miles) return;
        getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                .putInt(alertKey, result.miles).apply();

        Notification notification = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Passagem abaixo de " + format(config.targetMiles))
                .setContentText(result.route + " em " + result.displayDate() + ": "
                        + format(result.miles) + " milhas por viajante.")
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify((result.route + result.date + result.miles).hashCode(), notification);
    }

    private String format(int value) {
        return NumberFormat.getIntegerInstance(new Locale("pt", "BR")).format(value);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            NotificationChannel status = new NotificationChannel(
                    STATUS_CHANNEL, "Monitor de passagens",
                    NotificationManager.IMPORTANCE_DEFAULT);
            status.setSound(null, null);
            status.enableVibration(false);
            status.setShowBadge(false);
            manager.createNotificationChannel(status);

            NotificationChannel alerts = new NotificationChannel(
                    ALERT_CHANNEL, "Alertas de oportunidades",
                    NotificationManager.IMPORTANCE_HIGH);
            manager.createNotificationChannel(alerts);
        }
    }

    private Notification buildStatusNotification(String message) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, MonitorService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, STATUS_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("WonderTravel monitor")
                .setContentText(message)
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Parar", stop)
                .build();
    }

    private void updateStatus(String message) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify(STATUS_NOTIFICATION_ID, buildStatusNotification(message));
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

    private static final class PriceResult {
        final String route;
        final LocalDate date;
        final int miles;

        PriceResult(String route, LocalDate date, int miles) {
            this.route = route;
            this.date = date;
            this.miles = miles;
        }

        String displayDate() {
            return String.format(Locale.getDefault(), "%02d/%02d/%04d",
                    date.getDayOfMonth(), date.getMonthValue(), date.getYear());
        }
    }
}
