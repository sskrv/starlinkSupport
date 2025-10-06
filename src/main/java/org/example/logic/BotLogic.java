package org.example.logic;

import org.example.config.BotConfig;
import org.example.db.User;
import org.example.db.UserRepository;
import org.example.yookassa.YooKassaPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BotLogic extends TelegramLongPollingBot {
    private static final Logger logger = LoggerFactory.getLogger(BotLogic.class);

    private final BotConfig botConfig;
    private final YooKassaPayment yooKassaPayment;
    private final UserRepository userRepository;

    private record ReplyTarget(long chatId, String username) {}
    private final Map<Long, ReplyTarget> adminReplyTarget = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> waitingForCustomQuestion = new ConcurrentHashMap<>();

    public BotLogic(BotConfig botConfig, UserRepository userRepository, YooKassaPayment yooKassaPayment) {
        super(botConfig.getToken());
        this.botConfig = botConfig;
        this.userRepository = userRepository;
        this.yooKassaPayment = yooKassaPayment;
        logger.info("BotLogic initialized");
    }

    @Override
    public String getBotUsername() {
        return botConfig.getUsername();
    }

    private boolean isAdmin(long chatId) {
        return String.valueOf(chatId).equals(botConfig.getAdminId());
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                Message message = update.getMessage();
                if (message.hasText()) {
                    handleTextMessage(message);
                } else if (message.hasContact() && !isAdmin(message.getChatId())) {
                    handleUserContactMessage(message);
                }
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update);
            }
        } catch (Exception e) {
            logger.error("Error processing update: " + update.toString(), e);
        }
    }

    private void handleTextMessage(Message message) throws TelegramApiException {
        if (isAdmin(message.getChatId())) {
            handleAdminMessage(message);
        } else {
            handleUserMessage(message);
        }
    }

    private void handleAdminMessage(Message message) throws TelegramApiException {
        long adminId = message.getChatId();
        String text = message.getText();

        if (adminReplyTarget.containsKey(adminId)) {
            if ("/stop".equals(text)) {
                adminReplyTarget.remove(adminId);
                sendMessage(adminId, "✅ Вы вышли из режима ответа.");
            } else {
                ReplyTarget target = adminReplyTarget.get(adminId);
                sendMessage(target.chatId(), "Сообщение от поддержки:\n\n" + text);

                String userDisplay = target.username() != null && !target.username().isEmpty()
                        ? "@" + target.username()
                        : "ID: " + target.chatId();

                sendMessage(adminId, "↪️ Сообщение отправлено пользователю " + userDisplay);
            }
            return;
        }

        if ("/start".equals(text)) {
            sendMessage(adminId, "Добро пожаловать, Администратор!");
        } else {
            sendMessage(adminId, "Для ответа пользователю, нажмите кнопку '✍️ Ответить' под его сообщением.");
        }
    }

    private void handleUserMessage(Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        String text = message.getText();

        if (waitingForCustomQuestion.getOrDefault(chatId, false)) {
            if ("/start".equals(text)) {
                waitingForCustomQuestion.remove(chatId);
                showMainMenu(chatId, "Добро пожаловать! По какому вопросу обращаетесь?");
                return;
            }

            // Обрабатываем кастомный вопрос
            waitingForCustomQuestion.remove(chatId);
            String username = message.getFrom().getUserName();
            String requestText = String.format("❓ Вопрос от пользователя:\n\n\"%s\"", text);
            handleUserRequest(chatId, username, requestText);
            return;
        }

        if ("/start".equals(text)) {
            showMainMenu(chatId, "Добро пожаловать! По какому вопросу обращаетесь?");
            return;
        }

        String username = message.getFrom().getUserName();
        String requestText = String.format("Сообщение от пользователя:\n\n\"%s\"", text);
        handleUserRequest(chatId, username, requestText);
    }

    private void handleUserContactMessage(Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        String phoneNumber = message.getContact().getPhoneNumber();

        try {
            User user = userRepository.findById(chatId).orElse(new User(chatId));
            user.setPhone(phoneNumber);
            userRepository.save(user);
            sendMessage(chatId, "✅ Спасибо, ваш контакт сохранен!");
        } catch (Exception e) {
            logger.error("Error saving user phone number for user {}: {}", chatId, e.getMessage(), e);
            sendMessage(chatId, "❌ Произошла ошибка при сохранении контакта. Попробуйте еще раз.");
        }
    }

    private void handleCallbackQuery(Update update) throws TelegramApiException {
        String callbackData = update.getCallbackQuery().getData();
        long chatId = update.getCallbackQuery().getMessage().getChatId();
        long userId = update.getCallbackQuery().getFrom().getId();
        String username = update.getCallbackQuery().getFrom().getUserName();
        int messageId = update.getCallbackQuery().getMessage().getMessageId();

        if (isAdmin(userId) && callbackData.startsWith("reply_to:")) {
            String[] parts = callbackData.split(":", 3);
            long targetChatId = Long.parseLong(parts[1]);
            String targetUsername = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;

            adminReplyTarget.put(userId, new ReplyTarget(targetChatId, targetUsername));

            // Формируем читаемое имя пользователя
            String userDisplay = targetUsername != null
                    ? "@" + targetUsername
                    : "ID: " + targetChatId;

            sendMessage(userId, "✅ Вы вошли в режим ответа пользователю " + userDisplay + ".\n" +
                    "Все следующие сообщения будут отправлены ему.\n" +
                    "Для выхода из режима отправьте /stop.");

            return;
        }

        if (callbackData.startsWith("check_payment:")) {
            String[] parts = callbackData.split(":");
            if (parts.length == 3) {
                String tariffCallback = parts[1];
                String paymentId = parts[2];
                Tariff purchasedTariff = Tariff.fromCallbackData(tariffCallback);
                checkPaymentStatus(chatId, paymentId, username, purchasedTariff, messageId);
            } else {
                logger.warn("Received malformed check_payment callback: {}", callbackData);
                sendMessage(chatId, "Произошла ошибка при проверке платежа. Пожалуйста, попробуйте снова.");
            }
            return;
        }

        if (callbackData.startsWith("skip_phone_and_send:")) {
            RequestContext context = pendingRequests.remove(chatId);
            if (context != null) {
                forwardUserActionToAdmin(chatId, context.username, context.requestText);
                sendMessage(chatId, "Ваш запрос отправлен администратору без контактного номера. " +
                        "Ответ будет отправлен в этом чате.");
            } else {
                sendMessage(chatId, "Сессия истекла. Пожалуйста, повторите ваш запрос.");
            }
            return;
        }

        if (callbackData.equals("share_phone")) {
            requestPhoneNumber(chatId, "📱 Поделитесь своим номером телефона для связи с поддержкой:");
            return;
        }

        Tariff selectedTariff = Tariff.fromCallbackData(callbackData);
        if (selectedTariff != null) {
            initiatePayment(chatId, selectedTariff);
            return;
        }

        switch (callbackData) {
            case "buy_device", "activate_device" -> {
                String actionText = "buy_device".equals(callbackData) ? "Приобретение устройства" : "Активация устройства";
                String requestText = String.format("❗️ Пользователь нажал на кнопку '%s'", actionText);
                handleUserRequest(chatId, username, requestText);
            }
            case "buy_subscription" -> showTariffOptions(chatId);
            case "other_question" -> {
                waitingForCustomQuestion.put(chatId, true);
                sendMessage(chatId, "📝 Введите свой вопрос и он будет отправлен администратору:");
            }
        }
    }

    private void handleUserRequest(long chatId, String username, String requestText) throws TelegramApiException {
        Optional<User> userOpt = userRepository.findById(chatId);

        boolean hasPhone = userOpt.isPresent() &&
                userOpt.get().getPhone() != null &&
                !userOpt.get().getPhone().trim().isEmpty();

        if (!hasPhone) {
            showPhoneRequestOptions(chatId, requestText, username);
        } else {
            forwardUserActionToAdmin(chatId, username, requestText);
            sendMessage(chatId, "Ваш запрос отправлен администратору. Скоро с вами свяжутся.");
        }
    }

    private void showPhoneRequestOptions(long chatId, String requestText, String username) throws TelegramApiException {
        SendMessage message = new SendMessage(String.valueOf(chatId),
                "💡 Для более быстрой связи с поддержкой рекомендуем поделиться номером телефона, " +
                        "но это необязательно. Выберите один из вариантов:");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(createButton("📱 Поделиться номером", "share_phone")));
        rows.add(List.of(createButton("⏭️ Продолжить без номера", "skip_phone_and_send:" +
                System.currentTimeMillis())));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(rows);
        message.setReplyMarkup(markup);
        execute(message);

        saveRequestContext(chatId, requestText, username);
    }

    private final Map<Long, RequestContext> pendingRequests = new ConcurrentHashMap<>();

    private static class RequestContext {
        final String requestText;
        final String username;
        final long timestamp;

        RequestContext(String requestText, String username) {
            this.requestText = requestText;
            this.username = username;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private void saveRequestContext(long chatId, String requestText, String username) {
        pendingRequests.put(chatId, new RequestContext(requestText, username));

        long cutoff = System.currentTimeMillis() - 600000; // 10 минут
        pendingRequests.entrySet().removeIf(entry -> entry.getValue().timestamp < cutoff);
    }


    private void forwardUserActionToAdmin(long userId, String username, String requestText) {
        String phone = userRepository.findById(userId)
                .map(User::getPhone)
                .filter(p -> p != null && !p.trim().isEmpty())
                .orElse("не указан");

        String adminMessage = String.format(
                "Входящий запрос от @%s (ID: %d, Тел: %s):\n\n%s",
                username != null ? username : "неизвестно", userId, phone, requestText
        );

        String callbackData = "reply_to:" + userId + ":" + (username != null ? username : "");

        InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup(
                List.of(List.of(createButton("✍️ Ответить пользователю", callbackData)))
        );
        sendToAdmin(adminMessage, keyboard);
    }

    private void showMainMenu(long chatId, String text) throws TelegramApiException {
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(List.of(
                createButton("Приобретение устройства", "buy_device"),
                createButton("Активация устройства", "activate_device")
        ));
        rows.add(List.of(createButton("Покупка подписки", "buy_subscription")));
        rows.add(List.of(createButton("❓ Другой вопрос", "other_question")));

        markup.setKeyboard(rows);
        message.setReplyMarkup(markup);
        execute(message);
    }

    private void showTariffOptions(long chatId) throws TelegramApiException {
        SendMessage message = new SendMessage(String.valueOf(chatId), "Выберите тарифный план:");

        List<List<InlineKeyboardButton>> tariffButtons = new ArrayList<>();
        for (Tariff tariff : Tariff.values()) {
            String buttonText = String.format("%s - %.2f ₽", tariff.getDisplayName(), tariff.getPrice());
            tariffButtons.add(List.of(createButton(buttonText, tariff.getCallbackData())));
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(tariffButtons);
        message.setReplyMarkup(markup);
        execute(message);
    }

    private void initiatePayment(long chatId, Tariff tariff) throws TelegramApiException {
        sendMessage(chatId, "Создаем ссылку на оплату, пожалуйста, подождите...");
        try {
            String description = String.format("Покупка тарифа: '%s' для пользователя %d", tariff.getDisplayName(), chatId);
            String confirmationUrl = yooKassaPayment.createPayment(tariff.getPrice(), description);

            String paymentId = confirmationUrl.substring(confirmationUrl.lastIndexOf("=") + 1);
            String checkCallbackData = String.format("check_payment:%s:%s", tariff.getCallbackData(), paymentId);

            InlineKeyboardMarkup keyboard = new InlineKeyboardMarkup(List.of(
                    List.of(createUrlButton("🔗 Перейти к оплате", confirmationUrl)),
                    List.of(createButton("✅ Я оплатил(а)", checkCallbackData))
            ));

            SendMessage message = new SendMessage(String.valueOf(chatId),
                    "Ваша ссылка на оплату готова. После успешной оплаты нажмите кнопку 'Я оплатил(а)'.");
            message.setReplyMarkup(keyboard);
            execute(message);

        } catch (Exception e) {
            logger.error("Error initiating payment for user {}: {}", chatId, e.getMessage(), e);
            sendMessage(chatId, "❌ Произошла ошибка при создании платежа. Попробуйте позже.");
        }
    }

    private void checkPaymentStatus(long chatId, String paymentId, String username, Tariff purchasedTariff, int messageId) throws TelegramApiException {
        if (purchasedTariff == null) {
            sendMessage(chatId, "Не удалось определить оплаченный тариф. Обратитесь в поддержку.");
            return;
        }

        try {
            YooKassaPayment.PaymentStatus status = yooKassaPayment.checkPaymentStatus(paymentId);
            switch (status) {
                case SUCCEEDED -> {
                    try {
                        User user = userRepository.findById(chatId).orElse(new User(chatId));

                        final int SUBSCRIPTION_DAYS = 30;
                        LocalDateTime currentExpiry = user.getSubscriptionExpiryDate();
                        LocalDateTime newExpiry;

                        if (currentExpiry != null && currentExpiry.isAfter(LocalDateTime.now())) {
                            newExpiry = currentExpiry.plusDays(SUBSCRIPTION_DAYS);
                        } else {
                            newExpiry = LocalDateTime.now().plusDays(SUBSCRIPTION_DAYS);
                        }

                        user.setSubscriptionExpiryDate(newExpiry);

                        userRepository.save(user);
                        sendMessage(chatId, "✅ Оплата прошла успешно! Ваша подписка активна.");

                        String adminNotification = String.format(
                                "✅ Пользователь успешно оплатил тариф: '%s'",
                                purchasedTariff.getDisplayName()
                        );
                        forwardUserActionToAdmin(chatId, username, adminNotification);

                        removeInlineKeyboard(chatId, messageId);
                    } catch (Exception e) {
                        logger.error("Error updating subscription for user {}: {}", chatId, e.getMessage(), e);
                        sendMessage(chatId, "✅ Оплата прошла успешно, но произошла ошибка при активации подписки. Обратитесь в поддержку.");
                    }
                }
                case PENDING -> sendMessage(chatId, "⏳ Платеж еще не подтвержден. Пожалуйста, завершите оплату и попробуйте снова через минуту.");
                case WAITING_FOR_CAPTURE -> sendMessage(chatId, "⏳ Платеж ожидает подтверждения. Попробуйте проверить через несколько минут.");
                case CANCELED -> sendMessage(chatId, "❌ Платеж был отменен.");
                case UNKNOWN -> {
                    logger.warn("Unknown payment status for payment ID: {}", paymentId);
                    sendMessage(chatId, "❌ Получен неизвестный статус платежа. Обратитесь в поддержку.");
                }
            }
        } catch (YooKassaPayment.YooKassaException e) {
            logger.error("YooKassa API error checking payment status for user {}: {}", chatId, e.getMessage(), e);
            sendMessage(chatId, "❌ Произошла ошибка при обращении к платежной системе. Попробуйте позже.");
        } catch (Exception e) {
            logger.error("Unexpected error checking payment status for user {}: {}", chatId, e.getMessage(), e);
            sendMessage(chatId, "❌ Произошла неожиданная ошибка при проверке статуса платежа. Попробуйте позже.");
        }
    }

    private void requestPhoneNumber(long chatId, String text) throws TelegramApiException {
        SendMessage message = new SendMessage(String.valueOf(chatId), text);
        ReplyKeyboardMarkup replyKeyboardMarkup = new ReplyKeyboardMarkup();
        replyKeyboardMarkup.setSelective(true);
        replyKeyboardMarkup.setResizeKeyboard(true);
        replyKeyboardMarkup.setOneTimeKeyboard(true);

        List<KeyboardRow> keyboard = new ArrayList<>();
        KeyboardRow row = new KeyboardRow();
        KeyboardButton button = new KeyboardButton("📱 Поделиться номером телефона");
        button.setRequestContact(true);
        row.add(button);
        keyboard.add(row);
        replyKeyboardMarkup.setKeyboard(keyboard);
        message.setReplyMarkup(replyKeyboardMarkup);
        execute(message);
    }

    private InlineKeyboardButton createButton(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }

    private InlineKeyboardButton createUrlButton(String text, String url) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setUrl(url);
        return button;
    }

    private void removeInlineKeyboard(long chatId, int messageId) {
        EditMessageReplyMarkup editMarkup = new EditMessageReplyMarkup();
        editMarkup.setChatId(String.valueOf(chatId));
        editMarkup.setMessageId(messageId);
        editMarkup.setReplyMarkup(new InlineKeyboardMarkup(Collections.emptyList()));
        try {
            execute(editMarkup);
        } catch (TelegramApiException e) {
            logger.error("Failed to remove inline keyboard for message {}: {}", messageId, e.getMessage());
        }
    }

    private void sendToAdmin(String text, InlineKeyboardMarkup keyboard) {
        if (botConfig.getAdminId() != null && !botConfig.getAdminId().isEmpty()) {
            try {
                long adminId = Long.parseLong(botConfig.getAdminId());
                SendMessage message = new SendMessage(String.valueOf(adminId), text);
                if (keyboard != null) {
                    message.setReplyMarkup(keyboard);
                }
                execute(message);
            } catch (NumberFormatException e) {
                logger.error("Invalid admin ID format: {}", botConfig.getAdminId());
            } catch (Exception e) {
                logger.error("Failed to send message to admin: {}", e.getMessage(), e);
            }
        } else {
            logger.warn("Admin ID is not configured.");
        }
    }

    private void sendMessage(long chatId, String text) throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        execute(message);
    }
}