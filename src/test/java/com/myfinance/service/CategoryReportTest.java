package com.myfinance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinance.model.Category;
import com.myfinance.model.Expense;
import com.myfinance.model.Income;
import com.myfinance.model.Money;
import com.myfinance.model.Wallet;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CategoryReportTest {
    private final Wallet wallet = new Wallet();
    private final ReportGenerator report = new ReportGenerator();
    private final YearMonth month = YearMonth.of(2026, 11);
    private Category food;
    private Category taxi;
    private Category fun;

    @BeforeEach
    void setupWallet() {
        food = wallet.createCategory("Еда", Category.Type.EXPENSE);
        taxi = wallet.createCategory("Такси", Category.Type.EXPENSE);
        fun = wallet.createCategory("Развлечения", Category.Type.EXPENSE);
        wallet.createCategory("Зарплата", Category.Type.INCOME);
        wallet.addTransaction(new Expense(new BigDecimal("300"), month.atDay(1), food));
        wallet.addTransaction(new Expense(new BigDecimal("500"), month.atDay(2), food));
        wallet.addTransaction(new Expense(new BigDecimal("1500"), month.atDay(3), taxi));
        wallet.addTransaction(new Expense(new BigDecimal("3000"), month.atDay(4), fun));
        wallet.addTransaction(new Expense(new BigDecimal("100"), month.minusMonths(1).atDay(1), food));
    }

    @Test
    void twoCategoriesAreSummedAndDuplicatesIgnoreCase() {
        Map<Category, BigDecimal> totals = report.getTotalsForCategories(wallet, Category.Type.EXPENSE,
                List.of("Еда", "Такси", " еДА "), month);
        assertEquals(2, totals.size());
        assertEquals(new BigDecimal("800.00"), totals.get(food));
        assertEquals(new BigDecimal("1500.00"), totals.get(taxi));
        assertEquals(new BigDecimal("2300.00"), totals.values().stream().reduce(Money.ZERO, BigDecimal::add));
    }

    @Test
    void threeCategoriesCanBeSelectedAcrossAllMonths() {
        Map<Category, BigDecimal> totals = report.getTotalsForCategories(wallet, Category.Type.EXPENSE,
                List.of("Еда", "Такси", "Развлечения"), null);
        assertEquals(new BigDecimal("900.00"), totals.get(food));
        assertEquals(new BigDecimal("3000.00"), totals.get(fun));
        assertEquals(3, totals.size());
        assertThrows(UnsupportedOperationException.class, totals::clear);
    }

    @Test
    void unknownEmptyAndIncompatibleSelectionsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> report.getTotalsForCategories(wallet, Category.Type.EXPENSE, List.of("Еда", "Нет"), month));
        assertThrows(IllegalArgumentException.class,
                () -> report.getTotalsForCategories(wallet, Category.Type.EXPENSE, List.of(), month));
        assertThrows(IllegalArgumentException.class,
                () -> report.getTotalsForCategories(wallet, Category.Type.EXPENSE, List.of("Еда", "Зарплата"), month));
        assertThrows(IllegalArgumentException.class,
                () -> report.getTotalsForCategories(wallet, Category.Type.EXPENSE, null, month));
    }

    @Test
    void categoriesWithoutTransactionsInMonthReturnZero() {
        Map<Category, BigDecimal> totals = report.getTotalsForCategories(wallet, Category.Type.EXPENSE,
                List.of("Еда", "Такси"), month.plusMonths(1));
        assertTrue(totals.values().stream().allMatch(Money.ZERO::equals));
    }

    @Test
    void newFinancialMethodsRequireAuthentication() {
        FinanceManager finance = new FinanceManager(new AuthService());
        assertThrows(IllegalStateException.class, () -> finance.renameCategory("Еда", "Питание"));
        assertThrows(IllegalStateException.class, () -> finance.deleteCategory("Еда"));
        assertThrows(IllegalStateException.class, () -> finance.removeBudget("Еда", month));
        assertThrows(IllegalStateException.class,
                () -> finance.getTotalsForCategories(Category.Type.EXPENSE, List.of("Еда"), month));
        assertThrows(IllegalStateException.class, finance::generateCsv);
        assertThrows(IllegalStateException.class, () -> finance.applyImport(null));
    }

    @Test
    void incomesAreSelectedSeparately() {
        Category salary = wallet.getCategory("Зарплата");
        wallet.addTransaction(new Income(BigDecimal.TEN, month.atDay(1), salary));
        assertEquals(new BigDecimal("10.00"), report.getTotalsForCategories(wallet, Category.Type.INCOME,
                List.of("Зарплата"), month).get(salary));
    }
}
