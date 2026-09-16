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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final int TARGET_MILES = 31_000;
    private static final String CHANNEL_ID = "price_alerts";
    private static final String SMILES_HOME =
            "https://www.smiles.com.br/portal/passagens";

    private static final String TEST_SEARCH =
            "https://www.smiles.com.br/mfe/emissao-passagem/"
            + "?adults=2"
            + "&cabin=ECONOMIC"
            + "&children=2"
            + "&departureDate=1791514800000"
            + "&infants=0"
            + "&isElegible=false"
            + "&isFlexibleDateChecked=false"
            + "&returnDate=1792249200000"
            + "&searchType=g3"
            + "&segments=1"
            + "&tripType=1"
            + "&originAirport=GRU"
            + "&originCity="
            + "&originCountry="
            + "&originAirportIsAny=false"
            + "&destinationAirport=BPS"
            + "&destinCity="
            + "&destinCountry="
            + "&destinAirportIsAny=false"
            + "&novo-resultado-voos=true";

    private static final Pattern MILES_PATTERN =
            Pattern.compile("(\\d{1,3}(?:\\.\\d{3})+)\\s*milhas", Pattern.CASE_INSENSITIVE);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private TextView status;
    private Integer lastNotifiedPrice;

    private final Runnable priceReader = new Runnable() {
        @Override
        public void run() {
            if (webView != null && webView.getUrl() != null
                    && webView.getUrl().contains("/mfe/emissao-passagem")) {
                readVisiblePrices();
            }
            handler.postDelayed(this, 5_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        webView = findViewById(R.id.webView);
        Button testSearch = findViewById(R.id.testSearch);

        createNotificationChannel();
        requestNotificationPermission();
        configureWebView();

        testSearch.setOnClickListener(view -> {
            status.setText("Abrindo GRU → BPS para 2 adultos e 2 crianças...");
            lastNotifiedPrice = null;
            webView.loadUrl(TEST_SEARCH);
        });

        if (savedInstanceState == null) {
            webView.loadUrl(SMILES_HOME);
        } else {
            webView.restoreState(savedInstanceState);
        }

        handler.post(priceReader);
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
                status.setText("Carregando...");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url.contains("/mfe/emissao-passagem")) {
                    status.setText("Procurando valores em milhas...");
                    handler.postDelayed(() -> readVisiblePrices(), 3_000);
                } else {
                    status.setText("Smiles carregada. Toque em “Testar GRU → BPS”.");
                }
            }
        });
    }

    private void readVisiblePrices() {
        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                encoded -> {
                    try {
                        Object decoded = new JSONTokener(encoded).nextValue();
                        if (!(decoded instanceof String)) {
                            return;
                        }
                        updateLowestPrice((String) decoded);
                    } catch (Exception ignored) {
                        status.setText("Não foi possível ler as tarifas desta tela.");
                    }
                }
        );
    }

    private void updateLowestPrice(String pageText) {
        Matcher matcher = MILES_PATTERN.matcher(pageText);
        int lowest = Integer.MAX_VALUE;

        while (matcher.find()) {
            try {
                int miles = Integer.parseInt(matcher.group(1).replace(".", ""));
                if (miles < lowest) {
                    lowest = miles;
                }
            } catch (NumberFormatException ignored) {
                // Ignora textos que não representam uma quantidade válida de milhas.
            }
        }

        if (lowest == Integer.MAX_VALUE) {
            if (!pageText.contains("Aguarde enquanto buscamos")) {
                status.setText("Nenhum valor em milhas foi identificado nesta tela.");
            }
            return;
        }

        String formatted = NumberFormat.getIntegerInstance(
                new Locale("pt", "BR")
        ).format(lowest);

        if (lowest < TARGET_MILES) {
            status.setText("Oportunidade: " + formatted
                    + " milhas por viajante — abaixo de 31.000!");
            if (lastNotifiedPrice == null || lowest < lastNotifiedPrice) {
                showPriceNotification(lowest, formatted);
                lastNotifiedPrice = lowest;
            }
        } else {
            status.setText("Menor tarifa visível: " + formatted
                    + " milhas por viajante — acima de 31.000.");
        }
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

    private void showPriceNotification(int price, String formatted) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        android.app.Notification notification =
                new android.app.Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Passagem abaixo de 31 mil")
                        .setContentText("Encontramos " + formatted
                                + " milhas por viajante.")
                        .setAutoCancel(true)
                        .build();

        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(price, notification);
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
}
