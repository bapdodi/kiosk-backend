package com.example.demo.config;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadDir = Paths.get("uploads").toAbsolutePath().normalize();

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadDir.toUri().toString() + "/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .resourceChain(true);
    }
}
