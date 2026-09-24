package br.com.wondertravel.passagens;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class SearchConfig {
    static final String PREFS = "monitor";
    static final String DEFAULT_OUTBOUND = "09/10/2026, 10/10/2026, 11/10/2026";
    static final String DEFAULT_RETURN = "17/10/2026, 18/10/2026";

    final String originMode;
    final String destination;
    final String outboundDates;
    final String returnDates;
    final int adults;
    final int children;
    final int targetMiles;
    final int intervalMinutes;

    SearchConfig(String originMode, String destination, String outboundDates,
                 String returnDates, int adults, int children,
                 int targetMiles, int intervalMinutes) {
        this.originMode = originMode;
        this.destination = AirportCatalog.extractCode(destination);
        this.outboundDates = outboundDates.trim();
        this.returnDates = returnDates.trim();
        this.adults = adults;
        this.children = children;
        this.targetMiles = targetMiles;
        this.intervalMinutes = intervalMinutes;
    }

    static SearchConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new SearchConfig(
                p.getString("origin_mode", "São Paulo (GRU + CGH)"),
                p.getString("destination", "BPS"),
                p.getString("outbound_dates", DEFAULT_OUTBOUND),
                p.getString("return_dates", DEFAULT_RETURN),
                p.getInt("adults", 2),
                p.getInt("children", 2),
                p.getInt("target_miles", 31000),
                p.getInt("interval_minutes", 60)
        );
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("origin_mode", originMode)
                .putString("destination", destination)
                .putString("outbound_dates", outboundDates)
                .putString("return_dates", returnDates)
                .putInt("adults", adults)
                .putInt("children", children)
                .putInt("target_miles", targetMiles)
                .putInt("interval_minutes", intervalMinutes)
                .apply();
    }

    List<Task> createTasks() {
        List<LocalDate> outbound = parseDates(outboundDates);
        List<LocalDate> returns = parseDates(returnDates);
        if (outbound.isEmpty()) throw new IllegalArgumentException("Informe ao menos uma data de ida.");
        if (returns.isEmpty()) throw new IllegalArgumentException("Informe ao menos uma data de volta.");
        validateDateRange(outbound, "ida");
        validateDateRange(returns, "volta");
        if (!returns.get(0).isAfter(outbound.get(0))) {
            throw new IllegalArgumentException("A volta precisa ser posterior à ida.");
        }
        if (destination.length() != 3) {
            throw new IllegalArgumentException("Selecione ou informe um aeroporto de destino.");
        }
        if (adults < 1 || children < 0 || targetMiles < 1000 || intervalMinutes < 15) {
            throw new IllegalArgumentException("Revise passageiros, limite e intervalo mínimo de 15 minutos.");
        }

        List<String> origins;
        if (AirportCatalog.isSaoPauloAll(originMode)) {
            origins = Arrays.asList("GRU", "CGH");
        } else {
            String origin = AirportCatalog.extractCode(originMode);
            if (origin.length() != 3) {
                throw new IllegalArgumentException("Selecione ou informe um aeroporto de origem.");
            }
            origins = Arrays.asList(origin);
        }

        List<Task> tasks = new ArrayList<>();
        for (String origin : origins) {
            for (LocalDate date : outbound) {
                tasks.add(new Task(origin, destination, origin + " → " + destination,
                        Arrays.asList(date), returns.get(returns.size() - 1)));
            }
        }
        for (String origin : origins) {
            for (LocalDate date : returns) {
                tasks.add(new Task(destination, origin, destination + " → " + origin,
                        Arrays.asList(date), date.plusDays(1)));
            }
        }
        return tasks;
    }

    private static void validateDateRange(List<LocalDate> dates, String label) {
        if (dates.size() > 3) {
            throw new IllegalArgumentException("O período de " + label
                    + " pode ter no máximo 3 dias.");
        }
        for (int i = 1; i < dates.size(); i++) {
            if (!dates.get(i).equals(dates.get(i - 1).plusDays(1))) {
                throw new IllegalArgumentException("As datas de " + label
                        + " precisam ser consecutivas.");
            }
        }
    }

    static List<LocalDate> parseDates(String value) {
        List<LocalDate> dates = new ArrayList<>();
        for (String raw : value.split(",")) {
            String text = raw.trim();
            if (text.isEmpty()) continue;
            String[] parts = text.split("/");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Use datas no formato dd/mm/aaaa, separadas por vírgula.");
            }
            try {
                dates.add(LocalDate.of(
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[0])
                ));
            } catch (Exception error) {
                throw new IllegalArgumentException("Data inválida: " + text);
            }
        }
        return dates;
    }

    static final class Task {
        final String from;
        final String to;
        final String label;
        final List<LocalDate> dates;
        final LocalDate urlReturnDate;

        Task(String from, String to, String label,
             List<LocalDate> dates, LocalDate urlReturnDate) {
            this.from = from;
            this.to = to;
            this.label = label;
            this.dates = new ArrayList<>(dates);
            this.urlReturnDate = urlReturnDate;
        }

        boolean accepts(int day, int month) {
            for (LocalDate date : dates) {
                if (date.getDayOfMonth() == day && date.getMonthValue() == month) return true;
            }
            return false;
        }

        String displayDate(LocalDate date) {
            return String.format(Locale.getDefault(), "%02d/%02d/%04d",
                    date.getDayOfMonth(), date.getMonthValue(), date.getYear());
        }

        long departureTimestamp() {
            LocalDate centerDate = dates.get(dates.size() / 2);
            return centerDate.atTime(12, 0)
                    .atZone(ZoneId.of("America/Sao_Paulo"))
                    .toInstant().toEpochMilli();
        }

        long returnTimestamp() {
            return urlReturnDate.atTime(12, 0)
                    .atZone(ZoneId.of("America/Sao_Paulo"))
                    .toInstant().toEpochMilli();
        }
    }

}
