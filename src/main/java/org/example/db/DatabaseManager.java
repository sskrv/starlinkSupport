package org.example.db;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Repository для управления пользователями и их данными в базе данных.
 * Предоставляет методы для создания, обновления и получения информации о пользователях.
 */
@Repository
@Validated
public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static final int DEFAULT_SUBSCRIPTION_DAYS = 30;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Сохраняет или обновляет номер телефона пользователя.
     * Если пользователя нет, создает новую запись.
     *
     * @param userId      ID пользователя в Telegram (должен быть положительным)
     * @param phoneNumber Номер телефона пользователя (не должен быть пустым)
     * @throws IllegalArgumentException если параметры некорректны
     * @throws DataAccessException при ошибках работы с БД
     */
    @Transactional
    public void saveUserPhoneNumber(@Positive long userId, @NotBlank String phoneNumber) {
        validatePhoneNumber(phoneNumber);

        try {
            User user = entityManager.find(User.class, userId);

            if (user == null) {
                user = createNewUser(userId, phoneNumber);
                logger.info("Created new user with ID: {} and phone number", userId);
            } else {
                updateUserPhoneNumber(user, phoneNumber);
                logger.info("Updated phone number for user with ID: {}", userId);
            }
        } catch (PersistenceException e) {
            logger.error("Database error while saving phone number for user ID {}: {}", userId, e.getMessage(), e);
            throw new DataAccessException("Failed to save user phone number", e) {};
        } catch (Exception e) {
            logger.error("Unexpected error while saving phone number for user ID {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Failed to save user phone number", e);
        }
    }

    /**
     * Устанавливает или продлевает подписку пользователя.
     * Если пользователь не существует, создает его автоматически.
     *
     * @param userId ID пользователя в Telegram (должен быть положительным)
     * @param days   Количество дней подписки (по умолчанию 30)
     * @throws IllegalArgumentException если параметры некорректны
     * @throws DataAccessException при ошибках работы с БД
     */
    @Transactional
    public void updateSubscription(@Positive long userId, int days) {
        if (days <= 0) {
            throw new IllegalArgumentException("Subscription days must be positive");
        }

        try {
            User user = findUserByIdInternal(userId);

            // Если пользователь не существует, создаем его
            if (user == null) {
                logger.info("User with ID {} not found, creating new user for subscription", userId);
                user = new User(userId);
                entityManager.persist(user);
            }

            LocalDateTime currentExpiry = user.getSubscriptionExpiryDate();
            LocalDateTime newExpiry;

            // Если подписка еще действует, продлеваем от текущей даты окончания
            if (currentExpiry != null && currentExpiry.isAfter(LocalDateTime.now())) {
                newExpiry = currentExpiry.plusDays(days);
            } else {
                // Если подписка истекла или отсутствует, начинаем с текущей даты
                newExpiry = LocalDateTime.now().plusDays(days);
            }

            user.setSubscriptionExpiryDate(newExpiry);
            entityManager.merge(user);

            logger.info("Subscription for user {} updated. New expiry date: {}", userId, newExpiry);
        } catch (PersistenceException e) {
            logger.error("Database error while updating subscription for user ID {}: {}", userId, e.getMessage(), e);
            throw new DataAccessException("Failed to update user subscription", e) {};
        } catch (Exception e) {
            logger.error("Unexpected error while updating subscription for user ID {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Failed to update user subscription", e);
        }
    }

    /**
     * Устанавливает или продлевает подписку пользователя на стандартный период (30 дней).
     *
     * @param userId ID пользователя в Telegram
     */
    @Transactional
    public void updateSubscription(@Positive long userId) {
        updateSubscription(userId, DEFAULT_SUBSCRIPTION_DAYS);
    }

    /**
     * Получает пользователя по его ID.
     *
     * @param userId ID пользователя (должен быть положительным)
     * @return Optional с объектом User, или Optional.empty() если пользователь не найден
     */
    @Transactional(readOnly = true)
    public Optional<User> findUserById(@Positive long userId) {
        try {
            User user = findUserByIdInternal(userId);
            return Optional.ofNullable(user);
        } catch (PersistenceException e) {
            logger.error("Database error while finding user by ID {}: {}", userId, e.getMessage(), e);
            throw new DataAccessException("Failed to find user", e) {};
        } catch (Exception e) {
            logger.error("Unexpected error while finding user by ID {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Failed to find user", e);
        }
    }

    /**
     * Проверяет, активна ли подписка пользователя.
     *
     * @param userId ID пользователя
     * @return true если подписка активна, false в противном случае
     */
    @Transactional(readOnly = true)
    public boolean isSubscriptionActive(@Positive long userId) {
        Optional<User> userOpt = findUserById(userId);
        if (userOpt.isEmpty()) {
            return false;
        }

        User user = userOpt.get();
        LocalDateTime expiryDate = user.getSubscriptionExpiryDate();
        return expiryDate != null && expiryDate.isAfter(LocalDateTime.now());
    }

    /**
     * Проверяет существование пользователя в базе данных.
     *
     * @param userId ID пользователя
     * @return true если пользователь существует, false в противном случае
     */
    @Transactional(readOnly = true)
    public boolean userExists(@Positive long userId) {
        return findUserById(userId).isPresent();
    }

    /**
     * Создает пользователя, если он не существует, или возвращает существующего.
     * Полезно для гарантии существования пользователя перед операциями.
     *
     * @param userId ID пользователя
     * @return существующий или новый пользователь
     */
    @Transactional
    public User getOrCreateUser(@Positive long userId) {
        try {
            User user = findUserByIdInternal(userId);

            if (user == null) {
                logger.info("Creating new user with ID: {}", userId);
                user = new User(userId);
                entityManager.persist(user);
            }

            return user;
        } catch (PersistenceException e) {
            logger.error("Database error while getting or creating user {}: {}", userId, e.getMessage(), e);
            throw new DataAccessException("Failed to get or create user", e) {};
        } catch (Exception e) {
            logger.error("Unexpected error while getting or creating user {}: {}", userId, e.getMessage(), e);
            throw new RuntimeException("Failed to get or create user", e);
        }
    }

    private User findUserByIdInternal(long userId) {
        return entityManager.find(User.class, userId);
    }

    private User createNewUser(long userId, String phoneNumber) {
        User user = new User(userId);
        user.setPhone(phoneNumber);
        entityManager.persist(user);
        return user;
    }

    private void updateUserPhoneNumber(User user, String phoneNumber) {
        user.setPhone(phoneNumber);
        entityManager.merge(user);
    }

    private void validatePhoneNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("Phone number cannot be null or empty");
        }

        String trimmed = phoneNumber.trim();
        if (trimmed.length() < 10 || trimmed.length() > 15) {
            throw new IllegalArgumentException("Phone number length must be between 10 and 15 characters");
        }

        // Базовая валидация: только цифры, +, -, пробелы и скобки
        if (!trimmed.matches("^[+]?[0-9\\s\\-()]+$")) {
            throw new IllegalArgumentException("Phone number contains invalid characters");
        }
    }
}