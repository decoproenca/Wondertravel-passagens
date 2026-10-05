package br.com.wondertravel.passagens;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LatamCashParser {
    private static final String MONEY = "(?:R\\$|BRL)\\s*([0-9]{1,3}(?:\\.[0-9]{3})*,[0-9]{2})";
    private static final Pattern ACCESSIBLE_FLIGHT = Pattern.compile(
            "(?is)Hora de sa(?:í|i)da\\s+(\\d{1,2}:\\d{2}).*?"
                    + "hora de chegada\\s+(\\d{1,2}:\\d{2})(?:\\s+do dia seguinte)?.*?"
                    + "Voo\\s+(direto|\\d+\\s+parada(?:s)?).*?"
                    + "(?:Pre(?:ç|c)o de um adulto a partir de|Por pessoa a partir de).*?"
                    + MONEY
    );
    private static final Pattern ANY_PRICE = Pattern.compile("(?i)" + MONEY);

    static FlightParser.Result parse(String text, SearchConfig.Task task) {
        if (text == null || text.trim().isEmpty()) return null;
        LocalDate date = task.dates.get(0);
        FlightParser.Result best = null;
        Matcher matcher = ACCESSIBLE_FLIGHT.matcher(text);
        while (matcher.find()) {
            int cents = parseCents(matcher.group(4));
            if (cents < 0) continue;
            String rawStops = matcher.group(3).toLowerCase();
            String stops = rawStops.startsWith("direto")
                    ? "Direto"
                    : rawStops.substring(0, 1).toUpperCase() + rawStops.substring(1);
            FlightParser.Result candidate = new FlightParser.Result(
                    task.label, date, cents, matcher.group(1), matcher.group(2), stops);
            if (best == null || candidate.miles < best.miles) best = candidate;
        }
        if (best != null) return best;

        Matcher any = ANY_PRICE.matcher(text);
        while (any.find()) {
            int cents = parseCents(any.group(1));
            if (cents > 0 && (best == null || cents < best.miles)) {
                best = new FlightParser.Result(task.label, date, cents, null, null, null);
            }
        }
        return best;
    }

    static boolean hasFinishedLoading(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return (lower.contains("preço de um adulto a partir de")
                || lower.contains("preco de um adulto a partir de")
                || lower.contains("por pessoa a partir de"))
                && (text.contains("R$") || text.contains("BRL"))
                || lower.contains("não encontramos voos")
                || lower.contains("nao encontramos voos");
    }

    private static int parseCents(String value) {
        try {
            String normalized = value.replace(".", "").replace(',', '.');
            return (int) Math.round(Double.parseDouble(normalized) * 100.0);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private LatamCashParser() {
    }
}
