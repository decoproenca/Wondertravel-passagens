package br.com.wondertravel.passagens;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FlightParser {
    private static final Pattern CALENDAR_PRICE = Pattern.compile(
            "(?i)(\\d{1,2})\\s+(jan|fev|mar|abr|mai|jun|jul|ago|set|out|nov|dez)"
                    + "\\s+(\\d{1,3}(?:\\.\\d{3})+)\\s+milhas"
    );
    private static final Pattern MILES = Pattern.compile(
            "(?i)(?:a partir de\\s+)?(\\d{1,3}(?:\\.\\d{3})+)\\s+milhas"
    );
    private static final Pattern STOPS = Pattern.compile(
            "(?i)(direto|voo direto|(\\d+)\\s+parada(?:s)?)"
    );

    static Result parse(String text, SearchConfig.Task task) {
        if (text == null || text.isEmpty()) return null;
        LocalDate date = task.dates.get(0);
        Pattern flight = Pattern.compile(
                "(?is)\\b" + Pattern.quote(task.from)
                        + "\\s+(\\d{1,2}h\\d{2})\\s+"
                        + Pattern.quote(task.to)
                        + "\\s+(\\d{1,2}h\\d{2})(.*?)(?=\\b"
                        + Pattern.quote(task.from)
                        + "\\s+\\d{1,2}h\\d{2}\\s+"
                        + Pattern.quote(task.to)
                        + "\\s+\\d{1,2}h\\d{2}|\\z)"
        );

        Result best = null;
        Matcher cards = flight.matcher(text);
        while (cards.find()) {
            String block = cards.group(3);
            Matcher price = MILES.matcher(block);
            if (!price.find()) continue;
            int miles = parseMiles(price.group(1));
            String stops = "Paradas não identificadas";
            Matcher stopMatcher = STOPS.matcher(block);
            if (stopMatcher.find()) {
                if (stopMatcher.group(2) == null) stops = "Direto";
                else {
                    int count = Integer.parseInt(stopMatcher.group(2));
                    stops = count + (count == 1 ? " parada" : " paradas");
                }
            }
            Result candidate = new Result(
                    task.label, date, miles, cards.group(1), cards.group(2), stops
            );
            if (best == null || candidate.miles < best.miles) best = candidate;
        }
        if (best != null) return best;

        Matcher calendar = CALENDAR_PRICE.matcher(text);
        while (calendar.find()) {
            int day = Integer.parseInt(calendar.group(1));
            int month = monthNumber(calendar.group(2));
            if (date.getDayOfMonth() == day && date.getMonthValue() == month) {
                int miles = parseMiles(calendar.group(3));
                if (best == null || miles < best.miles) {
                    best = new Result(task.label, date, miles, null, null, null);
                }
            }
        }
        return best;
    }

    private static int parseMiles(String value) {
        return Integer.parseInt(value.replace(".", ""));
    }

    private static int monthNumber(String value) {
        String[] months = {
                "jan", "fev", "mar", "abr", "mai", "jun",
                "jul", "ago", "set", "out", "nov", "dez"
        };
        for (int i = 0; i < months.length; i++) {
            if (months[i].equals(value.toLowerCase(Locale.ROOT))) return i + 1;
        }
        return 0;
    }

    static final class Result {
        final String route;
        final LocalDate date;
        final int miles;
        final String departureTime;
        final String arrivalTime;
        final String stops;

        Result(String route, LocalDate date, int miles,
               String departureTime, String arrivalTime, String stops) {
            this.route = route;
            this.date = date;
            this.miles = miles;
            this.departureTime = departureTime;
            this.arrivalTime = arrivalTime;
            this.stops = stops;
        }

        boolean hasFlightDetails() {
            return departureTime != null && arrivalTime != null;
        }

        String displayDate() {
            return String.format(Locale.getDefault(), "%02d/%02d/%04d",
                    date.getDayOfMonth(), date.getMonthValue(), date.getYear());
        }
    }

    private FlightParser() {
    }
}
