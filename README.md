# 🤖 Telegram Support Bot для Starlink

> Современный, масштабируемый Telegram-бот для автоматизации поддержки клиентов с интеграцией платежной системы ЮKassa и управлением подписками.

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.6-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## 📋 О проекте

Telegram-бот, разработанный для автоматизации взаимодействия с клиентами. Система обеспечивает:
- Обработку пользовательских запросов в режиме реального времени
- Безопасный прием платежей через ЮKassa API
- Управление подписками с автоматическим продлением
- Двустороннюю коммуникацию администратора с пользователями

---

## ✨ Ключевые особенности

### 🎯 Архитектурные преимущества

- **Современный стек технологий** - Spring Boot 3.5.6 + Java 17
- **Чистая архитектура** - разделение слоев: конфигурация, бизнес-логика, персистентность
- **Отказоустойчивость** - автоматические повторные попытки с экспоненциальной задержкой (`@Retryable`)
- **Безопасность** - валидация данных, маскирование чувствительной информации в логах
- **Соответствие best practices** - константы вместо magic strings, внедрение зависимостей через конструктор

### 🚀 Функциональность

#### Для пользователей:
- 📱 Интуитивное меню с inline-кнопками
- 💳 Интеграция с ЮKassa для безопасных платежей
- 📞 Контекстный сбор контактной информации
- ⏱️ Автоматическое управление подписками
- 💬 Прямая связь с поддержкой

#### Для администраторов:
- 🔔 Мгновенные уведомления о действиях пользователей
- ✉️ Режим ответа с контекстом пользователя
- 📊 Информация о статусе пользователя (ID, username, телефон)
- 🎛️ Гибкое управление тарифами через enum

---

## 🛠️ Технологический стек

### Backend
- **Java 17** - современная LTS-версия с улучшенной производительностью
- **Spring Boot 3.5.6** - enterprise-grade фреймворк
- **Spring Data JPA** - абстракция над работой с БД
- **Spring Retry** - декларативный retry механизм
- **Hibernate 6.4.4** - ORM с поддержкой SQLite через community dialects

### Интеграции
- **Telegram Bot API** (telegrambots-spring-boot-starter 6.9.7.1)
- **ЮKassa REST API** - платежный шлюз
- **RestTemplate** - HTTP-клиент для внешних API
- **Jackson** - JSON сериализация/десериализация

### База данных
- **SQLite** - легковесная встраиваемая БД
- **Hibernate Community Dialects** - расширенная поддержка диалектов

### Инструменты разработки
- **Lombok** - уменьшение boilerplate кода
- **Jakarta Validation** - декларативная валидация
- **SLF4J + Logback** - структурированное логирование
- **Maven** - управление зависимостями и сборка

### DevOps
- **Docker** - контейнеризация приложения
- **Multi-stage build** - оптимизация размера образа

---

## 🏗️ Архитектура проекта

```
src/main/java/org/example/
├── config/                    # Конфигурация (BotConfig, YooKassaConfig)
├── db/                        # Data Access Layer (Entity, Repository)
├── logic/                     # Business Logic Layer (BotLogic, Tariff)
├── yookassa/                  # External Integration (YooKassaPayment)
└── App.java                   # Application Entry Point

src/main/resources/
└── application.properties     # Централизованная конфигурация
```

### Паттерны проектирования
- **Dependency Injection** - через конструкторы для лучшей тестируемости
- **Strategy Pattern** - в обработке различных типов callback'ов
- **Repository Pattern** - абстракция доступа к данным
- **Builder Pattern** - создание сложных объектов (например, keyboard markup)

---

## 📊 Преимущества реализации

### 1. **Производительность и надежность**
```java
@Retryable(
    retryFor = {ResourceAccessException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 1000, multiplier = 2)
)
public PaymentStatus checkPaymentStatus(String paymentId) { ... }
```
- Автоматический retry при сетевых ошибках
- Экспоненциальная задержка (1s → 2s → 4s)
- Graceful degradation

