package com.example.demo.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.ChannelCategoryMapping;

public interface ChannelCategoryMappingRepository extends JpaRepository<ChannelCategoryMapping, Long> {

    List<ChannelCategoryMapping> findByChannel(String channel);

    Optional<ChannelCategoryMapping> findByChannelAndKioskMainCategoryAndKioskSubCategory(
            String channel, String kioskMainCategory, String kioskSubCategory);

    Optional<ChannelCategoryMapping> findByChannelAndKioskMainCategoryAndKioskSubCategoryIsNull(
            String channel, String kioskMainCategory);
}
