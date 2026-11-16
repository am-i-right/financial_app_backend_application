package com.myfinance;

import com.myfinance.model.Category;
import com.myfinance.model.Money;
import com.myfinance.model.Transaction;
import com.myfinance.repository.WalletRepository;
import com.myfinance.service.AuthService;
import com.myfinance.service.FinanceManager;
import com.myfinance.service.ReportGenerator;
import com.myfinance.storage.ExportFiles;
import com.myfinance.storage.JsonUserRepository;
import com.myfinance.storage.JsonWalletRepository;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        try (Scanner input = new Scanner(System.in, StandardCharsets.UTF_8)) {
            run(input, new PrintStream(System.out, true, StandardCharsets.UTF_8),
                    Path.of(System.getProperty("finance.dataDir", "data")),
                    Path.of(System.getProperty("finance.exportsDir", "exports")));
        }
    }


    public static void run(Scanner input, PrintStream out) {
        run(input, out, new AuthService(), null, null);
    }

    public static void run(Scanner input, PrintStream out, Path dataDirectory, Path exportDirectory) {
        try {
            WalletRepository wallets = new JsonWalletRepository(dataDirectory, exportDirectory);
            AuthService auth = new AuthService(new JsonUserRepository(dataDirectory), wallets);
            run(input, out, auth, wallets, exportDirectory);
        } catch (IOException e) {
            out.println("Ошибка открытия хранилища: " + e.getMessage());
            out.println("Запуск остановлен; сохранённые файлы не изменены.");
        }
    }

    private static void run(Scanner input, PrintStream out, AuthService auth,
                            WalletRepository wallets, Path exportDirectory) {
        FinanceManager finance = new FinanceManager(auth, wallets);
        out.println("Личные финансы. " + (auth.isPersistent()
                ? "Изменения сохраняются при выходе из аккаунта и завершении. help — справка."
                : "Режим только в памяти. help — справка."));
        boolean saved;
        try {
            saved = commandLoop(input, out, auth, finance, exportDirectory);
        } catch (EndOfInput e) {
            out.println("\nВвод завершён. Попытка сохранения данных.");
            saved = save(auth, out);
        }
        out.println("Работа завершена. " + (auth.isPersistent()
                ? (saved ? "Данные сохранены." : "Сохранение не выполнено; проверьте ошибку выше.")
                : "Режим только в памяти завершён."));
    }

    private static boolean commandLoop(Scanner input, PrintStream out, AuthService auth,
                                        FinanceManager finance, Path exportDirectory) {
        while (true) {
            printMenu(auth, out);
            String command = read(input, out, "> ");
            if (command.equals("0")) {
                if (save(auth, out)) {
                    return true;
                }
                continue;
            }
            try {
                if (command.equalsIgnoreCase("help") || command.equals("11")) {
                    printHelp(out);
                } else if (!auth.isLoggedIn()) {
                    accountCommand(command, input, out, auth);
                } else {
                    financeCommand(command, input, out, auth, finance, exportDirectory);
                }
            } catch (DateTimeParseException e) {
                out.println("Ошибка: используйте существующую дату ГГГГ-ММ-ДД или месяц ГГГГ-ММ.");
            } catch (NumberFormatException e) {
                out.println("Ошибка: введите сумму числом, например 1250.50.");
            } catch (UncheckedIOException e) {
                out.println("Ошибка файла: " + e.getMessage());
            } catch (IOException e) {
                out.println("Ошибка файла: " + e.getMessage());
            } catch (IllegalArgumentException | IllegalStateException e) {
                out.println("Ошибка: " + e.getMessage());
            }
        }
    }

    private static boolean save(AuthService auth, PrintStream out) {
        try {
            auth.saveAll();
            return true;
        } catch (UncheckedIOException e) {
            out.println("Ошибка сохранения: " + e.getMessage());
            out.println("Данные не записаны. При доступном вводе можно исправить проблему и повторить выход.");
            return false;
        }
    }

    private static void printMenu(AuthService auth, PrintStream out) {
        if (!auth.isLoggedIn()) {
            out.println("\n1 Регистрация | 2 Вход | 11 Справка | 0 Завершить");
            return;
        }
        out.println("\nПользователь: " + auth.getCurrentUser().getLogin());
        out.println("""
                1 Категория | 2 Доход | 3 Расход | 4 Бюджет | 5 Баланс
                6 Операции по дате | 7 Отчёт за месяц | 8 Категории
                9 Выйти из аккаунта | 10 Операции по сумме | 11 Справка
                12 Переименовать категорию | 13 Удалить категорию | 14 Выборка категорий
                15 Экспорт CSV | 16 Экспорт JSON | 17 Импорт JSON
                18 Сохранить отчёт | 19 Удалить бюджет | 0 Завершить""");
    }

    private static void accountCommand(String command, Scanner input, PrintStream out, AuthService auth) {
        switch (command) {
            case "1" -> {
                auth.register(read(input, out, "Логин: "), readPassword(input, out));
                out.println("Регистрация выполнена. Теперь войдите.");
            }
            case "2" -> {
                auth.login(read(input, out, "Логин: "), readPassword(input, out));
                out.println("Вход выполнен.");
            }
            default -> out.println("Неизвестная команда.");
        }
    }

    private static void financeCommand(String command, Scanner input, PrintStream out, AuthService auth,
                                        FinanceManager finance, Path exportDirectory) throws IOException {
        switch (command) {
            case "1" -> {
                String name = read(input, out, "Название категории: ");
                finance.createCategory(name, readType(input, out));
                out.println("Категория создана.");
            }
            case "2", "3" -> addTransaction(command, input, out, finance);
            case "4" -> {
                String category = read(input, out, "Категория расходов: ");
                YearMonth month = readMonth(input, out);
                List<String> messages = finance.setBudget(category, month, readAmount(input, out));
                out.println("Бюджет установлен.");
                messages.forEach(out::println);
            }
            case "5" -> out.println("Баланс: " + finance.getBalance().toPlainString() + " RUB");
            case "6" -> printTransactions(finance.getTransactions(), out);
            case "7" -> out.print(finance.generateReport(new ReportGenerator(), readMonth(input, out)));
            case "8" -> printCategories(finance, out);
            case "9" -> {
                auth.logout();
                out.println("Вы вышли из аккаунта.");
            }
            case "10" -> printTransactions(finance.getTransactionsByAmount(), out);
            case "12" -> {
                String oldName = read(input, out, "Текущее название: ");
                finance.renameCategory(oldName, read(input, out, "Новое название: "));
                out.println("Категория переименована.");
            }
            case "13" -> {
                finance.deleteCategory(read(input, out, "Удаляемая категория: "));
                out.println("Категория и её неиспользуемые бюджеты удалены.");
            }
            case "14" -> categoryTotals(input, out, finance);
            case "15", "16", "17", "18" -> fileCommand(command, input, out, finance, exportDirectory);
            case "19" -> {
                String category = read(input, out, "Категория расходов: ");
                finance.removeBudget(category, readMonth(input, out));
                out.println("Бюджет удалён; операции сохранены.");
            }
            default -> out.println("Неизвестная команда.");
        }
    }

    private static void addTransaction(String command, Scanner input, PrintStream out, FinanceManager finance) {
        String category = read(input, out, "Категория: ");
        BigDecimal amount = readAmount(input, out);
        LocalDate date = readDate(input, out);
        List<String> messages = command.equals("2") ? finance.addIncome(category, amount, date)
                : finance.addExpense(category, amount, date);
        out.println("Операция добавлена.");
        messages.forEach(out::println);
    }

    private static void categoryTotals(Scanner input, PrintStream out, FinanceManager finance) {
        Category.Type type = readType(input, out);
        int count;
        try {
            count = Integer.parseInt(read(input, out, "Количество выбранных категорий: "));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Количество категорий должно быть целым числом.", e);
        }
        if (count <= 0) {
            throw new IllegalArgumentException("Выберите хотя бы одну категорию.");
        }
        List<String> names = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            names.add(read(input, out, "Категория " + (index + 1) + ": "));
        }
        String value = read(input, out, "Месяц ГГГГ-ММ (Enter — за всё время): ");
        YearMonth month = value.isEmpty() ? null : YearMonth.parse(value);
        Map<Category, BigDecimal> totals = finance.getTotalsForCategories(type, names, month);
        out.println((type == Category.Type.INCOME ? "ДОХОДЫ" : "РАСХОДЫ")
                + ": " + (month == null ? "за всё время" : month));
        BigDecimal total = Money.ZERO;
        for (Map.Entry<Category, BigDecimal> entry : totals.entrySet()) {
            out.println(entry.getKey().getName() + ": " + entry.getValue().toPlainString() + " RUB");
            total = total.add(entry.getValue());
        }
        out.println("Итого: " + total.toPlainString() + " RUB");
    }

    private static void fileCommand(String command, Scanner input, PrintStream out,
                                    FinanceManager finance, Path exportDirectory) throws IOException {
        if (exportDirectory == null) {
            throw new IllegalStateException("Файловое хранилище не подключено.");
        }
        switch (command) {
            case "15" -> {
                Path target = ExportFiles.resolve(exportDirectory, readFileName(input, out, "operations.csv"), ".csv");
                ExportFiles.writeText(target, finance.generateCsv());
                out.println("CSV сохранён: " + target);
            }
            case "16" -> {
                String name = readFileName(input, out, "wallet.json");
                finance.exportJson(name);
                out.println("JSON сохранён: " + exportDirectory.resolve(name));
            }
            case "17" -> {
                FinanceManager.PreparedImport prepared = finance.prepareImport(readFileName(input, out, "wallet.json"));
                String answer = read(input, out, "Заменить текущий кошелёк данными снимка? Введите да: ");
                if (answer.toLowerCase(Locale.ROOT).equals("да")) {
                    finance.applyImport(prepared);
                    out.println("Кошелёк восстановлен из JSON.");
                } else {
                    out.println("Импорт отменён; текущие данные сохранены.");
                }
            }
            case "18" -> {
                String text = finance.generateReport(new ReportGenerator(), readMonth(input, out));
                Path target = ExportFiles.resolve(exportDirectory, readFileName(input, out, "report.txt"), ".txt");
                ExportFiles.writeText(target, text);
                out.println("Отчёт сохранён: " + target);
            }
            default -> throw new IllegalArgumentException("Неизвестная файловая команда.");
        }
    }

    private static void printHelp(PrintStream out) {
        out.println("""
                СПРАВКА
                До входа: 1 регистрация, 2 вход. После входа:
                1 категория; 2 доход; 3 расход; 4 установить/изменить бюджет; 5 баланс;
                6 история по дате; 7 отчёт за месяц; 8 категории; 9 выход из аккаунта;
                10 история по сумме; 11 справка; 12 переименование; 13 удаление категории;
                14 выборка нескольких категорий (каждое название вводится отдельной строкой);
                15 экспорт CSV; 16 экспорт JSON; 17 импорт JSON; 18 отчёт в файл; 19 удаление бюджета.
                0 — сохранить изменения и завершить приложение.
                Суммы: 120.50 или 120,50, строго больше нуля, точность до копеек.
                Дата: 2026-11-09; месяц: 2026-11. Enter — сегодня/текущий месяц.
                Категория: «Еда дома»; запятые и кавычки допустимы. Бюджет привязан к месяцу.
                Отрицательный баланс разрешён. Уведомления: 80%, 100%, превышение, ноль, минус.
                Аккаунты и кошельки сохраняются в data/, экспорты — в exports/ (по умолчанию).
                Настройки директорий: -Dfinance.dataDir=... и -Dfinance.exportsDir=... перед -jar.
                Для файлов вводите только имя, например wallet.json, без пути.
                JSON-импорт: положите снимок в exports/, выберите 17 и подтвердите замену словом да.
                Импорт чужого кошелька запрещён. Экспорт в существующее имя заменяет этот экспорт.
                Пароль не сохраняется открытым текстом, но ввод в консоли виден.
                """);
    }

    private static void printCategories(FinanceManager finance, PrintStream out) {
        List<Category> categories = finance.getCategories();
        if (categories.isEmpty()) {
            out.println("Категорий пока нет.");
        }
        categories.forEach(category -> out.println(category.getName() + " — "
                + (category.getType() == Category.Type.INCOME ? "доход" : "расход")));
    }

    private static void printTransactions(List<Transaction> transactions, PrintStream out) {
        if (transactions.isEmpty()) {
            out.println("Операций пока нет.");
        }
        for (Transaction transaction : transactions) {
            out.println(transaction.getDate() + " | " + transaction.getCategory().getName()
                    + " | " + transaction.getSignedAmount().toPlainString() + " RUB");
        }
    }

    private static String read(Scanner input, PrintStream out, String prompt) {
        out.print(prompt);
        out.flush();
        if (!input.hasNextLine()) {
            throw new EndOfInput();
        }
        return input.nextLine().strip();
    }

    private static String readPassword(Scanner input, PrintStream out) {
        out.print("Пароль (минимум 8 символов; ввод виден): ");
        out.flush();
        if (!input.hasNextLine()) {
            throw new EndOfInput();
        }
        return input.nextLine();
    }

    private static Category.Type readType(Scanner input, PrintStream out) {
        return switch (read(input, out, "Тип (1 — доход, 2 — расход): ")) {
            case "1" -> Category.Type.INCOME;
            case "2" -> Category.Type.EXPENSE;
            default -> throw new IllegalArgumentException("Тип должен быть 1 или 2.");
        };
    }

    private static String readFileName(Scanner input, PrintStream out, String defaultName) {
        String value = read(input, out, "Имя файла (Enter — " + defaultName + "): ");
        return value.isEmpty() ? defaultName : value;
    }

    private static BigDecimal readAmount(Scanner input, PrintStream out) {
        return new BigDecimal(read(input, out, "Сумма RUB: ").replace(',', '.'));
    }

    private static LocalDate readDate(Scanner input, PrintStream out) {
        String value = read(input, out, "Дата ГГГГ-ММ-ДД (Enter — сегодня): ");
        return value.isEmpty() ? LocalDate.now() : LocalDate.parse(value);
    }

    private static YearMonth readMonth(Scanner input, PrintStream out) {
        String value = read(input, out, "Месяц ГГГГ-ММ (Enter — текущий): ");
        return value.isEmpty() ? YearMonth.now() : YearMonth.parse(value);
    }

    private static final class EndOfInput extends RuntimeException {
    }
}
