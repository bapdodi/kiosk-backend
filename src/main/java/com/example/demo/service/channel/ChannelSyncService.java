package com.example.demo.service.channel;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.example.demo.entity.ChannelProductLink;
import com.example.demo.entity.Combination;
import com.example.demo.entity.Product;
import com.example.demo.repository.ChannelProductLinkRepository;
import com.example.demo.repository.ProductRepository;
import com.example.demo.service.FileService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * 채널 무관 전송/동기화 오케스트레이션. 상품/이미지 로드·링크(스냅샷) 저장·diff 는 공통으로 처리하고,
 * 채널 고유 작업(등록/수정/판매중지)은 {@link SalesChannelConnector} 구현체에 위임한다.
 *
 * 새 채널 추가 = SalesChannelConnector 구현 빈 하나. 이 서비스는 수정 불필요.
 */
@Service
@Slf4j
public class ChannelSyncService {

    private static final String STATUS_SUSPENSION = "SUSPENSION";
    private static final String STATUS_SALE = "SALE";

    private final ProductRepository productRepository;
    private final ChannelProductLinkRepository linkRepository;
    private final FileService fileService;
    private final ObjectMapper objectMapper;
    private final Map<String, SalesChannelConnector> connectors;

    public ChannelSyncService(ProductRepository productRepository,
            ChannelProductLinkRepository linkRepository,
            FileService fileService,
            ObjectMapper objectMapper,
            List<SalesChannelConnector> connectorBeans) {
        this.productRepository = productRepository;
        this.linkRepository = linkRepository;
        this.fileService = fileService;
        this.objectMapper = objectMapper;
        Map<String, SalesChannelConnector> map = new LinkedHashMap<>();
        for (SalesChannelConnector c : connectorBeans) {
            map.put(c.channel().toUpperCase(), c);
        }
        this.connectors = map;
    }

    private SalesChannelConnector connector(String channel) {
        SalesChannelConnector c = connectors.get(normalize(channel));
        if (c == null) {
            throw new ChannelApiException("지원하지 않는 채널입니다: " + channel);
        }
        return c;
    }

    private String normalize(String channel) {
        return channel == null ? "" : channel.trim().toUpperCase();
    }

    public boolean isConfigured(String channel) {
        return connector(channel).isConfigured();
    }

    public List<ChannelProductLink> getLinks(String channel, Collection<Long> productIds) {
        return linkRepository.findByChannelAndProductIdIn(normalize(channel), productIds);
    }

    // ── 전송(등록/수정) ────────────────────────────────────────────────────────

