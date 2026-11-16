package com.myfinance.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinance.model.Category;
import com.myfinance.model.Expense;
import com.myfinance.model.Income;
import com.myfinance.model.Wallet;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportGeneratorTest {
    private final Report report = new ReportGenerator();
    private final YearMonth month = YearMonth.of(2026, 11);

    @Test
    void reportsMonthlyTotalsCategoriesBudgetsAndAllTimeBalance() {
        Wallet wallet = new Wallet();
        Category salary = wallet.createCategory("Зарплата", Category.Type.INCOME);
        Category food = wallet.createCategory("Еда", Category.Type.EXPENSE);
        wallet.addTransaction(new Income(new BigDecimal("1000"), month.atDay(1), salary));
        wallet.addTransaction(new Expense(new BigDecimal("120"), month.atDay(2), food));
        wallet.addTransaction(new Expense(new BigDecimal("30"), month.atDay(3), food));
        wallet.addTransaction(new Income(new BigDecimal("300"), month.minusMonths(1).atDay(1), salary));
        wallet.setBudget("Еда", month, new BigDecimal("100"));
        String text = report.generate(wallet, month);
        assertAll(
                () -> assertTrue(text.contains("Доходы: 1000.00 RUB")),
                () -> assertTrue(text.contains("Расходы: 150.00 RUB")),
                () -> assertTrue(text.contains("Итог месяца: 850.00 RUB")),
                () -> assertTrue(text.contains("Баланс за всё время: 1150.00 RUB")),
                () -> assertTrue(text.contains("- Еда: 150.00 RUB")),
                () -> assertEquals(List.of("Еда", "100.00", "150.00", "-50.00"), budgetRow(text, "Еда")),
                () -> assertTrue(text.contains("Превышен бюджет")));
    }

    @Test
    void supportsEmptyWallet() {
        String text = report.generate(new Wallet(), month);
        assertTrue(text.contains("Доходы: 0.00 RUB"));
        assertTrue(text.contains("Расходы: 0.00 RUB"));
        assertTrue(text.contains("Операций нет."));
        assertTrue(text.contains("Не заданы."));
    }

    @Test
    void budgetWithoutExpensesShowsEntireLimitAsRemaining() {
        Wallet wallet = new Wallet();
        wallet.createCategory("Еда", Category.Type.EXPENSE);
        wallet.setBudget("Еда", month, BigDecimal.TEN);
        String text = report.generate(wallet, month);
        assertEquals(List.of("Еда", "10.00", "0.00", "10.00"), budgetRow(text, "Еда"));
        assertFalse(text.contains("Превышен бюджет"));
    }

    @Test
    void budgetTableKeepsColumnsAlignedWithLongNamesLargeAmountsAndSignedRemainders() {
        Wallet wallet = new Wallet();
        String longName = "Коммунальные услуги и обслуживание большой квартиры в ноябре";
        Category utilities = wallet.createCategory(longName, Category.Type.EXPENSE);
        Category food = wallet.createCategory("Еда", Category.Type.EXPENSE);
        wallet.createCategory("Такси", Category.Type.EXPENSE);
        wallet.addTransaction(new Expense(new BigDecimal("10.25"), month.atDay(1), utilities));
        wallet.addTransaction(new Expense(new BigDecimal("5"), month.atDay(1), food));
        wallet.setBudget(longName, month, BigDecimal.TEN);
        wallet.setBudget("Еда", month, new BigDecimal("5"));
        wallet.setBudget("Такси", month, new BigDecimal("123456789012345678901234567890.00"));
        String text = report.generate(wallet, month);
        List<String> table = text.lines().filter(line -> line.contains(" | ")).toList();
        assertEquals(4, table.size());
        assertEquals(List.of("Категория", "Лимит", "Потрачено", "Остаток"), budgetRow(text, "Категория"));
        assertEquals(List.of(longName, "10.00", "10.25", "-0.25"), budgetRow(text, longName));
        assertEquals(List.of("Еда", "5.00", "5.00", "0.00"), budgetRow(text, "Еда"));
        assertEquals(List.of("Такси", "123456789012345678901234567890.00", "0.00",
                "123456789012345678901234567890.00"), budgetRow(text, "Такси"));
        String header = table.get(0);
        for (String row : table) {
            assertEquals(header.length(), row.length());
            for (int index = 0; index < header.length(); index++) {
                if (header.charAt(index) == '|') {
                    assertEquals('|', row.charAt(index));
                }
            }
        }
        assertTrue(text.contains("Бюджеты (RUB):"));
    }

    private List<String> budgetRow(String text, String category) {
        return text.lines().filter(line -> line.contains(" | "))
                .map(line -> Arrays.stream(line.split("\\|", -1)).map(String::strip).toList())
                .filter(cells -> cells.get(0).equals(category)).findFirst().orElseThrow();
    }

    @Test
    void rejectsMissingReportArguments() {
        assertThrows(IllegalArgumentException.class, () -> report.generate(null, month));
        assertThrows(IllegalArgumentException.class, () -> report.generate(new Wallet(), null));
    }
}
