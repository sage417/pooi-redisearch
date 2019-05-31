package app.pooi.redissearch.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "redis.search")
public class RedisSearchConfiguration {
    
    private String prefix;
}