    public ChannelProductLink push(String channel, Long productId) {
        String ch = normalize(channel);
        SalesChannelConnector conn = connector(ch);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ChannelApiException("상품을 찾을 수 없습니다: " + productId));
        try {
            ChannelProductLink link = linkRepository.findByProductIdAndChannel(productId, ch).orElse(null);
            List<ImagePart> images = buildImageParts(product);
            ConnectorResult result = conn.register(product, images, link != null ? link.getOriginProductNo() : null);
            return saveSnapshot(link, productId, ch, product, result);
        } catch (RuntimeException e) {
            recordError(productId, ch, e.getMessage());
            throw e;
        }
    }

    public List<PushResult> pushBulk(String channel, List<Long> productIds) {
        String ch = normalize(channel);
        List<PushResult> results = new ArrayList<>();
        for (Long id : productIds) {
            String name = productRepository.findById(id).map(Product::getName).orElse("(삭제됨)");
            try {
                push(ch, id);
                results.add(new PushResult(id, name, true, "전송 완료"));
            } catch (RuntimeException e) {
                log.warn("[{}] 전송 실패 productId={}: {}", ch, id, e.getMessage());
                results.add(new PushResult(id, name, false, e.getMessage()));
            }
        }
        return results;
    }

    // ── 동기화(미리보기 → 수락 반영) ────────────────────────────────────────────

    public List<ChangePreview> previewChanges(String channel) {
        String ch = normalize(channel);
        List<ChangePreview> previews = new ArrayList<>();
        for (ChannelProductLink link : linkRepository.findByChannel(ch)) {
            Product product = productRepository.findById(link.getProductId()).orElse(null);
            if (product == null) {
                previews.add(new ChangePreview(link.getProductId(), "(삭제된 상품)", link.getOriginProductNo(),
                        List.of(new FieldChange("상태", "연동됨", "키오스크에서 삭제됨 → 판매중지 권장"))));
                continue;
            }
            List<FieldChange> changes = new ArrayList<>();
            int currentStock = computeEffectiveStock(product);
            if (!nullSafeEquals(link.getLastSyncedName(), product.getName())) {
                changes.add(new FieldChange("상품명", link.getLastSyncedName(), product.getName()));
            }
            if (!nullSafeEquals(link.getLastSyncedPrice(), product.getPriceC())) {
                changes.add(new FieldChange("가격", String.valueOf(link.getLastSyncedPrice()), String.valueOf(product.getPriceC())));
            }
            if (!nullSafeEquals(link.getLastSyncedStock(), currentStock)) {
                changes.add(new FieldChange("재고", String.valueOf(link.getLastSyncedStock()), String.valueOf(currentStock)));
            }
            if (!changes.isEmpty()) {
                previews.add(new ChangePreview(link.getProductId(), product.getName(), link.getOriginProductNo(), changes));
            }
        }
        return previews;
    }

    public List<PushResult> applyChanges(String channel, List<Long> productIds) {
        String ch = normalize(channel);
        SalesChannelConnector conn = connector(ch);
        List<PushResult> results = new ArrayList<>();
        for (Long id : productIds) {
            ChannelProductLink link = linkRepository.findByProductIdAndChannel(id, ch).orElse(null);
            Product product = productRepository.findById(id).orElse(null);
            String name = product != null ? product.getName() : "(삭제됨)";
            try {
                if (link == null || link.getOriginProductNo() == null) {
                    throw new ChannelApiException("아직 이 채널에 등록되지 않은 상품입니다.");
                }
                if (product == null) {
                    conn.suspend(link.getOriginProductNo());
                    link.setNaverStatus(STATUS_SUSPENSION);
                    link.setLastSyncedAt(LocalDateTime.now());
                    link.setLastError(null);
                    linkRepository.save(link);
                    results.add(new PushResult(id, name, true, "판매중지 처리"));
                    continue;
                }
                List<String> reusable = readJsonList(link.getChannelImageUrls());
                ConnectorResult result = reusable.isEmpty()
                        ? conn.register(product, buildImageParts(product), link.getOriginProductNo())
                        : conn.update(product, reusable, link.getOriginProductNo());
                saveSnapshot(link, id, ch, product, result);
                results.add(new PushResult(id, name, true, "반영 완료"));
            } catch (RuntimeException e) {
                log.warn("[{}] 동기화 반영 실패 productId={}: {}", ch, id, e.getMessage());
                recordError(id, ch, e.getMessage());
                results.add(new PushResult(id, name, false, e.getMessage()));
            }
        }
        return results;
    }

    // ── 판매상태 변경(판매중 ↔ 판매중지) ─────────────────────────────────────────

    /**
     * 이미 채널에 등록된 상품의 판매상태를 변경한다. targetStatus 는 SALE(판매중/재판매) 또는 SUSPENSION(판매중지).
     * 성공 시 갱신된 링크를 반환하고, 미등록·미지원 상태·채널 오류는 예외로 알린다.
     */
    public ChannelProductLink changeStatus(String channel, Long productId, String targetStatus) {
        String ch = normalize(channel);
        String status = targetStatus == null ? "" : targetStatus.trim().toUpperCase();
        if (!STATUS_SALE.equals(status) && !STATUS_SUSPENSION.equals(status)) {
            throw new ChannelApiException("지원하지 않는 상태입니다: " + targetStatus);
        }
        SalesChannelConnector conn = connector(ch);
        ChannelProductLink link = linkRepository.findByProductIdAndChannel(productId, ch).orElse(null);
        if (link == null || link.getOriginProductNo() == null) {
            throw new ChannelApiException("아직 이 채널에 등록되지 않은 상품입니다. 먼저 전송해 주세요.");
        }
        try {
            if (STATUS_SUSPENSION.equals(status)) {
                conn.suspend(link.getOriginProductNo());
            } else {
                conn.resume(link.getOriginProductNo());
            }
            link.setNaverStatus(status);
            link.setLastSyncedAt(LocalDateTime.now());
            link.setLastError(null);
            return linkRepository.save(link);
        } catch (RuntimeException e) {
            log.warn("[{}] 상태 변경 실패 productId={} → {}: {}", ch, productId, status, e.getMessage());
            recordError(productId, ch, e.getMessage());
            throw e;
        }
    }

    // ── 판매중지 ────────────────────────────────────────────────────────────────

    public void suspend(String channel, Long productId) {
        String ch = normalize(channel);
        suspendLink(linkRepository.findByProductIdAndChannel(productId, ch).orElse(null));
    }

    /** 키오스크 상품 삭제 시: 이 상품이 링크된 모든 채널에서 판매중지(best-effort). */
    public void suspendEverywhere(Long productId) {
        for (ChannelProductLink link : linkRepository.findByProductId(productId)) {
            suspendLink(link);
        }
    }

    private void suspendLink(ChannelProductLink link) {
        if (link == null || link.getOriginProductNo() == null) {
            return;
        }
        SalesChannelConnector conn = connectors.get(normalize(link.getChannel()));
        if (conn == null) {
            return;
        }
        try {
            conn.suspend(link.getOriginProductNo());
            link.setNaverStatus(STATUS_SUSPENSION);
            link.setLastSyncedAt(LocalDateTime.now());
            link.setLastError(null);
        } catch (RuntimeException e) {
            log.warn("[{}] 판매중지 실패 productId={}: {}", link.getChannel(), link.getProductId(), e.getMessage());
            link.setLastError("판매중지 실패: " + e.getMessage());
        }
        linkRepository.save(link);
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────────────────

    private ChannelProductLink saveSnapshot(ChannelProductLink link, Long productId, String channel, Product product, ConnectorResult result) {
        if (link == null) {
            link = ChannelProductLink.builder().productId(productId).channel(channel).build();
        }
        if (result.originProductNo() != null) {
            link.setOriginProductNo(result.originProductNo());
        }
        if (result.channelProductNo() != null) {
            link.setChannelProductNo(result.channelProductNo());
        }
        link.setNaverStatus(result.status());
        link.setLastSyncedName(product.getName());
        link.setLastSyncedPrice(product.getPriceC());
        link.setLastSyncedStock(computeEffectiveStock(product));
        if (result.channelImageUrls() != null && !result.channelImageUrls().isEmpty()) {
            link.setChannelImageUrls(writeJson(result.channelImageUrls()));
        }
        link.setLastSyncedAt(LocalDateTime.now());
        link.setLastError(null);
        return linkRepository.save(link);
    }

    private void recordError(Long productId, String channel, String message) {
        linkRepository.findByProductIdAndChannel(productId, channel).ifPresent(link -> {
            link.setLastError(message);
            linkRepository.save(link);
        });
    }

    /** 복합옵션이면 조합 재고 합, 아니면 단품 재고. (프론트 computeEffectiveStock 와 동일 규칙) */
    public int computeEffectiveStock(Product product) {
        List<Combination> combos = new ArrayList<>();
        if (product.getCombinations() != null) {
            for (Combination c : product.getCombinations()) {
                if (c.getDeleted() == null || !c.getDeleted()) {
                    combos.add(c);
                }
            }
        }
        if (!combos.isEmpty()) {
            int sum = 0;
            for (Combination c : combos) {
                sum += c.getStock() != null ? c.getStock() : 0;
            }
            return sum;
        }
        return product.getStock() != null ? product.getStock() : 0;
    }

    private List<ImagePart> buildImageParts(Product product) {
        List<ImagePart> parts = new ArrayList<>();
        if (product.getImages() == null) {
            return parts;
        }
        for (String url : product.getImages()) {
            if (url == null || url.isBlank()) {
                continue;
            }
            String fileName = extractFileName(url);
            try {
                byte[] bytes = fileService.loadFileBytes(fileName);
                // 확장자가 아니라 실제 바이트(매직넘버)로 형식을 판별한다. 저장소에는 .PNG 로 저장돼 있지만
                // 실제 내용은 JPEG 인 파일이 많아, 확장자만 믿으면 채널이 "올바른 이미지 파일이 아닙니다"로 거부한다.
                MediaType mediaType = detectMediaType(bytes, fileName);
                String sendName = withExtensionFor(fileName, mediaType);
                parts.add(new ImagePart(bytes, sendName, mediaType));
            } catch (IOException e) {
                log.warn("이미지 파일 로드 실패(건너뜀): {} ({})", fileName, e.getMessage());
            }
        }
        return parts;
    }

    private String extractFileName(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return name;
        }
    }

    private MediaType guessMediaType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        return MediaType.IMAGE_JPEG;
    }

    /** 파일 시그니처(매직넘버)로 실제 이미지 형식을 판별한다. 판별 불가 시 확장자 기반 추정으로 폴백. */
    private MediaType detectMediaType(byte[] bytes, String fileName) {
        if (bytes != null && bytes.length >= 4) {
            int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF, b2 = bytes[2] & 0xFF, b3 = bytes[3] & 0xFF;
            if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
                return MediaType.IMAGE_JPEG;
            }
            if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) {
                return MediaType.IMAGE_PNG;
            }
            if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46) {
                return MediaType.IMAGE_GIF;
            }
        }
        return guessMediaType(fileName);
    }

    /** 전송 파일명 확장자를 실제 형식에 맞춘다. 네이버 PhotoInfra 는 content-type 뿐 아니라 확장자도 검증한다. */
    private String withExtensionFor(String fileName, MediaType mediaType) {
        int dot = fileName.lastIndexOf('.');
        String base = dot >= 0 ? fileName.substring(0, dot) : fileName;
        if (MediaType.IMAGE_JPEG.equals(mediaType)) {
            return base + ".jpg";
        }
        if (MediaType.IMAGE_PNG.equals(mediaType)) {
            return base + ".png";
        }
        if (MediaType.IMAGE_GIF.equals(mediaType)) {
            return base + ".gif";
        }
        return fileName;
    }

    private String writeJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private boolean nullSafeEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    // ── DTO ────────────────────────────────────────────────────────────────────

    public record PushResult(Long productId, String name, boolean success, String message) {
    }

    public record FieldChange(String field, String before, String after) {
    }

    public record ChangePreview(Long productId, String name, Long originProductNo, List<FieldChange> changes) {
    }
}
