package br.com.wondertravel.passagens;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.Locale;

final class DateRangeDialog {
    interface Listener {
        void onRangeSelected(LocalDate start, LocalDate end);
    }

    private final Context context;
    private final Dialog dialog;
    private final GridLayout daysGrid;
    private final TextView monthTitle;
    private final TextView selectionStatus;
    private final Button confirm;
    private final LocalDate minDate;
    private final int maxDays;
    private final Listener listener;
    private YearMonth visibleMonth;
    private LocalDate start;
    private LocalDate end;

    DateRangeDialog(Context context, String title, LocalDate initialStart,
                    LocalDate initialEnd, LocalDate minDate, int maxDays,
                    Listener listener) {
        this.context = context;
        this.start = initialStart;
        this.end = initialEnd;
        this.minDate = minDate;
        this.maxDays = maxDays;
        this.listener = listener;
        this.visibleMonth = YearMonth.from(initialStart != null ? initialStart : minDate);
        this.dialog = new Dialog(context);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(14));
        card.setBackground(rounded("#211A31", 20, "#7255B5"));

        TextView heading = text(title, 18, "#FFFFFF", true);
        card.addView(heading);

        selectionStatus = text("", 13, "#BBA9E8", false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, dp(6), 0, dp(12));
        card.addView(selectionStatus, statusParams);

        LinearLayout navigation = new LinearLayout(context);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        Button previous = navigationButton("‹");
        monthTitle = text("", 16, "#FFFFFF", true);
        monthTitle.setGravity(Gravity.CENTER);
        Button next = navigationButton("›");
        navigation.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(44)));
        navigation.addView(monthTitle, new LinearLayout.LayoutParams(
                0, dp(44), 1));
        navigation.addView(next, new LinearLayout.LayoutParams(dp(48), dp(44)));
        card.addView(navigation);

        daysGrid = new GridLayout(context);
        daysGrid.setColumnCount(7);
        card.addView(daysGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(12), 0, 0);
        Button cancel = actionButton("Cancelar", "#332B45");
        confirm = actionButton("Confirmar", "#7B4DFF");
        confirm.setEnabled(end != null);
        actions.addView(cancel);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48));
        confirmParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(confirm, confirmParams);
        card.addView(actions);

        previous.setOnClickListener(v -> {
            visibleMonth = visibleMonth.minusMonths(1);
            render();
        });
        next.setOnClickListener(v -> {
            visibleMonth = visibleMonth.plusMonths(1);
            render();
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        confirm.setOnClickListener(v -> {
            if (start != null && end != null) {
                listener.onRangeSelected(start, end);
                dialog.dismiss();
            }
        });

        dialog.setContentView(card);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.75f;
            window.setAttributes(attributes);
        }
        render();
    }

    void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void render() {
        monthTitle.setText(capitalize(visibleMonth.getMonth()
                .getDisplayName(TextStyle.FULL, new Locale("pt", "BR")))
                + " " + visibleMonth.getYear());
        selectionStatus.setText(statusText());
        confirm.setEnabled(start != null && end != null);
        daysGrid.removeAllViews();

        String[] weekdays = {"SEG", "TER", "QUA", "QUI", "SEX", "SÁB", "DOM"};
        for (String weekday : weekdays) {
            TextView label = text(weekday, 10, "#91879F", true);
            label.setGravity(Gravity.CENTER);
            daysGrid.addView(label, cellParams(dp(30)));
        }

        LocalDate first = visibleMonth.atDay(1);
        int emptyCells = first.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        for (int i = 0; i < emptyCells; i++) {
            daysGrid.addView(new View(context), cellParams(dp(46)));
        }

        for (int day = 1; day <= visibleMonth.lengthOfMonth(); day++) {
            LocalDate date = visibleMonth.atDay(day);
            TextView cell = text(String.valueOf(day), 14,
                    date.isBefore(minDate) ? "#554C64" : "#F4EFFA", false);
            cell.setGravity(Gravity.CENTER);
            cell.setEnabled(!date.isBefore(minDate));
            styleDate(cell, date);
            cell.setOnClickListener(v -> select(date));
            daysGrid.addView(cell, cellParams(dp(46)));
        }
    }

    private void select(LocalDate date) {
        if (date.isBefore(minDate)) return;
        if (start == null || end != null || date.isBefore(start)) {
            start = date;
            end = null;
        } else {
            long days = java.time.temporal.ChronoUnit.DAYS.between(start, date) + 1;
            if (days > maxDays) {
                Toast.makeText(context, "Escolha um período de até " + maxDays + " dias.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            end = date;
        }
        render();
    }

    private void styleDate(TextView cell, LocalDate date) {
        if (start != null && date.equals(start) || end != null && date.equals(end)) {
            cell.setBackground(rounded("#FF6B35", 22, null));
            cell.setTextColor(Color.WHITE);
        } else if (start != null && end != null
                && date.isAfter(start) && date.isBefore(end)) {
            cell.setBackground(rounded("#593E8D", 8, null));
            cell.setTextColor(Color.WHITE);
        } else {
            cell.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private String statusText() {
        if (start == null) return "Toque na data inicial.";
        if (end == null) return "Início: " + format(start)
                + " • agora escolha o fim (até " + maxDays + " dias).";
        return format(start) + " a " + format(end);
    }

    private GridLayout.LayoutParams cellParams(int height) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = height;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private Button navigationButton(String value) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextSize(24);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private Button actionButton(String value, String color) {
        Button button = new Button(context);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setBackground(rounded(color, 12, null));
        return button;
    }

    private TextView text(String value, float size, String color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.parseColor(color));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(String color, int radius, String stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.parseColor(color));
        shape.setCornerRadius(dp(radius));
        if (stroke != null) shape.setStroke(dp(1), Color.parseColor(stroke));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private String format(LocalDate date) {
        return String.format(Locale.getDefault(), "%02d/%02d/%04d",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(new Locale("pt", "BR"))
                + value.substring(1);
    }
}
