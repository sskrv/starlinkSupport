package org.example.yookassa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.YooKassaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Сервис для работы с платежным API ЮKassa.
 * Предоставляет методы для создания платежей, проверки статуса и отмены платежей.
 */
@Service
public class YooKassaPayment {
    private static final Logger logger = LoggerFactory.getLogger(YooKassaPayment.class);

    private static final String YOOKASSA_API_URL = "https://api.yookassa.ru/v3/payments";
    private static final String CURRENCY_RUB = "RUB";
    private static final String CONFIRMATION_TYPE_REDIRECT = "redirect";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_CANCELED = "canceled";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_WAITING_FOR_CAPTURE = "waiting_for_capture";

    private static final BigDecimal MAX_PAYMENT_AMOUNT = new BigDecimal("15000000.00");
    private static final BigDecimal MIN_PAYMENT_AMOUNT = new BigDecimal("1.00");

    private final YooKassaConfig yooKassaConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public YooKassaPayment(YooKassaConfig yooKassaConfig) {
        this.yooKassaConfig = yooKassaConfig;
        this.restTemplate = createConfiguredRestTemplate();
        this.objectMapper = new ObjectMapper();
        logger.info("YooKassaPayment initialized with shopID: {}",
                maskSensitiveData(yooKassaConfig.getShopID()));
    }

    /**
     * Создает платеж и возвращает URL для подтверждения оплаты.
     *
     * @param amount      сумма к оплате (должна быть положительной)
     * @param description описание платежа (не должно быть пустым)
     * @return URL для завершения оплаты
     * @throws YooKassaException при ошибках API или валидации
     */
    public String createPayment(@NotNull @Positive BigDecimal amount, @NotBlank String description) {
        validatePaymentAmount(amount);
        validateDescription(description);

        logger.info("Creating payment for amount: {} RUB, description length: {} chars",
                amount, description.length());

        String idempotenceKey = generateIdempotenceKey();

        try {
            Map<String, Object> requestPayload = buildCreatePaymentRequest(amount, description);
            HttpEntity<Map<String, Object>> requestEntity = createRequestEntity(requestPayload, idempotenceKey);

            ResponseEntity<String> response = executeRequest(YOOKASSA_API_URL, HttpMethod.POST, requestEntity);
            return parseCreatePaymentResponse(response.getBody());

        } catch (Exception e) {
            logger.error("Failed to create payment for amount: {}", amount, e);
            throw new YooKassaException("Failed to create payment", e);
        }
    }

    /**
     * Проверяет статус платежа по его идентификатору с повторными попытками.
     *
     * @param paymentId идентификатор платежа (не должен быть пустым)
     * @return статус платежа
     * @throws YooKassaException при ошибках API или валидации
     */
    public PaymentStatus checkPaymentStatus(@NotBlank String paymentId) {
        validatePaymentId(paymentId);

        logger.info("Checking status for payment ID: {}", maskSensitiveData(paymentId));

        int maxAttempts = 3;
        long delay = 1000; // 1 секунда

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpEntity<?> requestEntity = createRequestEntity(null, null);
                String url = YOOKASSA_API_URL + "/" + paymentId;

                ResponseEntity<String> response = executeRequest(url, HttpMethod.GET, requestEntity);
                return parsePaymentStatusResponse(response.getBody(), paymentId);

            } catch (ResourceAccessException e) {
                logger.warn("Network error on attempt {} of {}: {}", attempt, maxAttempts, e.getMessage());
                if (attempt == maxAttempts) {
                    logger.error("All retry attempts failed for payment status check", e);
                    throw new YooKassaException("Failed to check payment status after " + maxAttempts + " attempts", e);
                }
                try {
                    Thread.sleep(delay * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new YooKassaException("Interrupted during retry", ie);
                }
            } catch (Exception e) {
                logger.error("Failed to check payment status for ID: {}", maskSensitiveData(paymentId), e);
                throw new YooKassaException("Failed to check payment status", e);
            }
        }

