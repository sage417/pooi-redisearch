package app.pooi.redissearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import app.pooi.redissearch.configuration.RedisSearchConfiguration;

@EnableConfigurationProperties(RedisSearchConfiguration.class)
@SpringBootApplication
public class RedisSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisSearchApplication.class, args);
    }

}
