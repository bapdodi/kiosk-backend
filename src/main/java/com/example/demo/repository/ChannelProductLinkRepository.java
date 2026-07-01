package com.example.demo.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.ChannelProductLink;

public interface ChannelProductLinkRepository extends JpaRepository<ChannelProductLink, Long> {

    Optional<ChannelProductLink> findByProductIdAndChannel(Long productId, String channel);

    List<ChannelProductLink> findByChannelAndProductIdIn(String channel, Collection<Long> productIds);

    List<ChannelProductLink> findByChannel(String channel);

    /** 특정 상품의 모든 채널 링크(삭제 시 전 채널 판매중지에 사용). */
    List<ChannelProductLink> findByProductId(Long productId);
}
