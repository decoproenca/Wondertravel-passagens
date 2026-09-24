package br.com.wondertravel.passagens;

import java.util.Locale;

final class SearchDiagnostics {
    static final int FIRST_INSPECTION_DELAY_MS = 7000;
    static final int INSPECTION_INTERVAL_MS = 3000;
    static final int MAX_INSPECTION_ATTEMPT = 39;

    static boolean isLoading(String text) {
        String value = normalize(text);
        return value.contains("aguarde enquanto buscamos")
                || value.contains("buscando os melhores voos")
                || value.contains("carregando");
    }

    static boolean isNoFare(String text) {
        String value = normalize(text);
        return value.contains("não encontramos voos")
                || value.contains("nao encontramos voos")
                || value.contains("nenhum voo encontrado")
                || value.contains("não há voos disponíveis")
                || value.contains("nao ha voos disponiveis")
                || value.contains("não há opções de voo")
                || value.contains("nao ha opcoes de voo");
    }

    static String describe(String text, int httpStatus, String webViewError) {
        if (httpStatus == 429) return "HTTP 429 — limite de acessos da Smiles";
        if (httpStatus == 403) return "HTTP 403 — acesso recusado pela Smiles";
        if (httpStatus >= 400) return "HTTP " + httpStatus + " retornado pela Smiles";
        if (webViewError != null && !webViewError.trim().isEmpty()) return webViewError;
        if (text == null || text.trim().isEmpty()) return "página vazia";
        if (isNoFare(text)) return "sem tarifa disponível";

        String value = normalize(text);
        if (value.contains("captcha") || value.contains("access denied")
                || value.contains("acesso negado") || value.contains("forbidden")
                || value.contains("atividade incomum")) {
            return "bloqueio de acesso detectado";
        }
        if (isLoading(text)) return "tempo limite — a Smiles continuou carregando";
        return "tempo limite — resposta da Smiles não reconhecida";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private SearchDiagnostics() {
    }
}
