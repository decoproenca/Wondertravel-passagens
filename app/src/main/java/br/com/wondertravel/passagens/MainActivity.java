package br.com.wondertravel.passagens;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

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

public final class MainActivity extends Activity {
    private static final String ALERT_CHANNEL = "price_alerts";
    private static final String SMILES_HOME = "https://www.smiles.com.br/portal/passagens";
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?i)(\\d{1,2})\\s+(jan|fev|mar|abr|mai|jun|jul|ago|set|out|nov|dez)"
                    + "\\s+(\\d{1,3}(?:\\.\\d{3})+)\\s+milhas"
    );

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, PriceResult> results = new LinkedHashMap<>();

    private Spinner originMode;
    private EditText destination;
    private EditText outboundDates;
    private EditText returnDates;
    private EditText adults;
    private EditText children;
    private EditText targetMiles;
    private EditText intervalMinutes;
    private TextView status;
    private WebView webView;
    private Button scanButton;
    private SearchConfig config;
    private List<SearchConfig.Task> tasks;
    private int taskIndex = -1;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        bindViews();
        setupOriginSpinner();
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
        webView = findViewById(R.id.webView);
        scanButton = findViewById(R.id.testSearch);
    }

    private void setupOriginSpinner() {
        String[] values = {"São Paulo (GRU + CGH)", "GRU", "CGH"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        originMode.setAdapter(adapter);
    }

    private void loadForm(SearchConfig c) {
        for (int i = 0; i < originMode.getCount(); i++) {
            if (originMode.getItemAtPosition(i).toString().equals(c.originMode)) {
                originMode.setSelection(i);
                break;
            }
        }
        destination.setText(c.destination);
        outboundDates.setText(c.outboundDates);
        returnDates.setText(c.returnDates);
        adults.setText(String.valueOf(c.adults));
        children.setText(String.valueOf(c.children));
        targetMiles.setText(String.valueOf(c.targetMiles));
        intervalMinutes.setText(String.valueOf(c.intervalMinutes));
    }

    private SearchConfig saveForm() {
        try {
            SearchConfig candidate = new SearchConfig(
                    originMode.getSelectedItem().toString(),
                    destination.getText().toString(),
                    outboundDates.getText().toString(),
                    returnDates.getText().toString(),
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
        status.setText("Verificando " + (taskIndex + 1) + "/" + tasks.size()
                + ": " + task.label + "...");
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
                    handler.postDelayed(() -> inspect(0), 7000);
                } else if (!scanning) {
                    restoreLastScan();
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
                    if (text != null) collectPrices(task, text);
                    int foundDates = countFoundDates(task);
                    boolean complete = foundDates >= task.dates.size();
                    boolean loading = text != null
                            && text.contains("Aguarde enquanto buscamos");
                    if (complete && !loading) advance();
                    else if (attempt >= 29) advance();
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
        }
        return matches;
    }

    private int countFoundDates(SearchConfig.Task task) {
        int count = 0;
        for (LocalDate date : task.dates) {
            if (results.containsKey(task.label + "|" + date)) count++;
        }
        return count;
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
        scanButton.setEnabled(true);
        String checkedAt = new SimpleDateFormat("dd/MM/yyyy 'às' HH:mm",
                new Locale("pt", "BR")).format(new Date());
        StringBuilder summary = new StringBuilder("Varredura concluída em ")
                .append(checkedAt).append(".\n");
        PriceResult lowest = null;

        for (SearchConfig.Task task : tasks) {
            for (LocalDate date : task.dates) {
                PriceResult result = results.get(task.label + "|" + date);
                summary.append(task.label).append(" | ").append(task.displayDate(date)).append(": ");
                if (result == null) summary.append("não foi possível ler\n");
                else {
                    summary.append(format(result.miles)).append(" milhas\n");
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
        status.setText(finalText);
    }

    private String format(int value) {
        return NumberFormat.getIntegerInstance(new Locale("pt", "BR")).format(value);
    }

    private void restoreLastScan() {
        String saved = getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE)
                .getString("last_scan", "");
        if (!saved.isEmpty()) status.setText("Último resultado salvo:\n" + saved);
        else status.setText("Configuração pronta. Salve ou ative o monitor.");
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

    private static final class PriceResult {
        final String route;
        final LocalDate date;
        final int miles;

        PriceResult(String route, LocalDate date, int miles) {
            this.route = route;
            this.date = date;
            this.miles = miles;
        }
    }
}
