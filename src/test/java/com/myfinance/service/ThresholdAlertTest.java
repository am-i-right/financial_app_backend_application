package com.myfinance.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.myfinance.model.Category;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ThresholdAlertTest {
    private final FinanceManager finance;
    private final LocalDate date = LocalDate.of(2026, 11, 9);

    ThresholdAlertTest() {
        AuthService auth = new AuthService();
        auth.register("alice", "password1");
        auth.login("alice", "password1");
        finance = new FinanceManager(auth);
    }

    @BeforeEach
    void setupWallet() {
        finance.createCategory("Зарплата", Category.Type.INCOME);
        finance.createCategory("Еда", Category.Type.EXPENSE);
        finance.createCategory("Такси", Category.Type.EXPENSE);
        finance.addIncome("Зарплата", new BigDecimal("1000"), date);
        finance.setBudget("Еда", YearMonth.from(date), new BigDecimal("100"));
    }

    @ParameterizedTest
    @CsvSource({"79,1,80%", "80,10,NONE", "99,1,100%", "100,1,Превышен бюджет",
            "50,60,Превышен бюджет", "101,1,NONE"})
    void sendsOnlyHighestNewThreshold(String before, String amount, String message) {
        finance.addExpense("Еда", new BigDecimal(before), date);
        List<String> alerts = finance.addExpense("Еда", new BigDecimal(amount), date);
        if (message.equals("NONE")) {
            assertTrue(alerts.isEmpty());
        } else {
            assertEquals(1, alerts.size());
            assertTrue(alerts.get(0).contains(message), alerts.toString());
        }
    }

    @Test
    void unrelatedIncomeAndExpenseDoNotRepeatOldWarnings() {
        finance.addExpense("Еда", new BigDecimal("110"), date);
        assertTrue(finance.addIncome("Зарплата", BigDecimal.TEN, date).isEmpty());
        assertTrue(finance.addExpense("Такси", BigDecimal.TEN, date).isEmpty());
        assertTrue(finance.getAlerts(YearMonth.from(date)).get(0).contains("Превышен бюджет"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void settingOrReducingBudgetReportsOnlySelectedCategoryAndMonth(boolean existingBudget) {
        YearMonth month = YearMonth.from(date);
        finance.addExpense("Еда", new BigDecimal("110"), date);
        finance.setBudget("Такси", month.plusMonths(1), BigDecimal.TEN);
        finance.addExpense("Такси", new BigDecimal("20"), date.plusMonths(1));
        finance.addExpense("Такси", new BigDecimal("150"), date);
        if (existingBudget) {
            finance.setBudget("Такси", month, new BigDecimal("200"));
        }
        assertEquals(List.of("Превышен бюджет категории «Такси» за 2026-11 на 50.00 RUB."),
                finance.setBudget("Такси", month, new BigDecimal("100")));
        assertEquals(new BigDecimal("100.00"), finance.getBudgetStatuses(month).get(1).limit());
        assertEquals(new BigDecimal("-50.00"), finance.getBudgetStatuses(month).get(1).remaining());
    }

    @ParameterizedTest
    @ValueSource(strings = {"150", "200"})
    void settingBudgetAtOrAboveSpendingDoesNotRepeatAnotherCategoryWarning(String limit) {
        finance.addExpense("Еда", new BigDecimal("110"), date);
        finance.addExpense("Такси", new BigDecimal("150"), date);
        assertTrue(finance.setBudget("Такси", YearMonth.from(date), new BigDecimal(limit)).isEmpty());
    }

    @Test
    void balanceTransitionsToZeroAndNegativeAreReportedOnce() {
        assertEquals(List.of("Ваш баланс достиг нуля."),
                finance.addExpense("Такси", new BigDecimal("1000"), date));
        assertEquals(List.of("Внимание: общий баланс стал отрицательным."),
                finance.addExpense("Такси", BigDecimal.ONE, date));
        assertTrue(finance.addExpense("Такси", BigDecimal.ONE, date).isEmpty());
        assertTrue(finance.addIncome("Зарплата", BigDecimal.ONE, date).isEmpty());
        assertEquals(List.of("Ваш баланс достиг нуля."), finance.addIncome("Зарплата", BigDecimal.ONE, date));
    }

    @Test
    void positiveBalanceCanGoDirectlyNegative() {
        assertEquals(List.of("Внимание: общий баланс стал отрицательным."),
                finance.addExpense("Такси", new BigDecimal("1001"), date));
    }

    @Test
    void categoryWithoutBudgetDoesNotProduceBudgetWarnings() {
        assertTrue(finance.addExpense("Такси", BigDecimal.TEN, date).isEmpty());
    }

    @Test
    void thresholdsUseTransactionMonthIncludingPastMonth() {
        finance.setBudget("Еда", YearMonth.from(date).plusMonths(1), new BigDecimal("200"));
        assertTrue(finance.addExpense("Еда", new BigDecimal("79"), date).isEmpty());
        assertTrue(finance.addExpense("Еда", new BigDecimal("80"), date.plusMonths(1)).isEmpty());
        assertTrue(finance.addExpense("Еда", BigDecimal.ONE, date).get(0).contains("80%"));
        assertEquals(new BigDecimal("80.00"), finance.getBudgetStatuses(YearMonth.from(date)).get(0).spent());
        assertEquals(new BigDecimal("80.00"),
                finance.getBudgetStatuses(YearMonth.from(date).plusMonths(1)).get(0).spent());
    }
}
