package org.example.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "bot")
@Getter
@Setter
public class BotConfig {

    @NotBlank(message = "Bot toker must be provided!")
    private String token;

    @NotBlank(message = "Bot username must be provided!")
    private String username;

    private String nickname;

    @NotBlank(message = "Admin ID must be provided!")
    private String adminId;
}