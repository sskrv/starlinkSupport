package org.example.db;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    private Long id;

    @Column(name = "user_phone")
    private String phone;

    @Column(name = "subscription_expiry_date")
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