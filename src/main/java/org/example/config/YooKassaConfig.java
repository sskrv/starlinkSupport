package org.example.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "yookassa")
public class YooKassaConfig {

    @NotBlank(message = "ShopId must be provided!")
    private String shopID;

    @NotBlank(message = "SecretKey must be provided!")
    private String secretKey;

    private String returnUrl;
}
