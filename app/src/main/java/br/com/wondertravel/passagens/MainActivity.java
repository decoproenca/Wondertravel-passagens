package br.com.wondertravel.passagens;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;

public final class MainActivity extends Activity {
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

    private WebView webView;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        webView = findViewById(R.id.webView);
        Button testSearch = findViewById(R.id.testSearch);

        configureWebView();

        testSearch.setOnClickListener(view -> {
            status.setText("Abrindo GRU → BPS para 2 adultos e 2 crianças...");
            webView.loadUrl(TEST_SEARCH);
        });

        if (savedInstanceState == null) {
            webView.loadUrl(SMILES_HOME);
        } else {
            webView.restoreState(savedInstanceState);
        }
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
                    status.setText(
                            "Consulta aberta. Confirme se as tarifas aparecem abaixo."
                    );
                } else {
                    status.setText(
                            "Smiles carregada. Toque em “Testar GRU → BPS”."
                    );
                }
            }
        });
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
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
