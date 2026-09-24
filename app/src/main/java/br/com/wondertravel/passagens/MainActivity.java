package br.com.wondertravel.passagens;

import android.Manifest;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
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

    private AutoCompleteTextView originMode;
    private AutoCompleteTextView destination;
    private EditText outboundDates;
    private EditText returnDates;
    private EditText adults;
    private EditText children;
    private EditText targetMiles;
    private EditText intervalMinutes;
    private TextView status;
    private TextView resultsTitle;
    private LinearLayout resultsTable;
    private TextView bestMatchTitle;
    private LinearLayout bestMatchContainer;
    private WebView webView;
    private Button scanButton;
    private SearchConfig config;
    private List<SearchConfig.Task> tasks;
    private int taskIndex = -1;
    private boolean scanning;
    private int currentHttpStatus;
    private String currentWebViewError;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF100D18);
        getWindow().setNavigationBarColor(0xFF100D18);
        setContentView(R.layout.activity_main);
        bindViews();
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
        status = findViewById(R.id.status);
        resultsTitle = findViewById(R.id.resultsTitle);
        resultsTable = findViewById(R.id.resultsTable);
        bestMatchTitle = findViewById(R.id.bestMatchTitle);
        bestMatchContainer = findViewById(R.id.bestMatchContainer);
        webView = findViewById(R.id.webView);
        scanButton = findViewById(R.id.testSearch);
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
                openDateRangePicker(outboundDates, "Data inicial da ida"));
        returnDates.setOnClickListener(v ->
                openDateRangePicker(returnDates, "Data inicial da volta"));
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

    private void openDateRangePicker(EditText field, String startTitle) {
        List<LocalDate> current;
        try {
            current = SearchConfig.parseDates(serializedDates(field));
        } catch (Exception ignored) {
            current = new ArrayList<>();
        }
        LocalDate initial = current.isEmpty() ? LocalDate.now() : current.get(0);
        DatePickerDialog startDialog = new DatePickerDialog(
                this,
                (view, year, month, day) -> {
                    LocalDate start = LocalDate.of(year, month + 1, day);
                    openEndDatePicker(field, start);
                },
                initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()
        );
        startDialog.setTitle(startTitle);
        startDialog.getDatePicker().setMinDate(
                LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli());
        startDialog.show();
    }

    private void openEndDatePicker(EditText field, LocalDate start) {
        LocalDate maxEnd = start.plusDays(2);
        DatePickerDialog endDialog = new DatePickerDialog(
                this,
                (view, year, month, day) -> {
                    LocalDate end = LocalDate.of(year, month + 1, day);
                    showDateRange(field, serializeRange(start, end));
                },
                maxEnd.getYear(), maxEnd.getMonthValue() - 1, maxEnd.getDayOfMonth()
        );
        endDialog.setTitle("Data final — período máximo de 3 dias");
        endDialog.getDatePicker().setMinDate(
                start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli());
        endDialog.getDatePicker().setMaxDate(
                maxEnd.atTime(23, 59).atZone(ZoneId.systemDefault())
                        .toInstant().toEpochMilli());
        endDialog.show();
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
        results.clear();
        failures.clear();
        taskIndex = 0;
        scanButton.setEnabled(false);
        loadTask();
    }

    private void loadTask() {
        if (taskIndex >= tasks.size()) {
            finishScan();
            return;
        }
        SearchConfig.Task task = tasks.get(taskIndex);
        currentHttpStatus = 0;
        currentWebViewError = null;
        status.setText("Verificando " + (taskIndex + 1) + "/" + tasks.size()
                + ": " + task.label + " • "
                + task.displayDate(task.dates.get(0)) + "...");
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
                if (scanning && url.contains("/mfe/emissao-passagem")) {
                    handler.postDelayed(() -> inspect(0),
                            SearchDiagnostics.FIRST_INSPECTION_DELAY_MS);
                } else if (!scanning) {
                    restoreLastScan();
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse response) {
                int status = response.getStatusCode();
                if (scanning && (status == 403 || status == 429 || status >= 500)) {
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

    private void inspect(int attempt) {
        if (!scanning || taskIndex < 0 || taskIndex >= tasks.size()) return;
        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                encoded -> {
                    String text = decode(encoded);
                    SearchConfig.Task task = tasks.get(taskIndex);
                    FlightParser.Result parsed = FlightParser.parse(text, task);
                    String key = task.label + "|" + task.dates.get(0);
                    if (parsed != null) {
                        FlightParser.Result old = results.get(key);
                        if (old == null || parsed.hasFlightDetails()
                                || parsed.miles < old.miles) {
                            results.put(key, parsed);
                        }
                    }
                    FlightParser.Result saved = results.get(key);
                    boolean loading = SearchDiagnostics.isLoading(text);
                    boolean noFare = SearchDiagnostics.isNoFare(text);
                    if (saved != null && saved.hasFlightDetails() && !loading) {
                        advance();
                    } else if (saved != null && attempt >= 8 && !loading) {
                        advance();
                    } else if (noFare && !loading) {
                        failures.put(key, "sem tarifa disponível");
                        advance();
                    } else if (attempt >= SearchDiagnostics.MAX_INSPECTION_ATTEMPT) {
                        failures.put(key, SearchDiagnostics.describe(
                                text, currentHttpStatus, currentWebViewError));
                        advance();
                    } else {
                        handler.postDelayed(() -> inspect(attempt + 1),
                                SearchDiagnostics.INSPECTION_INTERVAL_MS);
                    }
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

    private void advance() {
        taskIndex++;
        handler.postDelayed(this::loadTask, 1500);
    }

    private void finishScan() {
        scanning = false;
        taskIndex = -1;
        scanButton.setEnabled(true);
        String checkedAt = new SimpleDateFormat("dd/MM/yyyy 'às' HH:mm",
                new Locale("pt", "BR")).format(new Date());
        StringBuilder summary = new StringBuilder("Varredura concluída em ")
                .append(checkedAt).append(".\n");
        FlightParser.Result lowest = null;

        for (SearchConfig.Task task : tasks) {
            for (LocalDate date : task.dates) {
                FlightParser.Result result = results.get(task.label + "|" + date);
                summary.append("\n").append(task.label).append("\n")
                        .append(task.displayDate(date));
                if (result == null) {
                    String failure = failures.get(task.label + "|" + date);
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
        if (lowest == null) summary.append("Nenhuma tarifa foi identificada.");
        else summary.append("Menor valor: ").append(format(lowest.miles)).append(" milhas — ")
                .append(lowest.miles < config.targetMiles ? "OPORTUNIDADE!" :
                        "acima de " + format(config.targetMiles) + ".");

        String finalText = summary.toString().trim();
        getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                .putString("last_scan", finalText).apply();
        showSavedResults(finalText);
    }

    private void showSavedResults(String saved) {
        if (saved == null || saved.isEmpty()) {
            resultsTitle.setVisibility(View.GONE);
            resultsTable.setVisibility(View.GONE);
            bestMatchTitle.setVisibility(View.GONE);
            bestMatchContainer.setVisibility(View.GONE);
            return;
        }

        String[] lines = saved.split("\\n");
        String first = lines.length > 0 ? lines[0] : "Varredura concluída.";
        String last = lines.length > 1 ? lines[lines.length - 1] : "";
        status.setText(first + (last.startsWith("Menor valor:") ? "\n" + last : ""));

        resultsTable.removeAllViews();
        bestMatchContainer.removeAllViews();
        List<ResultRow> outbound = new ArrayList<>();
        List<ResultRow> inbound = new ArrayList<>();
        String configuredDestination = SearchConfig.load(this).destination.toUpperCase(Locale.ROOT);

        for (int i = 0; i < lines.length; i++) {
            String route = lines[i].trim();
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

            ResultRow row = new ResultRow(route, date, time, type, miles, parseMiles(miles));
            if (route.startsWith(configuredDestination + " →")) inbound.add(row);
            else outbound.add(row);
        }

        addResultSection("IDA", "São Paulo → " + configuredDestination, outbound);
        addResultSection("VOLTA", configuredDestination + " → São Paulo", inbound);

        ResultRow bestOutbound = cheapest(outbound);
        ResultRow bestInbound = cheapest(inbound);
        addBestMatch("IDA", bestOutbound);
        addBestMatch("VOLTA", bestInbound);
        addTripTotal(bestOutbound, bestInbound);

        resultsTitle.setVisibility(View.VISIBLE);
        resultsTable.setVisibility(View.VISIBLE);
        bestMatchTitle.setVisibility(View.VISIBLE);
        bestMatchContainer.setVisibility(View.VISIBLE);
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
                    + row.route + "  |  " + row.date + "\n"
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
            total.setText("TOTAL DA VIAGEM — 1 PASSAGEIRO\n"
                    + "Indisponível enquanto faltar uma tarifa de ida ou volta.");
        } else {
            int tripTotal = outbound.milesValue + inbound.milesValue;
            total.setText("TOTAL DA VIAGEM — 1 PASSAGEIRO\n"
                    + "Ida: " + format(outbound.milesValue) + " milhas\n"
                    + "Volta: " + format(inbound.milesValue) + " milhas\n"
                    + "Total: " + format(tripTotal) + " milhas");
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
        final String route;
        final String date;
        final String time;
        final String type;
        final String milesText;
        final int milesValue;

        ResultRow(String route, String date, String time, String type,
                  String milesText, int milesValue) {
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

    private String format(int value) {
        return NumberFormat.getIntegerInstance(new Locale("pt", "BR")).format(value);
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
        if (!scanning) restoreLastScan();
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
