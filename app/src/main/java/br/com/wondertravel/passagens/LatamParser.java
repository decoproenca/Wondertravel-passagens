package br.com.wondertravel.passagens;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LatamParser {
    private static final Pattern ACCESSIBLE_FLIGHT = Pattern.compile(
            "(?is)Hora de sa(?:í|i)da\\s+(\\d{1,2}:\\d{2}).*?"
                    + "hora de chegada\\s+(\\d{1,2}:\\d{2})(?:\\s+do dia seguinte)?.*?"
                    + "Voo\\s+(direto|\\d+\\s+parada(?:s)?).*?"
                    + "Pre(?:ç|c)o de um adulto a partir de\\s+(\\d+)\\s+milhas"
    );

    static FlightParser.Result parse(String text, SearchConfig.Task task) {
        if (text == null || text.trim().isEmpty()) return null;
        Matcher matcher = ACCESSIBLE_FLIGHT.matcher(text);
        FlightParser.Result best = null;
        LocalDate date = task.dates.get(0);
        while (matcher.find()) {
            int miles;
            try {
                miles = Integer.parseInt(matcher.group(4));
            } catch (NumberFormatException ignored) {
                continue;
            }
            String rawStops = matcher.group(3).toLowerCase();
            String stops = rawStops.startsWith("direto")
                    ? "Direto"
                    : rawStops.substring(0, 1).toUpperCase() + rawStops.substring(1);
            FlightParser.Result candidate = new FlightParser.Result(
                    task.label, date, miles, matcher.group(1), matcher.group(2), stops);
            if (best == null || candidate.miles < best.miles) best = candidate;
        }
        return best;
    }

    static boolean hasFinishedLoading(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("preço de um adulto a partir de")
                || lower.contains("preco de um adulto a partir de")
                || lower.contains("não encontramos voos")
                || lower.contains("nao encontramos voos");
    }

    private LatamParser() {
    }
}
