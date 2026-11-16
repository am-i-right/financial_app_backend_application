package com.myfinance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinance.model.Category;
import com.myfinance.model.Expense;
import com.myfinance.model.Income;
import com.myfinance.model.Wallet;
import com.myfinance.storage.ExportFiles;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportAndSpecificationTest {
    @TempDir
    Path temporary;
    private final YearMonth month = YearMonth.of(2026, 11);
    private final ReportGenerator report = new ReportGenerator();

    @Test
    void specificationExampleHasExpectedTotalsBudgetsAndAlert() {
        AuthService auth = new AuthService();
        auth.register("alice", "password1");
        auth.login("alice", "password1");
        FinanceManager finance = new FinanceManager(auth);
        for (String name : List.of("Зарплата", "Бонус")) {
            finance.createCategory(name, Category.Type.INCOME);
        }
        for (String name : List.of("Еда", "Развлечения", "Коммунальные услуги", "Такси")) {
            finance.createCategory(name, Category.Type.EXPENSE);
        }
        finance.addIncome("Зарплата", new BigDecimal("20000"), month.atDay(1));
        finance.addIncome("Зарплата", new BigDecimal("40000"), month.atDay(1));
        finance.addIncome("Бонус", new BigDecimal("3000"), month.atDay(1));
        finance.setBudget("Еда", month, new BigDecimal("4000"));
        finance.setBudget("Развлечения", month, new BigDecimal("3000"));
        finance.setBudget("Коммунальные услуги", month, new BigDecimal("2500"));
        finance.addExpense("Еда", new BigDecimal("300"), month.atDay(2));
        finance.addExpense("Еда", new BigDecimal("500"), month.atDay(2));
        finance.addExpense("Развлечения", new BigDecimal("3000"), month.atDay(2));
        List<String> alerts = finance.addExpense("Коммунальные услуги", new BigDecimal("3000"), month.atDay(2));
        finance.addExpense("Такси", new BigDecimal("1500"), month.atDay(2));
        assertEquals(new BigDecimal("54700.00"), finance.getBalance());
        String text = finance.generateReport(report, month);
        assertTrue(text.contains("Доходы: 63000.00 RUB"));
        assertTrue(text.contains("Расходы: 8300.00 RUB"));
        assertTrue(text.contains("Зарплата: 60000.00 RUB"));
        assertTrue(text.contains("Бонус: 3000.00 RUB"));
        List<String> rows = text.lines().map(line -> line.strip().replaceAll("\\s*\\|\\s*", " | ")).toList();
        assertTrue(rows.contains("Еда | 4000.00 | 800.00 | 3200.00"));
        assertTrue(rows.contains("Развлечения | 3000.00 | 3000.00 | 0.00"));
        assertTrue(rows.contains("Коммунальные услуги | 2500.00 | 3000.00 | -500.00"));
        var statuses = finance.getBudgetStatuses(month);
        assertEquals(new BigDecimal("3200.00"), statuses.get(0).remaining());
        assertEquals(new BigDecimal("0.00"), statuses.get(1).remaining());
        assertEquals(new BigDecimal("-500.00"), statuses.get(2).remaining());
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).contains("Коммунальные услуги» за 2026-11 на 500.00 RUB"));
    }

    @Test
    void csvEscapesCommasQuotesAndLineBreaksAndPreservesTypesAndScale() throws IOException {
        Wallet wallet = new Wallet();
        Category salary = wallet.createCategory("Зарплата", Category.Type.INCOME);
        Category special = wallet.createCategory("Еда, \"дом\"\nмагазин", Category.Type.EXPENSE);
        wallet.addTransaction(new Income(new BigDecimal("20000"), month.atDay(1), salary));
        wallet.addTransaction(new Expense(new BigDecimal("300.5"), month.atDay(2), special));
        String csv = report.generateCsv(wallet);
        assertEquals("date,type,category,amount\r\n"
                + "2026-11-01,INCOME,Зарплата,20000.00\r\n"
                + "2026-11-02,EXPENSE,\"Еда, \"\"дом\"\"\nмагазин\",300.50\r\n", csv);
        Path target = ExportFiles.resolve(temporary, "operations.csv", ".csv");
        ExportFiles.writeText(target, csv);
        assertEquals(csv, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void emptyCsvContainsHeader() {
        assertEquals("date,type,category,amount\r\n", report.generateCsv(new Wallet()));
    }

    @Test
    void savedTextIsExactlySameAsReportAndCanReplacePreviousExport() throws IOException {
        Wallet wallet = new Wallet();
        Category food = wallet.createCategory("Еда", Category.Type.EXPENSE);
        wallet.addTransaction(new Expense(new BigDecimal("15"), month.atDay(1), food));
        wallet.setBudget("Еда", month, BigDecimal.TEN);
        String text = report.generate(wallet, month);
        assertTrue(text.contains("Категория"));
        assertTrue(text.contains("-5.00"));
        Path target = ExportFiles.resolve(temporary.resolve("nested"), "report.txt", ".txt");
        ExportFiles.writeText(target, "previous export");
        ExportFiles.writeText(target, text);
        assertEquals(text, Files.readString(target, StandardCharsets.UTF_8));
        try (var files = Files.list(target.getParent())) {
            assertEquals(1, files.count());
        }
    }
}
