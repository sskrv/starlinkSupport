package org.example.logic;

import java.math.BigDecimal;

public enum Tariff {
    ACTIVATION_1M("Активация и 1 месяц", new BigDecimal("14500.00"), "tariff_act_1m"),
    SUBSCRIPTION_1M("1 месяц подписки", new BigDecimal("14000.00"), "tariff_sub_1m"),
    SUBSCRIPTION_2M("2 месяца подписки", new BigDecimal("28000.00"), "tariff_sub_2m"),
    GLOBAL("Глобальный тариф", new BigDecimal("54000.00"), "tariff_global");

    private final String displayName;
    private final BigDecimal price;
    private final String callbackData;

    Tariff(String displayName, BigDecimal price, String callbackData) {
        this.displayName = displayName;
        this.price = price;
        this.callbackData = callbackData;
    }

    public String getDisplayName() { return displayName; }
    public BigDecimal getPrice() { return price; }
    public String getCallbackData() { return callbackData; }

    // Метод для удобного поиска тарифа по его callbackData
    public static Tariff fromCallbackData(String callbackData) {
        for (Tariff tariff : values()) {
            if (tariff.callbackData.equals(callbackData)) {
                return tariff;
            }
        }
        return null;
    }
}
