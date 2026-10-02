package br.com.wondertravel.passagens;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.URLEncoder;
import java.time.LocalDate;
import java.util.Locale;
import java.util.List;

public final class LatamLoginActivity extends Activity {
    private WebView webView;
    private TextView status;
    private String searchUrl;
    private LocalDate expectedDate;
    private boolean closing;
    private boolean dateRetryAttempted;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sessionPoll = new Runnable() {
        @Override public void run() {
            inspectSession(false);
            handler.postDelayed(this, 2500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF100D18);
        getWindow().setNavigationBarColor(0xFF100D18);
        buildScreen();
        configureWebView();
        searchUrl = buildSearchUrl();
        status.setText("Abrindo a busca LATAM para " + displayDate(expectedDate) + "…");
        webView.loadUrl(searchUrl);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#100D18"));

        TextView title = text("LATAM Pass • Conexão segura", 19, "#FFFFFF", true);
        title.setPadding(dp(16), dp(16), dp(16), dp(4));
        root.addView(title);

        status = text("Abrindo o site oficial da LATAM…", 12, "#BFB4D5", false);
        status.setPadding(dp(16), 0, dp(16), dp(12));
        root.addView(status);

        webView = new WebView(this);
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(dp(10), dp(8), dp(10), dp(10));
        Button reload = button("Reabrir busca");
        Button finish = button("Concluir e voltar");
        actions.addView(reload, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams finishParams = new LinearLayout.LayoutParams(0, dp(52), 1);
        finishParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(finish, finishParams);
        root.addView(actions);

        reload.setOnClickListener(v -> {
            dateRetryAttempted = false;
            webView.clearCache(false);
            webView.loadUrl(searchUrl);
        });
        finish.setOnClickListener(v -> completeConnection());
        setContentView(root);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (url.contains("auth.latamairlines.com")) {
                    status.setText("Faça login e conclua o duplo fator diretamente na LATAM.");
                } else {
                    status.setText("Validando a sessão LATAM Pass…");
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url.contains("auth.latamairlines.com")) {
                    markConnected(false);
                    status.setText("Faça login e conclua o duplo fator diretamente na LATAM.");
                    return;
                }
                if (url.contains("/oferta-voos")) {
                    view.evaluateJavascript(
                            "(function(){return document.body ? document.body.innerText : '';})()",
                            body -> {
                                if (body != null && body.length() > 20
                                        && !url.contains("/login")) {
                                    status.setText("Página carregada. Confirmando rota e data…");
                                }
                            });
                }
            }
        });
        handler.post(sessionPoll);
    }

    private void inspectSession(boolean finishAfterInspection) {
        String url = webView.getUrl();
        if (url == null || url.contains("auth.latamairlines.com")) {
            markConnected(false);
            if (finishAfterInspection) {
                status.setText("Conclua o login e o duplo fator antes de voltar.");
            }
            return;
        }
        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                encoded -> {
                    String body = decodeJavascriptString(encoded);
                    boolean resultPage = url.contains("/oferta-voos")
                            && (body.contains("Escolha um voo")
                            || body.contains("Organizar por")
                            || body.toLowerCase().contains("latam pass"));
                    if (resultPage) {
                        if (!bodyMatchesExpectedDate(body)) {
                            if (!dateRetryAttempted) {
                                dateRetryAttempted = true;
                                status.setText("A LATAM abriu outra data. Corrigindo para "
                                        + displayDate(expectedDate) + "…");
                                webView.clearCache(false);
                                webView.loadUrl(searchUrl);
                            } else {
                                status.setText("A LATAM não confirmou a data "
                                        + displayDate(expectedDate)
                                        + ". Toque em ‘Reabrir busca’.");
                            }
                            return;
                        }
                        markConnected(true);
                        getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                                .putString("latam_last_result_text",
                                        body.substring(0, Math.min(body.length(), 14000)))
                                .putString("latam_last_result_url", url)
                                .apply();
                        boolean milesVisible = body.toLowerCase().contains("milhas");
                        status.setText(milesVisible
                                ? "Sessão conectada • página de milhas identificada."
                                : "Sessão conectada • aguardando as tarifas em milhas aparecerem.");
                    }
                    CookieManager.getInstance().flush();
                    if (finishAfterInspection && resultPage) finish();
                });
    }

    private void completeConnection() {
        if (closing) return;
        String url = webView.getUrl();
        if (url == null || url.contains("auth.latamairlines.com")) {
            status.setText("Conclua o login e o duplo fator antes de voltar.");
            markConnected(false);
            return;
        }
        status.setText("Confirmando a data selecionada…");
        webView.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()",
                encoded -> {
                    String body = decodeJavascriptString(encoded);
                    if (!bodyMatchesExpectedDate(body)) {
                        status.setText("A página aberta não corresponde a "
                                + displayDate(expectedDate) + ". Reabrindo a busca correta…");
                        dateRetryAttempted = true;
                        webView.clearCache(false);
                        webView.loadUrl(searchUrl);
                        return;
                    }
                    closing = true;
                    markConnected(true);
                    getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                            .putString("latam_last_result_url", url)
                            .putString("latam_last_result_text",
                                    body.substring(0, Math.min(body.length(), 14000)))
                            .apply();
                    status.setText("Sessão LATAM conectada. Voltando ao Radar…");
                    CookieManager.getInstance().flush();
                    if (!isFinishing()) LatamLoginActivity.this.finish();
                });
    }

    private String decodeJavascriptString(String encoded) {
        if (encoded == null || "null".equals(encoded)) return "";
        try {
            return new org.json.JSONTokener(encoded).nextValue().toString();
        } catch (Exception ignored) {
            return encoded;
        }
    }

    private void markConnected(boolean connected) {
        getSharedPreferences(SearchConfig.PREFS, MODE_PRIVATE).edit()
                .putBoolean("latam_session_connected", connected)
                .apply();
        CookieManager.getInstance().flush();
    }

    private String buildSearchUrl() {
        SearchConfig config = SearchConfig.load(this);
        List<LocalDate> dates = SearchConfig.parseDates(config.outboundDates);
        LocalDate date = dates.isEmpty() ? LocalDate.now().plusDays(1) : dates.get(0);
        expectedDate = date;
        String origin = AirportCatalog.isSaoPauloAll(config.originMode)
                ? "SAO" : AirportCatalog.extractCode(config.originMode);
        String departure = date + "T12:00:00.000Z";
        return "https://www.latamairlines.com/br/pt/oferta-voos"
                + "?origin=" + encode(origin)
                + "&outbound=" + encode(departure)
                + "&destination=" + encode(config.destination)
                + "&adt=" + config.adults
                + "&chd=" + config.children
                + "&inf=0&trip=OW&cabin=Economy&redemption=true&sort=RECOMMENDED"
                + "&_wt=" + System.currentTimeMillis();
    }

    private boolean bodyMatchesExpectedDate(String body) {
        if (expectedDate == null || body == null) return false;
        String numeric = String.format(Locale.ROOT, "%02d/%02d",
                expectedDate.getDayOfMonth(), expectedDate.getMonthValue());
        String[] months = {"jan", "fev", "mar", "abr", "mai", "jun",
                "jul", "ago", "set", "out", "nov", "dez"};
        String textual = String.format(Locale.ROOT, "%02d %s",
                expectedDate.getDayOfMonth(), months[expectedDate.getMonthValue() - 1]);
        String normalized = body.toLowerCase(Locale.ROOT);
        return normalized.contains(numeric) || normalized.contains(textual);
    }

    private String displayDate(LocalDate date) {
        if (date == null) return "a data selecionada";
        return String.format(Locale.getDefault(), "%02d/%02d/%04d",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception ignored) {
            return value;
        }
    }

    private TextView text(String value, float size, String color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor("#4B2B83")));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
