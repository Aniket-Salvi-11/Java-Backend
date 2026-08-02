package com.closemore.backend.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Activates {@link JwtProperties} binding.
 *
 * <p>A small config class here rather than @ConfigurationPropertiesScan on the application class,
 * so the auth package stays self-contained and the main class keeps its Phase 0 advice-ordering
 * comments as the only thing worth reading in it.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class AuthConfig {
}
