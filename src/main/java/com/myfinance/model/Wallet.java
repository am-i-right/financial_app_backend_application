package com.myfinance.model;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Wallet {
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final List<Transaction> transactions = new ArrayList<>();
    private final Map<YearMonth, Map<Category, BigDecimal>> budgets = new LinkedHashMap<>();

    public Category createCategory(String name, Category.Type type) {
        Category category = new Category(name, type);
        String key = categoryKey(name);
        if (categories.containsKey(key)) {
            throw new IllegalArgumentException("Категория с таким названием уже существует.");
        }
        categories.put(key, category);
        return category;
    }

    public Category getCategory(String name) {
        Category category = categories.get(categoryKey(name));
        if (category == null) {
            throw new IllegalArgumentException("Категория не найдена: " + name);
        }
        return category;
    }

    public List<Category> getCategories() {
        return List.copyOf(categories.values());
    }

    public void renameCategory(String oldName, String newName) {
        Category category = getCategory(oldName);
        String newKey = categoryKey(newName);
        Category existing = categories.get(newKey);
        if (existing != null && existing != category) {
            throw new IllegalArgumentException("Категория с таким названием уже существует.");
        }

        Map<String, Category> renamed = new LinkedHashMap<>();
        for (Category item : categories.values()) {
            renamed.put(item == category ? newKey : categoryKey(item.getName()), item);
        }
        category.rename(newName);
        categories.clear();
        categories.putAll(renamed);
    }

    public void deleteCategory(String name) {
        Category category = getCategory(name);
        if (transactions.stream().anyMatch(transaction -> transaction.getCategory() == category)) {
            throw new IllegalArgumentException("Категория используется в операциях; удаление запрещено.");
        }
        categories.remove(categoryKey(name));
        budgets.values().forEach(limits -> limits.remove(category));
        budgets.values().removeIf(Map::isEmpty);
    }

    public void addTransaction(Transaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Операция не должна быть null.");
        }
        Category category = transaction.getCategory();

        if (categories.get(categoryKey(category.getName())) != category) {
            throw new IllegalArgumentException("Категория не принадлежит этому кошельку.");
        }
        transactions.add(transaction);
    }

    public List<Transaction> getTransactions() {
        return List.copyOf(transactions);
    }

    public List<String> addTransactionWithAlerts(Transaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Операция не должна быть null.");
        }
        BigDecimal balanceBefore = getBalance();
        YearMonth month = YearMonth.from(transaction.getDate());
        BigDecimal spentBefore = spentIn(transaction.getCategory(), month);
        addTransaction(transaction);
        return new Alert().after(transaction, balanceBefore, spentBefore);
    }

    public BigDecimal getBalance() {
        BigDecimal balance = Money.ZERO;
        for (Transaction transaction : transactions) {
            balance = balance.add(transaction.getSignedAmount());
        }
        return balance;
    }

    public void setBudget(String categoryName, YearMonth month, BigDecimal limit) {
        requireMonth(month);
        Category category = getCategory(categoryName);
        if (category.getType() != Category.Type.EXPENSE) {
            throw new IllegalArgumentException("Бюджет можно задать только для категории расходов.");
        }
        BigDecimal checkedLimit = Money.positive(limit);
        budgets.computeIfAbsent(month, ignored -> new LinkedHashMap<>()).put(category, checkedLimit);
    }

    public List<BudgetStatus> getBudgetStatuses(YearMonth month) {
        requireMonth(month);
        List<BudgetStatus> result = new ArrayList<>();
        for (Map.Entry<Category, BigDecimal> entry : budgets.getOrDefault(month, Map.of()).entrySet()) {
            result.add(new BudgetStatus(entry.getKey(), month, entry.getValue(), spentIn(entry.getKey(), month)));
        }
        return List.copyOf(result);
    }

    public void removeBudget(String categoryName, YearMonth month) {
        requireMonth(month);
        Category category = getCategory(categoryName);
        Map<Category, BigDecimal> limits = budgets.get(month);
        if (limits == null || limits.remove(category) == null) {
            throw new IllegalArgumentException("Бюджет категории за указанный месяц не установлен.");
        }
        if (limits.isEmpty()) {
            budgets.remove(month);
        }
    }

    public List<BudgetStatus> getAllBudgetStatuses() {
        List<BudgetStatus> result = new ArrayList<>();
        for (YearMonth month : budgets.keySet()) {
            result.addAll(getBudgetStatuses(month));
        }
        return List.copyOf(result);
    }

    private BigDecimal spentIn(Category category, YearMonth month) {
        BigDecimal spent = Money.ZERO;
        for (Transaction transaction : transactions) {
            if (transaction instanceof Expense && transaction.getCategory() == category
                    && YearMonth.from(transaction.getDate()).equals(month)) {
                spent = spent.add(transaction.getAmount());
            }
        }
        return spent;
    }

    public List<String> getAlerts(YearMonth month) {
        requireMonth(month);
        return new Alert().check(month);
    }

    public List<String> getBudgetAlerts(String categoryName, YearMonth month) {
        requireMonth(month);
        Category category = getCategory(categoryName);
        BigDecimal limit = budgets.getOrDefault(month, Map.of()).get(category);
        if (limit == null) {
            return List.of();
        }
        return new Alert().checkBudget(new BudgetStatus(category, month, limit, spentIn(category, month)));
    }

    private static String categoryKey(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Укажите название категории.");
        }
        return name.strip().toLowerCase(Locale.ROOT);
    }

    private static void requireMonth(YearMonth month) {
        if (month == null) {
            throw new IllegalArgumentException("Укажите месяц.");
        }
    }

    public record BudgetStatus(Category category, YearMonth month, BigDecimal limit, BigDecimal spent) {
        public BigDecimal remaining() {
            return limit.subtract(spent);
        }

        public boolean exceeded() {
            return spent.compareTo(limit) > 0;
        }
    }

    private final class Alert {
        private List<String> after(Transaction transaction, BigDecimal balanceBefore, BigDecimal spentBefore) {
            List<String> messages = new ArrayList<>();
            YearMonth month = YearMonth.from(transaction.getDate());
            Category category = transaction.getCategory();
            BigDecimal limit = budgets.getOrDefault(month, Map.of()).get(category);
            if (transaction instanceof Expense && limit != null) {
                BigDecimal spentAfter = spentIn(category, month);
                BigDecimal threshold = limit.multiply(new BigDecimal("0.80"));
                if (spentBefore.compareTo(limit) <= 0 && spentAfter.compareTo(limit) > 0) {
                    messages.add(exceededMessage(category, month, spentAfter.subtract(limit)));
                } else if (spentBefore.compareTo(limit) < 0 && spentAfter.compareTo(limit) == 0) {
                    messages.add("Лимит категории «" + category.getName() + "» за " + month
                            + " полностью израсходован (100%).");
                } else if (spentBefore.compareTo(threshold) < 0 && spentAfter.compareTo(threshold) >= 0) {
                    messages.add("Достигнут порог 80% бюджета категории «" + category.getName() + "» за " + month + ".");
                }
            }
            BigDecimal balanceAfter = getBalance();
            if (balanceBefore.signum() >= 0 && balanceAfter.signum() < 0) {
                messages.add("Внимание: общий баланс стал отрицательным.");
            } else if (balanceBefore.signum() != 0 && balanceAfter.signum() == 0) {
                messages.add("Ваш баланс достиг нуля.");
            }
            return List.copyOf(messages);
        }

        private String exceededMessage(Category category, YearMonth month, BigDecimal excess) {
            return "Превышен бюджет категории «" + category.getName() + "» за " + month
                    + " на " + excess.toPlainString() + " RUB.";
        }

        private List<String> check(YearMonth month) {
            List<String> messages = new ArrayList<>();
            for (BudgetStatus status : getBudgetStatuses(month)) {
                messages.addAll(checkBudget(status));
            }
            if (getBalance().signum() < 0) {
                messages.add("Общие расходы превысили доходы: баланс кошелька отрицательный.");
            }
            return List.copyOf(messages);
        }

        private List<String> checkBudget(BudgetStatus status) {
            return status.exceeded()
                    ? List.of(exceededMessage(status.category(), status.month(), status.remaining().negate()))
                    : List.of();
        }
    }
}
