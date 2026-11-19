package com.myfinance.service;

import com.myfinance.model.Category;
import com.myfinance.model.Money;
import com.myfinance.model.Transaction;
import com.myfinance.model.Wallet;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ReportGenerator implements Report {
    @Override
    public String generate(Wallet wallet, YearMonth month) {
        if (wallet == null || month == null) {
            throw new IllegalArgumentException("Укажите кошелёк и месяц отчёта.");
        }
        BigDecimal income = Money.ZERO;
        BigDecimal expense = Money.ZERO;
        Map<Category, BigDecimal> totals = new LinkedHashMap<>();
        for (Transaction transaction : wallet.getTransactions()) {
            if (!YearMonth.from(transaction.getDate()).equals(month)) {
                continue;
            }
            if (transaction.getCategory().getType() == Category.Type.INCOME) {
                income = income.add(transaction.getAmount());
            } else {
                expense = expense.add(transaction.getAmount());
            }
            totals.merge(transaction.getCategory(), transaction.getAmount(), BigDecimal::add);
        }

        StringBuilder text = new StringBuilder("========== Финансовый отчёт ==========\n\nОтчёт за ")
                .append(month).append('\n');
        text.append("Доходы: ").append(income.toPlainString()).append(" RUB\n");
        text.append("Расходы: ").append(expense.toPlainString()).append(" RUB\n");
        text.append("Итог месяца: ").append(income.subtract(expense).toPlainString()).append(" RUB\n");
        text.append("Баланс за всё время: ").append(wallet.getBalance().toPlainString()).append(" RUB\n");
        text.append("\nПо категориям:\n");
        if (totals.isEmpty()) {
            text.append("  Операций нет.\n");
        }
        for (Category.Type type : Category.Type.values()) {
            text.append(type == Category.Type.INCOME ? "ДОХОДЫ:\n" : "РАСХОДЫ:\n");
            totals.forEach((category, total) -> {
                if (category.getType() == type) {
                    text.append("  ").append(type == Category.Type.INCOME ? "+ " : "- ")
                            .append(category.getName()).append(": ").append(total.toPlainString()).append(" RUB\n");
                }
            });
        }

        appendBudgetTable(text, wallet.getBudgetStatuses(month));
        for (String alert : wallet.getAlerts(month)) {
            text.append("Внимание: ").append(alert).append('\n');
        }
        return text.toString();
    }

    private void appendBudgetTable(StringBuilder text, List<Wallet.BudgetStatus> statuses) {
        text.append("\nБюджеты (RUB):\n");
        if (statuses.isEmpty()) {
            text.append("  Не заданы.\n");
            return;
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Категория", "Лимит", "Потрачено", "Остаток"});
        for (Wallet.BudgetStatus status : statuses) {
            rows.add(new String[]{status.category().getName(), status.limit().toPlainString(),
                    status.spent().toPlainString(), status.remaining().toPlainString()});
        }
        int[] widths = new int[4];
        for (String[] row : rows) {
            for (int column = 0; column < widths.length; column++) {
                widths[column] = Math.max(widths[column], row[column].length());
            }
        }
        String format = "%-" + widths[0] + "s | %" + widths[1] + "s | %" + widths[2] + "s | %" + widths[3] + "s\n";
        text.append(String.format(Locale.ROOT, format, (Object[]) rows.get(0)));
        text.append("-".repeat(widths[0])).append("-+-").append("-".repeat(widths[1])).append("-+-")
                .append("-".repeat(widths[2])).append("-+-").append("-".repeat(widths[3])).append('\n');
        for (int index = 1; index < rows.size(); index++) {
            text.append(String.format(Locale.ROOT, format, (Object[]) rows.get(index)));
        }
    }

    public Map<Category, BigDecimal> getTotalsForCategories(Wallet wallet, Category.Type type,
                                                           Collection<String> categoryNames, YearMonth month) {
        if (wallet == null || type == null || categoryNames == null || categoryNames.isEmpty()) {
            throw new IllegalArgumentException("Укажите кошелёк, тип операций и хотя бы одну категорию.");
        }
        Set<Category> selected = new LinkedHashSet<>();
        for (String name : categoryNames) {
            Category category = wallet.getCategory(name);
            if (category.getType() != type) {
                throw new IllegalArgumentException("Категория «" + category.getName() + "» имеет другой тип.");
            }
            selected.add(category);
        }
        Map<Category, BigDecimal> totals = new LinkedHashMap<>();
        selected.forEach(category -> totals.put(category, Money.ZERO));
        for (Transaction transaction : wallet.getTransactions()) {
            if (selected.contains(transaction.getCategory())
                    && (month == null || month.equals(YearMonth.from(transaction.getDate())))) {
                totals.merge(transaction.getCategory(), transaction.getAmount(), BigDecimal::add);
            }
        }
        return Collections.unmodifiableMap(totals);
    }

    public String generateCsv(Wallet wallet) {
        StringBuilder csv = new StringBuilder("date,type,category,amount\r\n");
        for (Transaction transaction : wallet.getTransactions()) {
            csv.append(transaction.getDate()).append(',').append(transaction.getCategory().getType()).append(',')
                    .append(csvField(transaction.getCategory().getName())).append(',')
                    .append(transaction.getAmount().toPlainString()).append("\r\n");
        }
        return csv.toString();
    }

    private String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
