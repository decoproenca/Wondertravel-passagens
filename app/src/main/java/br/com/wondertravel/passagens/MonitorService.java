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
import android.os.PowerManager;
import android.os.SystemClock;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONTokener;

import java.net.URLEncoder;
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
    private static final long TASK_HARD_TIMEOUT_MS = 75000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, FlightParser.Result> results = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final Map<String, FlightParser.Result> latamResults = new LinkedHashMap<>();
    private final Map<String, String> latamFailures = new LinkedHashMap<>();
    private WebView webView;
    private SearchConfig config;
    private List<SearchConfig.Task> tasks;
    private int taskIndex = -1;
    private boolean scanning;
    private boolean scanningLatam;
    private boolean includeSmiles;
    private boolean includeLatam;
    private int currentHttpStatus;
    private String currentWebViewError;
    private long scanStartedAt;
    private PowerManager.WakeLock scanWakeLock;

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
            getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                    .putBoolean("monitor_enabled", false).apply();
            releaseScanWakeLock();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                    .putBoolean("monitor_enabled", true).apply();
            handler.removeCallbacksAndMessages(null);
            scanning = false;
            taskIndex = -1;
        }
        if (intent == null && !getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE)
                .getBoolean("monitor_enabled", false)) {
            stopSelf();
            return START_NOT_STICKY;
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
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                boolean expectedPage = scanningLatam
                        ? url.contains("/oferta-voos")
                        : url.contains("/mfe/emissao-passagem");
                if (scanning && expectedPage) {
                    int expectedTaskIndex = taskIndex;
                    boolean expectedLatam = scanningLatam;
                    handler.postDelayed(() -> inspect(0, expectedTaskIndex, expectedLatam),
                            scanningLatam ? 7000 : SearchDiagnostics.FIRST_INSPECTION_DELAY_MS);
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                int status = response.getStatusCode();
                if (scanning && request.isForMainFrame()
                        && (status == 403 || status == 429 || status >= 500)) {
                    currentHttpStatus = status;
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (scanning && request.isForMainFrame()) {
                    currentWebViewError = "WebView " + error.getErrorCode()
                            + " — " + error.getDescription();
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
        includeSmiles = config.usesSmiles();
        includeLatam = config.usesLatam();
        if (includeLatam && !getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE)
                .getBoolean("latam_session_connected", false)) {
            scanning = false;
            updateStatus("LATAM desconectada — abra o app para conectar.");
            handler.postDelayed(nextScan, config.intervalMinutes * 60L * 1000L);
            return;
        }
        scanningLatam = !includeSmiles;
        int providerCount = (includeSmiles ? 1 : 0) + (includeLatam ? 1 : 0);
        acquireScanWakeLock(tasks.size() * providerCount);
        results.clear();
        failures.clear();
        latamResults.clear();
        latamFailures.clear();
        taskIndex = 0;
        scanStartedAt = SystemClock.elapsedRealtime();
        updateStatus("Verificando 1/" + tasks.size() + "...");
        loadTask();
    }

    private void loadTask() {
        if (taskIndex >= tasks.size()) {
            if (!scanningLatam && includeLatam) {
                scanningLatam = true;
                taskIndex = 0;
                updateStatus("Smiles concluído. Iniciando LATAM Pass…");
                handler.postDelayed(this::loadTask, 1200);
                return;
            }
            finishScan();
            return;
        }
        SearchConfig.Task task = tasks.get(taskIndex);
        currentHttpStatus = 0;
        currentWebViewError = null;
        long elapsed = SystemClock.elapsedRealtime() - scanStartedAt;
        int offset = scanningLatam && includeSmiles ? tasks.size() : 0;
        int total = tasks.size() * ((includeSmiles ? 1 : 0) + (includeLatam ? 1 : 0));
        int completed = offset + taskIndex;
        StringBuilder progress = new StringBuilder("Verificando ")
                .append(scanningLatam ? "LATAM Pass " : "Smiles ")
                .append(completed + 1).append("/").append(total)
                .append(": ").append(task.label).append(" • ")
                .append(task.displayDate(task.dates.get(0))).append(" • ")
                .append(formatDuration(elapsed));
        int etaThreshold = Math.max(1, (int) Math.ceil(total * 0.20));
        if (completed >= etaThreshold) {
            long average = elapsed / completed;
            long remaining = average * (total - completed);
            progress.append(" • faltam ~").append(formatDuration(remaining));
        }
        updateStatus(progress.toString());
        int expectedTaskIndex = taskIndex;
        boolean expectedLatam = scanningLatam;
        handler.postDelayed(() -> handleTaskHardTimeout(expectedTaskIndex, expectedLatam),
                TASK_HARD_TIMEOUT_MS);
        webView.loadUrl(scanningLatam ? buildLatamUrl(task) : buildUrl(task));
    }

    private void handleTaskHardTimeout(int expectedTaskIndex, boolean expectedLatam) {
        if (!scanning || expectedTaskIndex != taskIndex || expectedLatam != scanningLatam
                || taskIndex < 0 || taskIndex >= tasks.size()) return;
        SearchConfig.Task task = tasks.get(taskIndex);
        String key = task.label + "|" + task.dates.get(0);
        (scanningLatam ? latamFailures : failures).put(
                key, "tempo limite — página não concluiu o carregamento");
        webView.stopLoading();
        advance();
    }

    private void acquireScanWakeLock(int taskCount) {
        releaseScanWakeLock();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        scanWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "WonderTravel:ActiveScan");
        long safetyTimeout = Math.max(10L * 60L * 1000L,
                taskCount * (TASK_HARD_TIMEOUT_MS + 5000L));
        scanWakeLock.acquire(safetyTimeout);
    }

    private void releaseScanWakeLock() {
        if (scanWakeLock != null && scanWakeLock.isHeld()) scanWakeLock.release();
        scanWakeLock = null;
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

    private String buildLatamUrl(SearchConfig.Task task) {
        String departure = task.dates.get(0) + "T12:00:00.000Z";
        return "https://www.latamairlines.com/br/pt/oferta-voos"
                + "?origin=" + encodeUrl(task.from)
                + "&outbound=" + encodeUrl(departure)
                + "&destination=" + encodeUrl(task.to)
                + "&adt=" + config.adults + "&chd=" + config.children
                + "&inf=0&trip=OW&cabin=Economy&redemption=true&sort=RECOMMENDED"
                + "&_wt=" + System.currentTimeMillis();
    }

    private String encodeUrl(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private void inspect(int attempt, int expectedTaskIndex, boolean expectedLatam) {
        if (!scanning || expectedTaskIndex != taskIndex || expectedLatam != scanningLatam
                || taskIndex < 0 || taskIndex >= tasks.size()) return;
        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                encoded -> {
                    if (!scanning || expectedTaskIndex != taskIndex
                            || expectedLatam != scanningLatam) return;
                    String text = decode(encoded);
                    SearchConfig.Task task = tasks.get(taskIndex);
                    FlightParser.Result parsed = scanningLatam
                            ? LatamParser.parse(text, task)
                            : FlightParser.parse(text, task);
                    String key = task.label + "|" + task.dates.get(0);
                    Map<String, FlightParser.Result> activeResults = scanningLatam
                            ? latamResults : results;
                    Map<String, String> activeFailures = scanningLatam
                            ? latamFailures : failures;
                    if (parsed != null) {
                        FlightParser.Result old = activeResults.get(key);
                        if (old == null || parsed.hasFlightDetails()
                                || parsed.miles < old.miles) {
                            activeResults.put(key, parsed);
                        }
                    }
                    FlightParser.Result saved = activeResults.get(key);
                    boolean loading = scanningLatam
                            ? !LatamParser.hasFinishedLoading(text)
                            : SearchDiagnostics.isLoading(text);
                    boolean noFare = SearchDiagnostics.isNoFare(text);
                    if (currentHttpStatus == 403 || currentHttpStatus == 429
                            || currentHttpStatus >= 500 || currentWebViewError != null) {
                        activeFailures.put(key, describeActiveFailure(text));
                        advance();
                    } else if (saved != null && saved.hasFlightDetails() && !loading) {
                        advance();
                    } else if (saved != null && attempt >= 8 && !loading) {
                        advance();
                    } else if (noFare && !loading) {
                        activeFailures.put(key, "sem tarifa disponível");
                        advance();
                    } else if (attempt >= SearchDiagnostics.MAX_INSPECTION_ATTEMPT) {
                        activeFailures.put(key, describeActiveFailure(text));
                        advance();
                    } else {
                        handler.postDelayed(() -> inspect(
                                        attempt + 1, expectedTaskIndex, expectedLatam),
                                SearchDiagnostics.INSPECTION_INTERVAL_MS);
                    }
                });
    }

    private String describeActiveFailure(String text) {
        if (!scanningLatam) {
            return SearchDiagnostics.describe(text, currentHttpStatus, currentWebViewError);
        }
        if (currentHttpStatus > 0) return "HTTP " + currentHttpStatus + " retornado pela LATAM";
        if (currentWebViewError != null) return currentWebViewError;
        if (SearchDiagnostics.isNoFare(text)) return "sem tarifa disponível";
        return "tempo limite — resposta da LATAM não reconhecida";
    }

    private String decode(String encoded) {
        try {
            Object value = new JSONTokener(encoded).nextValue();
            return value instanceof String ? (String) value : null;
        } catch (Exception error) {
            return null;
        }
    }

    private void advance() {
        taskIndex++;
        handler.postDelayed(this::loadTask, 1500);
    }

    private void finishScan() {
        long elapsed = Math.max(0, SystemClock.elapsedRealtime() - scanStartedAt);
        scanning = false;
        releaseScanWakeLock();
        taskIndex = -1;
        String checkedAt = new SimpleDateFormat("dd/MM/yyyy 'às' HH:mm",
                new Locale("pt", "BR")).format(new Date());
        StringBuilder summary = new StringBuilder("Varredura automática em ")
                .append(checkedAt).append(".\n");
        FlightParser.Result lowest = null;
        if (includeSmiles) {
            lowest = appendProviderSummary(summary, "Smiles", results, failures, lowest);
        }
        if (includeLatam) {
            lowest = appendProviderSummary(
                    summary, "LATAM Pass", latamResults, latamFailures, lowest);
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
        int totalQueries = tasks.size()
                * ((includeSmiles ? 1 : 0) + (includeLatam ? 1 : 0));
        long average = totalQueries == 0 ? 0 : elapsed / totalQueries;
        summary.append("\nTempo da varredura: ").append(totalQueries).append("/")
                .append(totalQueries).append(" concluídas • ")
                .append(formatDuration(elapsed)).append(" • média ")
                .append(formatDuration(average)).append(" por consulta.");

        getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                .putString("last_scan", summary.toString().trim())
                .putLong("last_scan_at", System.currentTimeMillis())
                .apply();
        webView.loadUrl("about:blank");
        handler.postDelayed(nextScan, config.intervalMinutes * 60L * 1000L);
    }

    private FlightParser.Result appendProviderSummary(
            StringBuilder summary, String provider,
            Map<String, FlightParser.Result> providerResults,
            Map<String, String> providerFailures,
            FlightParser.Result lowest) {
        for (SearchConfig.Task task : tasks) {
            for (LocalDate date : task.dates) {
                String key = task.label + "|" + date;
                FlightParser.Result result = providerResults.get(key);
                summary.append("\nPrograma: ").append(provider)
                        .append("\n").append(task.label).append("\n")
                        .append(task.displayDate(date));
                if (result == null) {
                    String failure = providerFailures.get(key);
                    if ("sem tarifa disponível".equals(failure)) {
                        summary.append(" • sem tarifa disponível\n");
                    } else {
                        summary.append(" • erro na consulta: ")
                                .append(failure == null ? "motivo não identificado" : failure)
                                .append("\n");
                    }
                } else {
                    if (result.hasFlightDetails()) {
                        summary.append(" • ").append(result.departureTime)
                                .append(" → ").append(result.arrivalTime)
                                .append(" • ").append(result.stops);
                    } else {
                        summary.append(" • horários não identificados");
                    }
                    summary.append("\n").append(format(result.miles))
                            .append(" milhas por viajante\n");
                    if (lowest == null || result.miles < lowest.miles) lowest = result;
                    if (result.miles < config.targetMiles) showOffer(provider, result);
                }
            }
        }
        return lowest;
    }

    private final Runnable nextScan = this::startScan;

    private void showOffer(String provider, FlightParser.Result result) {
        String alertKey = "alert_" + (provider + "|" + result.route + "|" + result.date).hashCode();
        int previous = getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE)
                .getInt(alertKey, -1);
        if (previous == result.miles) return;
        getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                .putInt(alertKey, result.miles).apply();

        Notification notification = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(provider + " abaixo de " + format(config.targetMiles))
                .setContentText(result.route + " • " + result.displayDate()
                        + (result.hasFlightDetails()
                        ? " • " + result.departureTime + " → " + result.arrivalTime
                        + " • " + result.stops : "")
                        + " • " + format(result.miles) + " milhas.")
                .setAutoCancel(true)
                .build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                .notify((provider + result.route + result.date + result.miles).hashCode(), notification);
    }

    private String format(int value) {
        return NumberFormat.getIntegerInstance(new Locale("pt", "BR")).format(value);
    }

    private String formatDuration(long milliseconds) {
        long totalSeconds = Math.max(0, milliseconds / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return hours + "h " + String.format(Locale.getDefault(), "%02dmin %02ds", minutes, seconds);
        if (minutes > 0) return minutes + "min " + String.format(Locale.getDefault(), "%02ds", seconds);
        return seconds + "s";
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
        releaseScanWakeLock();
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

}
