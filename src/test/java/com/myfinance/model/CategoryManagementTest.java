package com.myfinance.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class CategoryManagementTest {
    private final Wallet wallet = new Wallet();
    private final YearMonth month = YearMonth.of(2026, 11);

    @Test
    void renamingKeepsCategoryIdentityHistoryBudgetsAndOrder() {
        Category category = wallet.createCategory("Еда", Category.Type.EXPENSE);
        wallet.createCategory("Такси", Category.Type.EXPENSE);
        wallet.addTransaction(new Expense(BigDecimal.TEN, month.atDay(1), category));
        wallet.setBudget("Еда", month, new BigDecimal("100"));
        wallet.setBudget("Еда", month.plusMonths(1), new BigDecimal("200"));
        wallet.renameCategory("Еда", " Питание ");
        assertSame(category, wallet.getCategory("питание"));
        assertSame(category, wallet.getCategories().get(0));
        assertSame(category, wallet.getTransactions().get(0).getCategory());
        assertEquals("Питание", category.getName());
        assertEquals(new BigDecimal("90.00"), wallet.getBudgetStatuses(month).get(0).remaining());
        assertEquals(new BigDecimal("200.00"), wallet.getBudgetStatuses(month.plusMonths(1)).get(0).limit());
        assertThrows(IllegalArgumentException.class, () -> wallet.getCategory("Еда"));
        wallet.addTransaction(new Expense(BigDecimal.ONE, month.atDay(2), category));
    }

    @Test
    void changingOnlyCaseWorksWithoutCreatingDuplicate() {
        Category category = wallet.createCategory("Еда", Category.Type.EXPENSE);
        wallet.renameCategory("Еда", "ЕДА");
        assertSame(category, wallet.getCategory("еда"));
        assertEquals("ЕДА", category.getName());
        assertEquals(1, wallet.getCategories().size());
    }

    @Test
    void invalidRenamingIsAtomic() {
        Category food = wallet.createCategory("Еда", Category.Type.EXPENSE);
        wallet.createCategory("Такси", Category.Type.EXPENSE);
        assertThrows(IllegalArgumentException.class, () -> wallet.renameCategory("Еда", " такси "));
        assertThrows(IllegalArgumentException.class, () -> wallet.renameCategory("Еда", " "));
        assertThrows(IllegalArgumentException.class, () -> wallet.renameCategory("Еда", null));
        assertThrows(IllegalArgumentException.class, () -> wallet.renameCategory("Нет", "Другое"));
        assertSame(food, wallet.getCategory("Еда"));
        assertEquals("Еда", food.getName());
        assertEquals(2, wallet.getCategories().size());
    }

    @Test
    void deletingUnusedCategoryRemovesItsBudgetsInAllMonths() {
        wallet.createCategory("Еда", Category.Type.EXPENSE);
        wallet.setBudget("Еда", month, BigDecimal.TEN);
        wallet.setBudget("Еда", month.plusMonths(1), BigDecimal.ONE);
        wallet.deleteCategory("еда");
        assertTrue(wallet.getCategories().isEmpty());
        assertTrue(wallet.getAllBudgetStatuses().isEmpty());
    }

    @Test
    void deletingUsedCategoryIsForbiddenAndDoesNotRemoveBudgets() {
        Category food = wallet.createCategory("Еда", Category.Type.EXPENSE);
        wallet.addTransaction(new Expense(BigDecimal.ONE, month.atDay(1), food));
        wallet.setBudget("Еда", month, BigDecimal.TEN);
        assertThrows(IllegalArgumentException.class, () -> wallet.deleteCategory("Еда"));
        assertSame(food, wallet.getCategory("Еда"));
        assertEquals(1, wallet.getBudgetStatuses(month).size());
        assertEquals(1, wallet.getTransactions().size());
    }

    @Test
    void deletingUnknownCategoryIsReported() {
        assertThrows(IllegalArgumentException.class, () -> wallet.deleteCategory("Нет"));
    }

    @Test
    void removingBudgetKeepsTransactionsAndOtherMonths() {
        Category food = wallet.createCategory("Еда", Category.Type.EXPENSE);
        wallet.addTransaction(new Expense(BigDecimal.ONE, month.atDay(1), food));
        wallet.setBudget("Еда", month, BigDecimal.TEN);
        wallet.setBudget("Еда", month.plusMonths(1), BigDecimal.TEN);
        wallet.removeBudget("Еда", month);
        assertTrue(wallet.getBudgetStatuses(month).isEmpty());
        assertEquals(1, wallet.getBudgetStatuses(month.plusMonths(1)).size());
        assertEquals(1, wallet.getTransactions().size());
        assertThrows(IllegalArgumentException.class, () -> wallet.removeBudget("Еда", month));
    }
}