### 2. **Читаемость и поддерживаемость**
```java
// Вместо magic strings
private static final String CALLBACK_REPLY_TO = "reply_to:";
private static final String CALLBACK_CHECK_PAYMENT = "check_payment:";

// Использование в коде
if (callbackData.startsWith(CALLBACK_REPLY_TO)) {
    handleAdminReplyCallback(userId, callbackData);
}
```

### 3. **Безопасность**
```java
// Маскирование чувствительных данных в логах
private String maskSensitiveData(String data) {
    return data.substring(0, 4) + "***" + data.substring(data.length() - 4);
}

// Валидация входных данных
@NotNull @Positive BigDecimal amount
@NotBlank String description
```

### 4. **Гибкость конфигурации**
```java
@ConfigurationProperties(prefix = "bot")
public class BotConfig {
    @NotBlank private String token;
    @NotBlank private String username;
    @NotBlank private String adminId;
}
```
- Внешняя конфигурация через `application.properties`
- Валидация на старте приложения
- Простота изменения без перекомпиляции

---

## 🚀 Быстрый старт

### Предварительные требования
- Java 17+
- Maven 3.8+
- Docker (опционально)

### Установка

1. **Клонируйте репозиторий**
```bash
git clone https://github.com/yourusername/starlink-bot.git
cd starlink-bot
```

2. **Настройте конфигурацию**

Отредактируйте `src/main/resources/application.properties`:
```properties
# Telegram Bot
bot.token=YOUR_BOT_TOKEN
bot.username=YOUR_BOT_USERNAME
bot.admin-id=YOUR_TELEGRAM_ID

# YooKassa
yookassa.shop-id=YOUR_SHOP_ID
yookassa.secret-key=YOUR_SECRET_KEY
yookassa.return-url=https://yoursite.com/return
```

3. **Соберите проект**
```bash
mvn clean package
```

4. **Запустите приложение**
```bash
java -jar target/StarlinkBot-1.0-SNAPSHOT.jar
```

### 🐳 Запуск через Docker

```bash
# Сборка образа
docker build -t starlink-bot .

# Запуск контейнера
docker run -d \
  --name starlink-bot \
  -v $(pwd)/users.db:/app/users.db \
  -v $(pwd)/logs:/app/logs \
  starlink-bot
```

---

## 📝 Структура базы данных

```sql
CREATE TABLE users (
    id BIGINT PRIMARY KEY,                    -- Telegram Chat ID
    user_phone VARCHAR(255),                  -- Номер телефона
    subscription_expiry_date TIMESTAMP        -- Дата окончания подписки
);
```

---

## 🔧 Расширение функциональности

### Добавление нового тарифа

```java
public enum Tariff {
    NEW_TARIFF("Название", new BigDecimal("1000.00"), "tariff_new"),
    // ...
}
```

### Добавление новой команды бота

```java
private static final String CALLBACK_NEW_FEATURE = "new_feature";

// В handleCallbackQuery
case CALLBACK_NEW_FEATURE -> handleNewFeature(chatId, username);
```

---

## 📈 Метрики и мониторинг

Логирование настроено с использованием SLF4J:
- **Console logs** - для разработки
- **File logs** (`logs/bot.log`) - для production
- **Structured logging** - с временем, уровнем, классом

```
[2025-10-07 14:32:15] [INFO] [o.e.logic.BotLogic] - BotLogic initialized
[2025-10-07 14:32:16] [INFO] [o.e.yookassa.YooKassaPayment] - Payment created successfully
```

---

## 🤝 Вклад в проект

Приветствуются Pull Request'ы! Для крупных изменений сначала откройте issue для обсуждения.

### Процесс разработки:
1. Fork проекта
2. Создайте feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit изменения (`git commit -m 'Add some AmazingFeature'`)
4. Push в branch (`git push origin feature/AmazingFeature`)
5. Откройте Pull Request

---

## 📄 Лицензия

Распространяется под лицензией MIT. См. `LICENSE` для дополнительной информации.

---

## 👤 Автор

**Ваше имя**
- GitHub: [@sskrv](https://github.com/sskrv)
- LinkedIn: [Suleiman Askerov](www.linkedin.com/in/suleiman-askerov-6a432933b)
- Email: askerovsul18@gmail.com

---

<div align="center">
Made with ❤️ and ☕
</div>
