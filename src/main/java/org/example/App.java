package org.example;


import org.example.logic.BotLogic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.retry.annotation.EnableRetry;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@SpringBootApplication
@EnableRetry
public class App {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        logger.info("Starting Bot...");

        ConfigurableApplicationContext context = SpringApplication.run(App.class, args);

        try {
            BotLogic botLogic = context.getBean(BotLogic.class);

            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(botLogic);

            logger.info("Bot successfully started!");
        } catch (TelegramApiException e) {
            logger.error("Failed to start Bot: {}", e.getMessage());
            context.close();
        }
    }
}