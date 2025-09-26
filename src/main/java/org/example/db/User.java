package org.example.db;

import jakarta.persistence.*;
import java.time.LocalDateTime; // Импортируем правильный тип для даты и времени

@Entity
@Table(name = "users")
public class User {
    @Id
    private Long id; // ID пользователя из Telegram

    @Column(name = "user_phone")
    private String phone;

    // 💡 Заменили String на LocalDateTime для удобной работы с датами
    @Column(name = "subscription_expiry_date") // Дали полю осмысленное имя
    private LocalDateTime subscriptionExpiryDate;

    public User() {}

    public User(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public LocalDateTime getSubscriptionExpiryDate() {
        return subscriptionExpiryDate;
    }

    public void setSubscriptionExpiryDate(LocalDateTime subscriptionExpiryDate) {
        this.subscriptionExpiryDate = subscriptionExpiryDate;
    }
}