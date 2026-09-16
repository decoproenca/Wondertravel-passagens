package br.com.wondertravel.passagens;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
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
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONTokener;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final int TARGET_MILES = 31_000;
    private static final String CHANNEL_ID = "price_alerts";
    private static final String SMILES_HOME =
            "https://www.smiles.com.br/portal/passagens";
    private static final Pattern DATE_PRICE_PATTERN = Pattern.compile(
            "(?i)(\\d{1,2})\\s+out\\s+(\\d{1,3}(?:\\.\\d{3})+)\\s+milhas"
    );

    private final Handler handler = new Handler(Looper.getMainLooper());
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
    private final Map<String, PriceResult> results = new LinkedHashMap<>();
    private final List<String> notifiedOffers = new ArrayList<>();

    private WebView webView;
    private TextView status;
    private Button scanButton;
    private int currentTaskIndex = -1;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        webView = findViewById(R.id.webView);
        scanButton = findViewById(R.id.testSearch);

        createNotificationChannel();
        requestNotificationPermission();
        configureWebView();

        scanButton.setOnClickListener(view -> startFullScan());

        if (savedInstanceState == null) {
            webView.loadUrl(SMILES_HOME);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void startFullScan() {
        if (scanning) {
            return;
        }
        scanning = true;
        results.clear();
        currentTaskIndex = 0;
        scanButton.setEnabled(false);
        loadCurrentTask();
    }

    private void loadCurrentTask() {
        if (currentTaskIndex >= tasks.size()) {
            finishScan();
            return;
        }

        SearchTask task = tasks.get(currentTaskIndex);
        status.setText("Verificando " + (currentTaskIndex + 1) + "/"
                + tasks.size() + ": " + task.label + "...");
        webView.loadUrl(buildSearchUrl(task));
    }

    private String buildSearchUrl(SearchTask task) {
        return "https://www.smiles.com.br/mfe/emissao-passagem/"
                + "?adults=2"
                + "&cabin=ECONOMIC"
                + "&children=2"
                + "&departureDate=" + task.departureTimestamp
                + "&infants=0"
                + "&isElegible=false"
                + "&isFlexibleDateChecked=false"
                + "&returnDate=" + task.returnTimestamp
                + "&searchType=g3"
                + "&segments=1"
                + "&tripType=1"
                + "&originAirport=" + task.from
                + "&originCity="
                + "&originCountry="
                + "&originAirportIsAny=false"
                + "&destinationAirport=" + task.to
                + "&destinCity="
                + "&destinCountry="
                + "&destinAirportIsAny=false"
                + "&novo-resultado-voos=true";
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!scanning) {
                    status.setText("Carregando...");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (scanning && url.contains("/mfe/emissao-passagem")) {
                    handler.postDelayed(() -> inspectCurrentTask(0), 5_000);
                } else if (!scanning) {
                    status.setText("Smiles carregada. Toque em “Verificar agora”.");
                }
            }
        });
    }

    private void inspectCurrentTask(int attempt) {
        if (!scanning || currentTaskIndex < 0
                || currentTaskIndex >= tasks.size()) {
            return;
        }

        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                encoded -> {
                    String pageText = decodeJavascriptString(encoded);
                    if (pageText == null) {
                        retryOrAdvance(attempt);
                        return;
                    }

                    int matches = collectPrices(tasks.get(currentTaskIndex), pageText);
                    boolean stillLoading =
                            pageText.contains("Aguarde enquanto buscamos");

                    if (matches > 0 && !stillLoading) {
                        advanceTask();
                    } else {
                        retryOrAdvance(attempt);
                    }
                }
        );
    }

    private String decodeJavascriptString(String encoded) {
        try {
            Object decoded = new JSONTokener(encoded).nextValue();
            return decoded instanceof String ? (String) decoded : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int collectPrices(SearchTask task, String pageText) {
        Matcher matcher = DATE_PRICE_PATTERN.matcher(pageText);
        int matches = 0;

        while (matcher.find()) {
            int day;
            int miles;
            try {
                day = Integer.parseInt(matcher.group(1));
                miles = Integer.parseInt(matcher.group(2).replace(".", ""));
            } catch (NumberFormatException ignored) {
                continue;
            }

            if (!task.acceptsDay(day)) {
                continue;
            }

            String key = task.label + "|" + day;
            PriceResult existing = results.get(key);
            if (existing == null || miles < existing.miles) {
                results.put(key, new PriceResult(task.label, day, miles));
            }
            matches++;
        }

        return matches;
    }

    private void retryOrAdvance(int attempt) {
        if (attempt >= 19) {
            advanceTask();
            return;
        }
        handler.postDelayed(() -> inspectCurrentTask(attempt + 1), 3_000);
    }

    private void advanceTask() {
        currentTaskIndex++;
        handler.postDelayed(this::loadCurrentTask, 1_500);
    }

    private void finishScan() {
        scanning = false;
        scanButton.setEnabled(true);
        currentTaskIndex = -1;

        if (results.isEmpty()) {
            status.setText("A varredura terminou, mas nenhuma tarifa foi lida.");
            return;
        }

        PriceResult lowest = null;
        StringBuilder summary = new StringBuilder("Varredura concluída.\n");

        for (PriceResult result : results.values()) {
            summary.append(result.route)
                    .append(" | ")
                    .append(String.format(Locale.getDefault(), "%02d/10", result.day))
                    .append(": ")
                    .append(formatMiles(result.miles))
                    .append(" milhas\n");

            if (lowest == null || result.miles < lowest.miles) {
                lowest = result;
            }

            if (result.miles < TARGET_MILES) {
                notifyOffer(result);
            }
        }

        if (lowest != null) {
            summary.append("Menor valor: ")
                    .append(formatMiles(lowest.miles))
                    .append(" milhas — ")
                    .append(lowest.miles < TARGET_MILES
                            ? "OPORTUNIDADE!"
                            : "acima de 31.000.");
        }

        status.setText(summary.toString().trim());
    }

    private String formatMiles(int miles) {
        return NumberFormat.getIntegerInstance(
                new Locale("pt", "BR")
        ).format(miles);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Alertas de passagens",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Oportunidades abaixo do limite de milhas.");
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    100
            );
        }
    }

    private void notifyOffer(PriceResult result) {
        String offerKey = result.route + "|" + result.day + "|" + result.miles;
        if (notifiedOffers.contains(offerKey)) {
            return;
        }
        notifiedOffers.add(offerKey);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String date = String.format(
                Locale.getDefault(), "%02d/10", result.day
        );
        android.app.Notification notification =
                new android.app.Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Passagem abaixo de 31 mil")
                        .setContentText(result.route + " em " + date + ": "
                                + formatMiles(result.miles) + " milhas.")
                        .setAutoCancel(true)
                        .build();

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(offerKey.hashCode(), notification);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
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

    private static final class SearchTask {
        final String from;
        final String to;
        final String label;
        final String departureTimestamp;
        final String returnTimestamp;
        final int[] acceptedDays;

        SearchTask(
                String from,
                String to,
                String label,
                String departureTimestamp,
                String returnTimestamp,
                int... acceptedDays
        ) {
            this.from = from;
            this.to = to;
            this.label = label;
            this.departureTimestamp = departureTimestamp;
            this.returnTimestamp = returnTimestamp;
            this.acceptedDays = acceptedDays;
        }

        boolean acceptsDay(int day) {
            for (int acceptedDay : acceptedDays) {
                if (day == acceptedDay) {
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