        throw new YooKassaException("Unexpected end of retry loop");
    }

    /**
     * Отменяет платеж по его идентификатору.
     *
     * @param paymentId идентификатор платежа для отмены
     * @return результат операции отмены
     * @throws YooKassaException при ошибках API или валидации
     */
    public PaymentCancellationResult cancelPayment(@NotBlank String paymentId) {
        validatePaymentId(paymentId);

        logger.info("Attempting to cancel payment with ID: {}", maskSensitiveData(paymentId));

        String idempotenceKey = generateIdempotenceKey();

        try {
            HttpEntity<String> requestEntity = createRequestEntity(null, idempotenceKey);
            String cancelUrl = YOOKASSA_API_URL + "/" + paymentId + "/cancel";

            ResponseEntity<String> response = executeRequest(cancelUrl, HttpMethod.POST, requestEntity);
            return parseCancellationResponse(response.getBody(), paymentId);

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY) {
                logger.warn("Payment {} cannot be canceled in current state", maskSensitiveData(paymentId));
                return PaymentCancellationResult.failure("Payment cannot be canceled in current state");
            }
            throw new YooKassaException("Failed to cancel payment", e);
        } catch (Exception e) {
            logger.error("Failed to cancel payment with ID: {}", maskSensitiveData(paymentId), e);
            throw new YooKassaException("Failed to cancel payment", e);
        }
    }


    private Map<String, Object> buildCreatePaymentRequest(BigDecimal amount, String description) {
        Map<String, Object> requestMap = new HashMap<>();

        Map<String, Object> amountMap = new HashMap<>();
        amountMap.put("value", formatAmount(amount));
        amountMap.put("currency", CURRENCY_RUB);

        Map<String, Object> confirmationMap = new HashMap<>();
        confirmationMap.put("type", CONFIRMATION_TYPE_REDIRECT);
        confirmationMap.put("return_url", yooKassaConfig.getReturnUrl());

        requestMap.put("amount", amountMap);
        requestMap.put("capture", true);
        requestMap.put("confirmation", confirmationMap);
        requestMap.put("description", sanitizeDescription(description));

        return requestMap;
    }

    private <T> HttpEntity<T> createRequestEntity(T body, String idempotenceKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", createAuthHeader());

        if (idempotenceKey != null) {
            headers.set("Idempotence-Key", idempotenceKey);
        }

        return new HttpEntity<>(body, headers);
    }

    private String createAuthHeader() {
        String auth = yooKassaConfig.getShopID() + ":" + yooKassaConfig.getSecretKey();
        byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes(StandardCharsets.UTF_8));
        return "Basic " + new String(encodedAuth, StandardCharsets.UTF_8);
    }

    private ResponseEntity<String> executeRequest(String url, HttpMethod method, HttpEntity<?> requestEntity) {
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, method, requestEntity, String.class);

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new YooKassaException("API returned error status: " + response.getStatusCode());
            }

            return response;

        } catch (HttpClientErrorException | HttpServerErrorException e) {
            logger.error("HTTP error during YooKassa API call: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new YooKassaException("YooKassa API error: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            logger.error("Network error during YooKassa API call", e);
            throw new YooKassaException("Network error calling YooKassa API", e);
        }
    }


    private String parseCreatePaymentResponse(String responseBody) throws JsonProcessingException {
        JsonNode rootNode = objectMapper.readTree(responseBody);

        String confirmationUrl = rootNode.path("confirmation").path("confirmation_url").asText();
        String paymentId = rootNode.path("id").asText();
        String status = rootNode.path("status").asText();

        if (confirmationUrl.isEmpty()) {
            throw new YooKassaException("Confirmation URL not found in response");
        }

        logger.info("Payment created successfully. ID: {}, Status: {}",
                maskSensitiveData(paymentId), status);

        return confirmationUrl;
    }

    private PaymentStatus parsePaymentStatusResponse(String responseBody, String paymentId) throws JsonProcessingException {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        String status = rootNode.path("status").asText();

        if (status.isEmpty()) {
            throw new YooKassaException("Status not found in response");
        }

        logger.info("Payment status retrieved. ID: {}, Status: {}",
                maskSensitiveData(paymentId), status);

        return PaymentStatus.fromString(status);
    }

    private PaymentCancellationResult parseCancellationResponse(String responseBody, String paymentId) throws JsonProcessingException {
        JsonNode rootNode = objectMapper.readTree(responseBody);
        String status = rootNode.path("status").asText();

        if (STATUS_CANCELED.equals(status)) {
            logger.info("Payment {} successfully canceled", maskSensitiveData(paymentId));
            return PaymentCancellationResult.success();
        } else {
            logger.warn("Payment {} cancellation failed. Status: {}", maskSensitiveData(paymentId), status);
            return PaymentCancellationResult.failure("Unexpected status: " + status);
        }
    }

    private void validatePaymentAmount(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Payment amount cannot be null");
        }
        if (amount.compareTo(MIN_PAYMENT_AMOUNT) < 0) {
            throw new IllegalArgumentException("Payment amount cannot be less than " + MIN_PAYMENT_AMOUNT + " RUB");
        }
        if (amount.compareTo(MAX_PAYMENT_AMOUNT) > 0) {
            throw new IllegalArgumentException("Payment amount cannot be greater than " + MAX_PAYMENT_AMOUNT + " RUB");
        }
    }

    private void validateDescription(String description) {
        if (description == null || description.trim().isEmpty()) {
            throw new IllegalArgumentException("Payment description cannot be null or empty");
        }
        if (description.length() > 128) {
            throw new IllegalArgumentException("Payment description cannot be longer than 128 characters");
        }
    }

    private void validatePaymentId(String paymentId) {
        if (paymentId == null || paymentId.trim().isEmpty()) {
            throw new IllegalArgumentException("Payment ID cannot be null or empty");
        }
        // ЮKassa использует UUID v4 формат для ID платежей
        // Формат: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx где x - любая hex цифра, y - 8,9,a,b
        if (!paymentId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new IllegalArgumentException("Invalid payment ID format");
        }
    }

    private String generateIdempotenceKey() {
        return UUID.randomUUID().toString();
    }

    private String formatAmount(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toString();
    }

    private String sanitizeDescription(String description) {
        return description.trim().replaceAll("[\\r\\n\\t]", " ");
    }

    private String maskSensitiveData(String data) {
        if (data == null || data.length() <= 8) {
            return "***";
        }
        return data.substring(0, 4) + "***" + data.substring(data.length() - 4);
    }

    private RestTemplate createConfiguredRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        return restTemplate;
    }

    public enum PaymentStatus {
        PENDING("pending"),
        WAITING_FOR_CAPTURE("waiting_for_capture"),
        SUCCEEDED("succeeded"),
        CANCELED("canceled"),
        UNKNOWN("unknown");

        private final String value;

        PaymentStatus(String value) {
            this.value = value;
        }

        public static PaymentStatus fromString(String status) {
            for (PaymentStatus ps : PaymentStatus.values()) {
                if (ps.value.equals(status)) {
                    return ps;
                }
            }
            logger.warn("Unknown payment status received: {}", status);
            return UNKNOWN;
        }

        public String getValue() {
            return value;
        }
    }

    public static class PaymentCancellationResult {
        private final boolean success;
        private final String message;

        private PaymentCancellationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static PaymentCancellationResult success() {
            return new PaymentCancellationResult(true, "Payment canceled successfully");
        }

        public static PaymentCancellationResult failure(String message) {
            return new PaymentCancellationResult(false, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    public static class YooKassaException extends RuntimeException {
        public YooKassaException(String message) {
            super(message);
        }

        public YooKassaException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}