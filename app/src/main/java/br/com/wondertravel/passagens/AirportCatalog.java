package br.com.wondertravel.passagens;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AirportCatalog {
    static final String SAO_PAULO_ALL = "São Paulo — todos (GRU + CGH)";
    private static final Pattern CODE_AT_START = Pattern.compile("^([A-Z]{3})\\b");
    private static final Pattern CODE_IN_PARENTHESES = Pattern.compile("\\(([A-Z]{3})\\)");

    static List<String> options() {
        return Arrays.asList(
                SAO_PAULO_ALL,
                "GRU — São Paulo / Guarulhos", "CGH — São Paulo / Congonhas",
                "VCP — Campinas / Viracopos", "BPS — Porto Seguro", "IOS — Ilhéus",
                "SSA — Salvador", "GIG — Rio de Janeiro / Galeão",
                "SDU — Rio de Janeiro / Santos Dumont", "CNF — Belo Horizonte / Confins",
                "BSB — Brasília", "CWB — Curitiba", "POA — Porto Alegre",
                "FLN — Florianópolis", "REC — Recife", "FOR — Fortaleza",
                "NAT — Natal", "MCZ — Maceió", "AJU — Aracaju", "SLZ — São Luís",
                "BEL — Belém", "MAO — Manaus", "CGB — Cuiabá", "CGR — Campo Grande",
                "GYN — Goiânia", "VIX — Vitória", "NVT — Navegantes",
                "JOI — Joinville", "LDB — Londrina", "IGU — Foz do Iguaçu",
                "RAO — Ribeirão Preto", "UDI — Uberlândia", "MOC — Montes Claros",
                "PMW — Palmas", "THE — Teresina", "JPA — João Pessoa",
                "CPV — Campina Grande", "PNZ — Petrolina", "FEN — Fernando de Noronha",
                "PVH — Porto Velho", "RBR — Rio Branco", "MCP — Macapá",
                "BVB — Boa Vista", "STM — Santarém", "IMP — Imperatriz",
                "CXJ — Caxias do Sul", "XAP — Chapecó"
        );
    }

    static boolean isSaoPauloAll(String value) {
        String normalized = value == null ? "" : value.toUpperCase(Locale.ROOT);
        return normalized.contains("GRU + CGH")
                || normalized.contains("SÃO PAULO — TODOS")
                || normalized.contains("SAO PAULO - TODOS");
    }

    static String extractCode(String value) {
        if (value == null) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        Matcher start = CODE_AT_START.matcher(normalized);
        if (start.find()) return start.group(1);
        Matcher parentheses = CODE_IN_PARENTHESES.matcher(normalized);
        if (parentheses.find()) return parentheses.group(1);
        return normalized.length() == 3 ? normalized : "";
    }

    static String displayForCode(String code) {
        String normalized = code == null ? "" : code.toUpperCase(Locale.ROOT);
        for (String option : options()) {
            if (option.startsWith(normalized + " —")) return option;
        }
        return normalized;
    }

    private AirportCatalog() {
    }
}
