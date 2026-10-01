package br.com.wondertravel.passagens;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONTokener;

import java.net.URLEncoder;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String ALERT_CHANNEL = "price_alerts";
    private static final String SMILES_HOME = "https://www.smiles.com.br/portal/passagens";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, FlightParser.Result> results = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final Map<String, FlightParser.Result> latamResults = new LinkedHashMap<>();
    private final Map<String, String> latamFailures = new LinkedHashMap<>();

    private AutoCompleteTextView originMode;
    private AutoCompleteTextView destination;
    private EditText outboundDates;
    private EditText returnDates;
    private EditText adults;
    private EditText children;
    private EditText targetMiles;
    private EditText intervalMinutes;
    private EditText pointsBalance;
    private EditText matchMiles;
    private EditText thousandCost;
    private TextView pointsNeeded;
    private TextView investmentValue;
    private TextView latamSessionStatus;
    private View copyLatamDiagnostics;
    private View radarScreen;
    private View pointsScreen;
    private TextView radarTab;
    private TextView pointsTab;
    private TextView status;
    private TextView resultsTitle;
    private View resultsProgramTabs;
    private TextView smilesResultsTab;
    private TextView latamResultsTab;
    private LinearLayout resultsTable;
    private TextView bestMatchTitle;
    private LinearLayout bestMatchContainer;
    private WebView webView;
    private Button scanButton;
    private SearchConfig config;
    private List<SearchConfig.Task> tasks;
    private int taskIndex = -1;
    private boolean scanning;
    private boolean scanningLatam;
    private boolean includeLatam;
    private int currentHttpStatus;
    private String currentWebViewError;
    private List<ResultRow> latestOutboundRows = new ArrayList<>();
    private List<ResultRow> latestInboundRows = new ArrayList<>();
    private String selectedResultsProvider = "Smiles";
    private float resultsTouchStartX;
    private long scanStartedAt;
    private final Runnable scanTimer = new Runnable() {
        @Override public void run() {
            if (!scanning) return;
            updateScanProgress();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF100D18);
        getWindow().setNavigationBarColor(0xFF100D18);
        setContentView(R.layout.activity_main);
        bindViews();
        setupBottomNavigation();
        setupPointsCalculator();
        setupAirportFields();
        setupDateRangeFields();
        loadForm(SearchConfig.load(this));
        createNotificationChannel();
        requestNotificationPermission();
        configureWebView();
        restoreLastScan();

        findViewById(R.id.saveConfig).setOnClickListener(v -> {
            SearchConfig saved = saveForm();
            if (saved != null) {
                status.setText("Configuração salva. Toque em “Ativar monitor”.");
            }
        });
        scanButton.setOnClickListener(v -> {
            if (saveForm() != null) startScan();
        });
        findViewById(R.id.startMonitor).setOnClickListener(v -> {
            SearchConfig saved = saveForm();
            if (saved == null) return;
            Intent service = new Intent(this, MonitorService.class);
            service.setAction(MonitorService.ACTION_RELOAD);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service);
            else startService(service);
            status.setText("Monitor ativo. A primeira varredura começou.");
        });
        findViewById(R.id.stopMonitor).setOnClickListener(v -> {
            Intent service = new Intent(this, MonitorService.class);
            service.setAction(MonitorService.ACTION_STOP);
            startService(service);
            status.setText("Monitor desativado.");
        });

        if (state == null) webView.loadUrl(SMILES_HOME);
        else webView.restoreState(state);
    }

    private void bindViews() {
        originMode = findViewById(R.id.originMode);
        destination = findViewById(R.id.destination);
        outboundDates = findViewById(R.id.outboundDates);
        returnDates = findViewById(R.id.returnDates);
        adults = findViewById(R.id.adults);
        children = findViewById(R.id.children);
        targetMiles = findViewById(R.id.targetMiles);
        intervalMinutes = findViewById(R.id.intervalMinutes);
        pointsBalance = findViewById(R.id.pointsBalance);
        matchMiles = findViewById(R.id.matchMiles);
        thousandCost = findViewById(R.id.thousandCost);
        pointsNeeded = findViewById(R.id.pointsNeeded);
        investmentValue = findViewById(R.id.investmentValue);
        radarScreen = findViewById(R.id.radarScreen);
        pointsScreen = findViewById(R.id.pointsScreen);
        radarTab = findViewById(R.id.radarTab);
        pointsTab = findViewById(R.id.pointsTab);
        status = findViewById(R.id.status);
        resultsTitle = findViewById(R.id.resultsTitle);
        resultsProgramTabs = findViewById(R.id.resultsProgramTabs);
        smilesResultsTab = findViewById(R.id.smilesResultsTab);
        latamResultsTab = findViewById(R.id.latamResultsTab);
        resultsTable = findViewById(R.id.resultsTable);
        bestMatchTitle = findViewById(R.id.bestMatchTitle);
        bestMatchContainer = findViewById(R.id.bestMatchContainer);
        webView = findViewById(R.id.webView);
        scanButton = findViewById(R.id.testSearch);
        latamSessionStatus = findViewById(R.id.latamSessionStatus);
        copyLatamDiagnostics = findViewById(R.id.copyLatamDiagnostics);
        findViewById(R.id.connectLatam).setOnClickListener(v ->
                startActivity(new Intent(this, LatamLoginActivity.class)));
        copyLatamDiagnostics.setOnClickListener(v -> copyLatamDiagnostics());
        smilesResultsTab.setOnClickListener(v -> selectResultsProvider("Smiles"));
        latamResultsTab.setOnClickListener(v -> selectResultsProvider("LATAM Pass"));
        resultsTable.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                resultsTouchStartX = event.getX();
            } else if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                float delta = event.getX() - resultsTouchStartX;
                if (Math.abs(delta) > dp(70)) {
                    selectResultsProvider(delta < 0 ? "LATAM Pass" : "Smiles");
                }
            }
            return false;
        });
    }

    private void setupBottomNavigation() {
        radarTab.setOnClickListener(v -> selectTab(true));
        pointsTab.setOnClickListener(v -> selectTab(false));
        selectTab(true);
    }

    private void selectTab(boolean radarSelected) {
        radarScreen.setVisibility(radarSelected ? View.VISIBLE : View.GONE);
        pointsScreen.setVisibility(radarSelected ? View.GONE : View.VISIBLE);
        int active = Color.parseColor("#A98AF8");
        int inactive = Color.parseColor("#746C7E");
        radarTab.setTextColor(radarSelected ? active : inactive);
        pointsTab.setTextColor(radarSelected ? inactive : active);
        radarTab.setCompoundDrawableTintList(ColorStateList.valueOf(
                radarSelected ? active : inactive));
        pointsTab.setCompoundDrawableTintList(ColorStateList.valueOf(
                radarSelected ? inactive : active));
        radarTab.setTypeface(radarTab.getTypeface(), radarSelected
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        pointsTab.setTypeface(pointsTab.getTypeface(), radarSelected
                ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        if (!radarSelected) updatePointsCalculator();
    }

    private void setupPointsCalculator() {
        android.content.SharedPreferences prefs = getSharedPreferences(
                SearchConfig.PREFS, MODE_PRIVATE);
        pointsBalance.setText(prefs.getString("calculator_balance", ""));
        thousandCost.setText(prefs.getString("calculator_thousand_cost", ""));
        String savedMatch = prefs.getString("calculator_match_miles", "");
        if (savedMatch.isEmpty()) {
            long latestMatch = prefs.getLong("last_match_group_miles", 0);
            if (latestMatch > 0) savedMatch = String.valueOf(latestMatch);
        }
        matchMiles.setText(savedMatch);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updatePointsCalculator();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        pointsBalance.addTextChangedListener(watcher);
        matchMiles.addTextChangedListener(watcher);
        thousandCost.addTextChangedListener(watcher);
        updatePointsCalculator();
    }

    private void updatePointsCalculator() {
        if (pointsBalance == null) return;
        long balance = calculatorLong(pointsBalance);
        long required = calculatorLong(matchMiles);
        long needed = Math.max(0, required - balance);
        double cost = calculatorDecimal(thousandCost);
        double investment = needed / 1000.0 * cost;
        pointsNeeded.setText(format(needed) + " milhas");
        investmentValue.setText(NumberFormat.getCurrencyInstance(
                new Locale("pt", "BR")).format(investment));
        getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                .putString("calculator_balance", pointsBalance.getText().toString().trim())
                .putString("calculator_match_miles", matchMiles.getText().toString().trim())
                .putString("calculator_thousand_cost", thousandCost.getText().toString().trim())
                .apply();
    }

    private long calculatorLong(EditText field) {
        try {
            return Long.parseLong(field.getText().toString().replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private double calculatorDecimal(EditText field) {
        try {
            String value = field.getText().toString().trim();
            if (value.contains(",")) value = value.replace(".", "").replace(',', '.');
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private void setupAirportFields() {
        ArrayAdapter<String> originAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_dropdown_item, AirportCatalog.options());
        ArrayAdapter<String> destinationAdapter = new ArrayAdapter<>(
                this, R.layout.spinner_dropdown_item, AirportCatalog.options());
        originMode.setAdapter(originAdapter);
        destination.setAdapter(destinationAdapter);
        originMode.setThreshold(1);
        destination.setThreshold(1);
        originMode.setOnClickListener(v -> originMode.showDropDown());
        destination.setOnClickListener(v -> destination.showDropDown());
    }

    private void setupDateRangeFields() {
        outboundDates.setOnClickListener(v ->
                openDateRangePicker(outboundDates, false));
        returnDates.setOnClickListener(v ->
                openDateRangePicker(returnDates, true));
    }

    private void loadForm(SearchConfig c) {
        originMode.setText(AirportCatalog.isSaoPauloAll(c.originMode)
                ? AirportCatalog.SAO_PAULO_ALL
                : AirportCatalog.displayForCode(AirportCatalog.extractCode(c.originMode)), false);
        destination.setText(AirportCatalog.displayForCode(c.destination), false);
        showDateRange(outboundDates, c.outboundDates);
        showDateRange(returnDates, c.returnDates);
        adults.setText(String.valueOf(c.adults));
        children.setText(String.valueOf(c.children));
        targetMiles.setText(String.valueOf(c.targetMiles));
        intervalMinutes.setText(String.valueOf(c.intervalMinutes));
    }

    private SearchConfig saveForm() {
        try {
            SearchConfig candidate = new SearchConfig(
                    originMode.getText().toString(),
                    destination.getText().toString(),
                    serializedDates(outboundDates),
                    serializedDates(returnDates),
                    number(adults, "adultos"),
                    number(children, "crianças"),
                    number(targetMiles, "limite de milhas"),
                    number(intervalMinutes, "intervalo")
            );
            candidate.createTasks();
            candidate.save(this);
            config = candidate;
            Toast.makeText(this, "Configuração salva.", Toast.LENGTH_SHORT).show();
            return candidate;
        } catch (IllegalArgumentException error) {
            status.setText("Revise a configuração: " + error.getMessage());
            return null;
        }
    }

    private int number(EditText field, String label) {
        try {
            return Integer.parseInt(field.getText().toString().trim());
        } catch (Exception error) {
            throw new IllegalArgumentException("Informe " + label + ".");
        }
    }

    private void openDateRangePicker(EditText field, boolean returnTrip) {
        List<LocalDate> current;
        try {
            current = SearchConfig.parseDates(serializedDates(field));
        } catch (Exception ignored) {
            current = new ArrayList<>();
        }
        LocalDate minimum = LocalDate.now();
        if (returnTrip) {
            try {
                List<LocalDate> outbound = SearchConfig.parseDates(
                        serializedDates(outboundDates));
                if (!outbound.isEmpty()) {
                    minimum = outbound.get(outbound.size() - 1).plusDays(1);
                }
            } catch (Exception ignored) {
            }
        }
        LocalDate initialStart = current.isEmpty() || current.get(0).isBefore(minimum)
                ? minimum : current.get(0);
        LocalDate initialEnd = current.size() > 1
                && !current.get(current.size() - 1).isBefore(initialStart)
                ? current.get(current.size() - 1) : null;

        String title = returnTrip ? "Escolha a data ou período da volta"
                : "Escolha a data ou período da ida";
        new DateRangeDialog(this, title, initialStart, initialEnd, minimum, 5,
                (start, end) -> {
                    showDateRange(field, serializeRange(start, end));
                    if (!returnTrip) clearInvalidReturn(end);
                }).show();
    }

    private void clearInvalidReturn(LocalDate outboundEnd) {
        try {
            List<LocalDate> returns = SearchConfig.parseDates(
                    serializedDates(returnDates));
            if (!returns.isEmpty() && !returns.get(0).isAfter(outboundEnd)) {
                returnDates.setTag("");
                returnDates.setText("");
                Toast.makeText(this,
                        "Escolha novamente a volta, depois do período de ida.",
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
        }
    }

    private String serializeRange(LocalDate start, LocalDate end) {
        StringBuilder value = new StringBuilder();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (value.length() > 0) value.append(", ");
            value.append(displayDate(date));
        }
        return value.toString();
    }

    private void showDateRange(EditText field, String serialized) {
        List<LocalDate> dates;
        try {
            dates = SearchConfig.parseDates(serialized);
        } catch (Exception ignored) {
            field.setTag(serialized);
            field.setText(serialized);
            return;
        }
        field.setTag(serialized);
        if (dates.isEmpty()) {
            field.setText("");
        } else if (dates.size() == 1) {
            field.setText(displayDate(dates.get(0)));
        } else {
            field.setText(displayDate(dates.get(0)) + " a "
                    + displayDate(dates.get(dates.size() - 1)));
        }
    }

    private String serializedDates(EditText field) {
        Object value = field.getTag();
        return value == null ? field.getText().toString() : value.toString();
    }

    private String displayDate(LocalDate date) {
        return String.format(Locale.getDefault(), "%02d/%02d/%04d",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    private void startScan() {
        if (scanning) return;
        config = SearchConfig.load(this);
        try {
            tasks = config.createTasks();
        } catch (IllegalArgumentException error) {
            status.setText(error.getMessage());
            return;
        }
        scanning = true;
        scanningLatam = false;
        includeLatam = getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE)
                .getBoolean("latam_session_connected", false);
        results.clear();
        failures.clear();
        latamResults.clear();
        latamFailures.clear();
        taskIndex = 0;
        scanStartedAt = SystemClock.elapsedRealtime();
        scanButton.setEnabled(false);
        handler.removeCallbacks(scanTimer);
        handler.post(scanTimer);
        loadTask();
    }

    private void loadTask() {
        if (taskIndex >= tasks.size()) {
            if (!scanningLatam && includeLatam) {
                scanningLatam = true;
                taskIndex = 0;
                status.setText("Smiles concluído. Iniciando LATAM Pass…");
                handler.postDelayed(this::loadTask, 1200);
                return;
            }
            finishScan();
            return;
        }
        SearchConfig.Task task = tasks.get(taskIndex);
        currentHttpStatus = 0;
        currentWebViewError = null;
        updateScanProgress();
        webView.loadUrl(scanningLatam ? buildLatamUrl(task) : buildUrl(task));
    }

    private void updateScanProgress() {
        if (!scanning || tasks == null || tasks.isEmpty()
                || taskIndex < 0 || taskIndex >= tasks.size()) return;
        SearchConfig.Task task = tasks.get(taskIndex);
        long elapsed = SystemClock.elapsedRealtime() - scanStartedAt;
        int providerOffset = scanningLatam ? tasks.size() : 0;
        int total = tasks.size() * (includeLatam ? 2 : 1);
        int completed = providerOffset + taskIndex;
        String provider = scanningLatam ? "LATAM Pass" : "Smiles";
        StringBuilder progress = new StringBuilder("Verificando ").append(provider).append(" • ")
                .append(completed + 1).append("/").append(total)
                .append(": ").append(task.label).append(" • ")
                .append(task.displayDate(task.dates.get(0)))
                .append("\n").append(completed).append("/").append(total)
                .append(" concluídas • Tempo: ").append(formatDuration(elapsed));
        int etaThreshold = Math.max(1, (int) Math.ceil(total * 0.20));
        if (completed >= etaThreshold) {
            long average = elapsed / completed;
            long remaining = average * (total - completed);
            progress.append("\nTempo restante estimado: ~")
                    .append(formatDuration(remaining));
        }
        status.setText(progress.toString());
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
                + "&inf=0&trip=OW&cabin=Economy&redemption=true&sort=RECOMMENDED";
    }

    private String encodeUrl(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!scanning) status.setText("Carregando...");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                boolean expectedPage = scanningLatam
                        ? url.contains("/oferta-voos")
                        : url.contains("/mfe/emissao-passagem");
                if (scanning && expectedPage) {
                    int expectedTaskIndex = taskIndex;
                    handler.postDelayed(() -> inspect(0, expectedTaskIndex),
                            scanningLatam ? 7000 : SearchDiagnostics.FIRST_INSPECTION_DELAY_MS);
                } else if (!scanning) {
                    restoreLastScan();
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

    private void inspect(int attempt, int expectedTaskIndex) {
        if (!scanning || expectedTaskIndex != taskIndex
                || taskIndex < 0 || taskIndex >= tasks.size()) return;
        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                encoded -> {
                    if (!scanning || expectedTaskIndex != taskIndex) return;
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
                        handler.postDelayed(() -> inspect(attempt + 1, expectedTaskIndex),
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
        handler.removeCallbacks(scanTimer);
        taskIndex = -1;
        scanButton.setEnabled(true);
        String checkedAt = new SimpleDateFormat("dd/MM/yyyy 'às' HH:mm",
                new Locale("pt", "BR")).format(new Date());
        StringBuilder summary = new StringBuilder("Varredura concluída em ")
                .append(checkedAt).append(".\n");
        FlightParser.Result lowest = appendProviderSummary(
                summary, "Smiles", results, failures, null);
        if (includeLatam) {
            lowest = appendProviderSummary(
                    summary, "LATAM Pass", latamResults, latamFailures, lowest);
        }
        if (lowest == null) summary.append("Nenhuma tarifa foi identificada.");
        else summary.append("Menor valor: ").append(format(lowest.miles)).append(" milhas — ")
                .append(lowest.miles < config.targetMiles ? "OPORTUNIDADE!" :
                        "acima de " + format(config.targetMiles) + ".");
        int totalQueries = tasks.size() * (includeLatam ? 2 : 1);
        long average = totalQueries == 0 ? 0 : elapsed / totalQueries;
        summary.append("\nTempo da varredura: ").append(totalQueries).append("/")
                .append(totalQueries).append(" concluídas • ")
                .append(formatDuration(elapsed)).append(" • média ")
                .append(formatDuration(average)).append(" por consulta.");

        String finalText = summary.toString().trim();
        getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                .putString("last_scan", finalText).apply();
        showSavedResults(finalText);
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
                }
            }
        }
        return lowest;
    }

    private void showSavedResults(String saved) {
        if (saved == null || saved.isEmpty()) {
            resultsTitle.setVisibility(View.GONE);
            resultsProgramTabs.setVisibility(View.GONE);
            resultsTable.setVisibility(View.GONE);
            bestMatchTitle.setVisibility(View.GONE);
            bestMatchContainer.setVisibility(View.GONE);
            return;
        }

        String[] lines = saved.split("\\n");
        String first = lines.length > 0 ? lines[0] : "Varredura concluída.";
        StringBuilder statusSummary = new StringBuilder(first);
        for (String line : lines) {
            if (line.startsWith("Menor valor:") || line.startsWith("Tempo da varredura:")) {
                statusSummary.append("\n").append(line);
            }
        }
        status.setText(statusSummary.toString());

        resultsTable.removeAllViews();
        bestMatchContainer.removeAllViews();
        List<ResultRow> outbound = new ArrayList<>();
        List<ResultRow> inbound = new ArrayList<>();
        String configuredDestination = SearchConfig.load(this).destination.toUpperCase(Locale.ROOT);
        String currentProvider = "Smiles";

        for (int i = 0; i < lines.length; i++) {
            String route = lines[i].trim();
            if (route.startsWith("Programa: ")) {
                currentProvider = route.substring("Programa: ".length()).trim();
                continue;
            }
            if (!route.matches("[A-Z]{3} → [A-Z]{3}") || i + 1 >= lines.length) {
                continue;
            }

            String detail = lines[++i].trim();
            String[] parts = detail.split(" • ");
            String date = parts.length > 0 ? parts[0] : "—";
            String time = "—";
            String type = "—";
            String miles = "—";

            if (detail.contains("sem tarifa disponível")) {
                type = "Sem tarifa";
            } else if (detail.contains("erro na consulta:")) {
                type = detail.substring(detail.indexOf("erro na consulta:")
                        + "erro na consulta:".length()).trim();
            } else if (detail.contains("horários não identificados")) {
                type = "Não lido";
            } else {
                if (parts.length > 1) time = parts[1];
                if (parts.length > 2) type = parts[2];
            }

            if (i + 1 < lines.length && lines[i + 1].contains("milhas")) {
                String priceLine = lines[++i].trim();
                int end = priceLine.indexOf(" milhas");
                miles = end > 0 ? priceLine.substring(0, end) : priceLine;
            }

            ResultRow row = new ResultRow(currentProvider, route, date, time,
                    type, miles, parseMiles(miles));
            if (route.startsWith(configuredDestination + " →")) inbound.add(row);
            else outbound.add(row);
        }

        latestOutboundRows = outbound;
        latestInboundRows = inbound;
        selectResultsProvider(selectedResultsProvider);

        List<ResultRow> smilesOutbound = filterProvider(outbound, "Smiles");
        List<ResultRow> smilesInbound = filterProvider(inbound, "Smiles");
        List<ResultRow> latamOutbound = filterProvider(outbound, "LATAM Pass");
        List<ResultRow> latamInbound = filterProvider(inbound, "LATAM Pass");
        addProgramMatch("SMILES", cheapest(smilesOutbound), cheapest(smilesInbound), true);
        addProgramMatch("LATAM PASS", cheapest(latamOutbound), cheapest(latamInbound), false);

        resultsTitle.setVisibility(View.VISIBLE);
        resultsProgramTabs.setVisibility(View.VISIBLE);
        resultsTable.setVisibility(View.VISIBLE);
        bestMatchTitle.setVisibility(View.VISIBLE);
        bestMatchContainer.setVisibility(View.VISIBLE);
    }

    private void selectResultsProvider(String provider) {
        selectedResultsProvider = provider;
        if (resultsTable == null) return;
        int active = Color.parseColor("#FFFFFF");
        int inactive = Color.parseColor("#80758F");
        boolean smiles = "Smiles".equals(provider);
        smilesResultsTab.setTextColor(smiles ? active : inactive);
        latamResultsTab.setTextColor(smiles ? inactive : active);
        smilesResultsTab.setTypeface(smilesResultsTab.getTypeface(), smiles
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        latamResultsTab.setTypeface(latamResultsTab.getTypeface(), smiles
                ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        smilesResultsTab.setBackgroundResource(smiles
                ? R.drawable.bg_table_header : android.R.color.transparent);
        latamResultsTab.setBackgroundResource(smiles
                ? android.R.color.transparent : R.drawable.bg_table_header);

        resultsTable.removeAllViews();
        SearchConfig saved = SearchConfig.load(this);
        String destinationCode = saved.destination.toUpperCase(Locale.ROOT);
        addResultSection("IDA", "São Paulo → " + destinationCode,
                filterProvider(latestOutboundRows, provider));
        addResultSection("VOLTA", destinationCode + " → São Paulo",
                filterProvider(latestInboundRows, provider));
    }

    private List<ResultRow> filterProvider(List<ResultRow> rows, String provider) {
        List<ResultRow> filtered = new ArrayList<>();
        for (ResultRow row : rows) {
            if (provider.equals(row.provider)) filtered.add(row);
        }
        return filtered;
    }

    private void addProgramMatch(String provider, ResultRow outbound,
                                 ResultRow inbound, boolean updateSmilesPoints) {
        TextView card = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, bestMatchContainer.getChildCount() == 0 ? 0 : dp(12), 0, 0);
        card.setLayoutParams(params);
        card.setBackgroundResource(R.drawable.bg_best_match);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setTextColor(Color.parseColor("#F4EFFA"));
        card.setTextSize(13);

        StringBuilder text = new StringBuilder(provider).append(" • MELHOR MATCH\n\n");
        if (outbound == null) text.append("IDA: nenhuma tarifa identificada.\n");
        else text.append("IDA: ").append(outbound.route).append(" • ")
                .append(outbound.date).append(" • ").append(outbound.time)
                .append(" • ").append(outbound.type).append("\n")
                .append(outbound.milesText).append(" milhas por viajante\n");
        text.append("\n");
        if (inbound == null) text.append("VOLTA: nenhuma tarifa identificada.");
        else text.append("VOLTA: ").append(inbound.route).append(" • ")
                .append(inbound.date).append(" • ").append(inbound.time)
                .append(" • ").append(inbound.type).append("\n")
                .append(inbound.milesText).append(" milhas por viajante");

        if (outbound != null && inbound != null) {
            long individual = (long) outbound.milesValue + inbound.milesValue;
            SearchConfig saved = SearchConfig.load(this);
            int passengers = Math.max(1, saved.adults + saved.children);
            long group = individual * passengers;
            text.append("\n\nTOTAL INDIVIDUAL: ").append(format(individual)).append(" milhas")
                    .append("\nTOTAL PARA ").append(passengers).append(" PASSAGEIRO")
                    .append(passengers == 1 ? "" : "S").append(": ")
                    .append(format(group)).append(" milhas");
            if (updateSmilesPoints) {
                android.content.SharedPreferences prefs = getSharedPreferences(
                        SearchConfig.PREFS, MODE_PRIVATE);
                long previous = prefs.getLong("last_match_group_miles", 0);
                prefs.edit().putLong("last_match_group_miles", group).apply();
                if (matchMiles != null && group != previous) {
                    matchMiles.setText(String.valueOf(group));
                }
            }
        }
        card.setText(text.toString());
        bestMatchContainer.addView(card);
    }

    private void addResultSection(String title, String subtitle, List<ResultRow> rows) {
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setPadding(dp(12), dp(14), dp(12), dp(9));

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(Color.parseColor(title.equals("IDA") ? "#FF8A5B" : "#A98AF8"));
        name.setTextSize(14);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        heading.addView(name);

        TextView route = new TextView(this);
        route.setText(subtitle);
        route.setTextColor(Color.parseColor("#91879F"));
        route.setTextSize(11);
        heading.addView(route);
        resultsTable.addView(heading);

        addResultRow(new String[]{"ROTA", "DATA", "HORÁRIO", "VOO", "MILHAS"}, true, false);
        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Nenhum resultado neste trecho.");
            empty.setTextColor(Color.parseColor("#91879F"));
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            resultsTable.addView(empty);
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            ResultRow row = rows.get(i);
            addResultRow(new String[]{
                    row.route.replace(" → ", "\n"),
                    compactDate(row.date),
                    row.time.replace("h", ":").replace(" → ", "\n"),
                    row.type,
                    row.milesText
            }, false, i % 2 == 1);
        }
    }

    private void addBestMatch(String direction, ResultRow row) {
        TextView card = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, bestMatchContainer.getChildCount() == 0 ? 0 : dp(9), 0, 0);
        card.setLayoutParams(params);
        card.setBackgroundResource(R.drawable.bg_best_match);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setTextColor(Color.parseColor("#F4EFFA"));
        card.setTextSize(13);
        if (row == null) {
            card.setText(direction + "\nNenhuma tarifa identificada.");
        } else {
            String details = row.time.equals("—") ? row.type : row.time + " • " + row.type;
            card.setText(direction + "  •  MELHOR TARIFA\n"
                    + row.provider + " • " + row.route + "  |  " + row.date + "\n"
                    + details + "\n"
                    + row.milesText + " milhas por viajante");
        }
        bestMatchContainer.addView(card);
    }

    private void addTripTotal(ResultRow outbound, ResultRow inbound) {
        TextView total = new TextView(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(12), 0, 0);
        total.setLayoutParams(params);
        total.setBackgroundResource(R.drawable.bg_best_match);
        total.setPadding(dp(16), dp(15), dp(16), dp(15));
        total.setTextColor(Color.parseColor("#F4EFFA"));
        total.setTextSize(14);
        total.setTypeface(total.getTypeface(), android.graphics.Typeface.BOLD);
        if (outbound == null || inbound == null) {
            total.setText("TOTAL DA VIAGEM\n"
                    + "Indisponível enquanto faltar uma tarifa de ida ou volta.");
        } else {
            long tripTotal = (long) outbound.milesValue + inbound.milesValue;
            SearchConfig saved = SearchConfig.load(this);
            int passengers = Math.max(1, saved.adults + saved.children);
            long groupTotal = tripTotal * passengers;
            android.content.SharedPreferences prefs = getSharedPreferences(
                    SearchConfig.PREFS, MODE_PRIVATE);
            long previousMatch = prefs.getLong("last_match_group_miles", 0);
            prefs.edit().putLong("last_match_group_miles", groupTotal).apply();
            if (matchMiles != null && groupTotal != previousMatch) {
                matchMiles.setText(String.valueOf(groupTotal));
            }
            total.setText("TOTAL DA VIAGEM — 1 PASSAGEIRO\n"
                    + "Ida: " + format(outbound.milesValue) + " milhas\n"
                    + "Volta: " + format(inbound.milesValue) + " milhas\n"
                    + "Total individual: " + format(tripTotal) + " milhas\n\n"
                    + "TOTAL PARA " + passengers + " PASSAGEIRO"
                    + (passengers == 1 ? "" : "S") + "\n"
                    + format(groupTotal) + " milhas");
        }
        bestMatchContainer.addView(total);
    }

    private ResultRow cheapest(List<ResultRow> rows) {
        ResultRow best = null;
        for (ResultRow row : rows) {
            if (row.milesValue < 0) continue;
            if (best == null || row.milesValue < best.milesValue) best = row;
        }
        return best;
    }

    private int parseMiles(String value) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private String compactDate(String date) {
        return date.length() == 10 ? date.substring(0, 5) + "\n" + date.substring(6) : date;
    }

    private static final class ResultRow {
        final String provider;
        final String route;
        final String date;
        final String time;
        final String type;
        final String milesText;
        final int milesValue;

        ResultRow(String provider, String route, String date, String time, String type,
                  String milesText, int milesValue) {
            this.provider = provider;
            this.route = route;
            this.date = date;
            this.time = time;
            this.type = type;
            this.milesText = milesText;
            this.milesValue = milesValue;
        }
    }

    private void addResultRow(String[] values, boolean header, boolean alternate) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(header ? 42 : 64));
        row.setBackgroundResource(header
                ? R.drawable.bg_table_header
                : (alternate ? R.drawable.bg_table_row_alt : R.drawable.bg_table_row));

        float[] weights = {0.85f, 1.05f, 1.15f, 1.05f, 1.15f};
        for (int i = 0; i < values.length; i++) {
            TextView cell = new TextView(this);
            cell.setText(values[i]);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(3), dp(7), dp(3), dp(7));
            cell.setTextSize(header ? 9.5f : 11.5f);
            cell.setTextColor(Color.parseColor(
                    header ? "#BBA9E8" : (i == 4 ? "#FF8A5B" : "#F0EAF7")
            ));
            if (header || i == 4) {
                cell.setTypeface(cell.getTypeface(), android.graphics.Typeface.BOLD);
            }
            row.addView(cell, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[i]
            ));
        }
        resultsTable.addView(row);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String format(long value) {
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

    private void restoreLastScan() {
        String saved = getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE)
                .getString("last_scan", "");
        if (!saved.isEmpty()) showSavedResults(saved);
        else {
            status.setText("Configuração pronta. Salve ou ative o monitor.");
            showSavedResults("");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    ALERT_CHANNEL, "Alertas de oportunidades",
                    NotificationManager.IMPORTANCE_HIGH);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateLatamSessionStatus();
        if (!scanning) restoreLastScan();
    }

    private void updateLatamSessionStatus() {
        if (latamSessionStatus == null) return;
        boolean connected = getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE)
                .getBoolean("latam_session_connected", false);
        latamSessionStatus.setText(connected
                ? "LATAM conectada. Sessão pronta para o teste do motor Beta."
                : "LATAM ainda não conectada. O login e o duplo fator serão feitos no site oficial.");
        latamSessionStatus.setTextColor(Color.parseColor(
                connected ? "#72D6A0" : "#91879F"));
        String diagnostic = getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE)
                .getString("latam_last_result_text", "");
        copyLatamDiagnostics.setVisibility(connected && !diagnostic.isEmpty()
                ? View.VISIBLE : View.GONE);
    }

    private void copyLatamDiagnostics() {
        String diagnostic = getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE)
                .getString("latam_last_result_text", "");
        if (diagnostic.isEmpty()) {
            Toast.makeText(this, "Abra novamente a LATAM e aguarde os resultados.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico LATAM", diagnostic));
        Toast.makeText(this, "Diagnóstico copiado. Cole o texto na conversa.",
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }

}
