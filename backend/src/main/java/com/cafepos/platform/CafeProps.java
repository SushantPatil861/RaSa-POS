package com.cafepos.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cafe")
public record CafeProps(long businessId, String timezone, Security security, Bootstrap bootstrap, Cors cors) {

    public record Security(String jwtSecret, int accessTokenMinutes, int refreshTokenDays, boolean cookieSecure) {}

    public record Bootstrap(String ownerName, String ownerEmail, String ownerPassword) {}

    public record Cors(String allowedOrigins) {}
}
