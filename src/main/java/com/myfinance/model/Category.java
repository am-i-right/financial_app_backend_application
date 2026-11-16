package com.myfinance.model;

public final class Category {
    public enum Type { INCOME, EXPENSE }

    private String name;
    private final Type type;

    public Category(String name, Type type) {
        if (type == null) {
            throw new IllegalArgumentException("Укажите тип категории.");
        }
        rename(name);
        this.type = type;
    }

    void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Название категории не должно быть пустым.");
        }
        this.name = name.strip();
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }
}
