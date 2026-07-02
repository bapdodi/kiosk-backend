package com.example.demo.service.naver;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.demo.config.NaverProperties;
import com.example.demo.entity.Combination;
import com.example.demo.entity.OptionGroup;
import com.example.demo.entity.Product;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;

/**
 * 키오스크 {@link Product} → 네이버 커머스API 상품 등록/수정 payload 변환기.
 *
 * ⚠ 네이버 v2 상품 스키마는 카테고리/버전에 따라 필드 구성이 달라질 수 있다. 여기서는 문서상 공통
 *    필수 필드 위주로 구성했으며, 실제 등록 성공까지는 공식 문서로 필드 nesting 을 확정해야 한다.
 *    (특히 detailAttribute.optionInfo, deliveryInfo, productInfoProvidedNotice)
 *
 * 이미지 URL 은 반드시 네이버 이미지 업로드 API 가 돌려준 CDN URL 이어야 하므로, 업로드는
 * 상위 {@code NaverConnector}/{@code ChannelSyncService} 가 수행하고 그 결과 URL 목록을 이 매퍼에 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class NaverProductMapper {

    private static final int MAX_OPTIONAL_IMAGES = 9;
    private static final int MAX_SELLER_TAGS = 10;
    private static final String COMBINATION_SEPARATOR = " / ";
    private static final String NOTICE_REFERENCE = "상품상세참조";

    private final NaverProperties props;
    private final ObjectMapper objectMapper;

    /**
     * @param statusType "SALE"(판매) 또는 "SUSPENSION"(판매중지)
     */
    public ObjectNode toProductPayload(Product product, long leafCategoryId, List<String> naverImageUrls, String statusType) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode origin = root.putObject("originProduct");

        origin.put("statusType", statusType);
        origin.put("leafCategoryId", leafCategoryId);
        origin.put("name", product.getName());
        origin.put("salePrice", product.getPrice() != null ? product.getPrice() : 0);
        origin.put("stockQuantity", effectiveStock(product));
        origin.put("detailContent", buildDetailContent(product, naverImageUrls));
        origin.put("productState", "NEW");

        // 대표/추가 이미지
        ObjectNode images = origin.putObject("images");
        if (!naverImageUrls.isEmpty()) {
            images.putObject("representativeImage").put("url", naverImageUrls.get(0));
            if (naverImageUrls.size() > 1) {
                ArrayNode optional = images.putArray("optionalImages");
                for (int i = 1; i < naverImageUrls.size() && i <= MAX_OPTIONAL_IMAGES; i++) {
                    optional.addObject().put("url", naverImageUrls.get(i));
                }
            }
        }

        // 상세 속성(A/S, 원산지, 미성년자, 옵션)
        ObjectNode detailAttribute = origin.putObject("detailAttribute");
        ObjectNode afterService = detailAttribute.putObject("afterServiceInfo");
        afterService.put("afterServiceTelephoneNumber", nullToEmpty(props.getAsTelephone()));
        afterService.put("afterServiceGuideContent", nullToEmpty(props.getAsGuideContent()));
        // 원산지: 상품별 값 우선, 없으면 .env 의 기본 원산지 코드로 폴백
        String originAreaCode = (product.getOriginAreaCode() != null && !product.getOriginAreaCode().isBlank())
                ? product.getOriginAreaCode()
                : props.getOriginAreaCode();
        if (originAreaCode != null && !originAreaCode.isBlank()) {
            detailAttribute.putObject("originAreaInfo").put("originAreaCode", originAreaCode);
        }
        detailAttribute.put("minorPurchasable", true);
        addOptionInfo(detailAttribute, product);

        // SEO(제목/설명/검색태그) — 검색 노출 최적화
        detailAttribute.set("seoInfo", buildSeoInfo(product));

        // 상품정보제공고시(네이버 필수). 품목군은 기타 재화(ETC) 기본값으로 구성.
        detailAttribute.set("productInfoProvidedNotice", buildProductInfoProvidedNotice(product));

        // 배송 정보
        origin.set("deliveryInfo", buildDeliveryInfo());

        // 스마트스토어 채널 노출
        ObjectNode channel = root.putObject("smartstoreChannelProduct");
        channel.put("naverShoppingRegistration", true);
        channel.put("channelProductDisplayStatusType", "SUSPENSION".equals(statusType) ? "SUSPENSION" : "ON");

        return root;
    }

    /** 옵션이 있으면 조합 재고 합, 없으면 단품 재고. */
    public int effectiveStock(Product product) {
        List<Combination> combos = activeCombinations(product);
        if (!combos.isEmpty()) {
            int sum = 0;
            for (Combination c : combos) {
                sum += c.getStock() != null ? c.getStock() : 0;
            }
            return sum;
        }
        return product.getStock() != null ? product.getStock() : 0;
    }

    private List<Combination> activeCombinations(Product product) {
        List<Combination> result = new ArrayList<>();
        if (product.getCombinations() != null) {
            for (Combination c : product.getCombinations()) {
                if (c.getDeleted() == null || !c.getDeleted()) {
                    result.add(c);
                }
            }
        }
        return result;
    }

    /**
     * detailAttribute.optionInfo 구성.
     * 1) ERP 복합옵션(combinations): combo.name("색상:빨강 / 사이즈:L")을 파싱해 조합형 옵션 생성.
     * 2) 단순 optionGroups: 값들의 카티션 곱으로 조합 생성(추가금 0, 재고=단품재고).
     * 파싱이 애매하면 단일 그룹 "옵션" 으로 폴백한다.
     */
    private void addOptionInfo(ObjectNode detailAttribute, Product product) {
        List<Combination> combos = activeCombinations(product);
        if (!combos.isEmpty()) {
            ObjectNode optionInfo = buildOptionInfoFromCombinations(combos, product);
            if (optionInfo != null) {
                detailAttribute.set("optionInfo", optionInfo);
            }
            return;
        }
        if (product.getOptionGroups() != null && !product.getOptionGroups().isEmpty()) {
            ObjectNode optionInfo = buildOptionInfoFromGroups(product.getOptionGroups(), product);
            if (optionInfo != null) {
                detailAttribute.set("optionInfo", optionInfo);
            }
        }
    }

    private ObjectNode buildOptionInfoFromCombinations(List<Combination> combos, Product product) {
        // 각 조합의 name 을 "그룹:값 / 그룹:값" 으로 파싱
        List<String> groupNames = new ArrayList<>();
        List<List<String>> parsedValues = new ArrayList<>(); // combo 별 값 목록
        boolean parseable = true;

        for (Combination c : combos) {
            String name = c.getName() != null ? c.getName() : "";
            String[] parts = name.split(COMBINATION_SEPARATOR);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                int colon = part.indexOf(':');
                String g = colon >= 0 ? part.substring(0, colon).trim() : "옵션" + (i + 1);
                String v = colon >= 0 ? part.substring(colon + 1).trim() : part;
                if (groupNames.size() <= i) {
                    groupNames.add(g);
                } else if (!groupNames.get(i).equals(g)) {
                    // 그룹명이 조합마다 다르면 파싱 불가로 판단
                    parseable = false;
                }
                values.add(v);
            }
            parsedValues.add(values);
        }

        // 조합마다 그룹 개수가 다르면 폴백
        if (parseable) {
            int expected = groupNames.size();
            for (List<String> v : parsedValues) {
                if (v.size() != expected) {
                    parseable = false;
                    break;
                }
            }
        }
        if (!parseable || groupNames.isEmpty() || groupNames.size() > 4) {
            // 폴백: 단일 그룹 "옵션", 값 = 조합 전체 이름
            groupNames = List.of("옵션");
            parsedValues = new ArrayList<>();
            for (Combination c : combos) {
                parsedValues.add(List.of(c.getName() != null ? c.getName() : "옵션"));
            }
        }

        ObjectNode optionInfo = objectMapper.createObjectNode();
        ObjectNode groupNamesNode = optionInfo.putObject("optionCombinationGroupNames");
        for (int i = 0; i < groupNames.size(); i++) {
            groupNamesNode.put("optionGroupName" + (i + 1), groupNames.get(i));
        }
        ArrayNode combosNode = optionInfo.putArray("optionCombinations");
        for (int idx = 0; idx < combos.size(); idx++) {
            Combination c = combos.get(idx);
            List<String> values = parsedValues.get(idx);
            ObjectNode combo = combosNode.addObject();
            for (int i = 0; i < values.size(); i++) {
                combo.put("optionName" + (i + 1), values.get(i));
            }
            combo.put("stockQuantity", c.getStock() != null ? c.getStock() : 0);
            combo.put("price", c.getPrice() != null ? c.getPrice() : 0); // 추가금
            combo.put("usable", true);
        }
        optionInfo.put("useStockManagement", true);
        return optionInfo;
    }

    private ObjectNode buildOptionInfoFromGroups(List<OptionGroup> groups, Product product) {
        List<OptionGroup> usable = new ArrayList<>();
        for (OptionGroup g : groups) {
            if (g.getValues() != null && !g.getValues().isEmpty()) {
                usable.add(g);
            }
        }
        if (usable.isEmpty() || usable.size() > 4) {
            return null;
        }

        ObjectNode optionInfo = objectMapper.createObjectNode();
        ObjectNode groupNamesNode = optionInfo.putObject("optionCombinationGroupNames");
        for (int i = 0; i < usable.size(); i++) {
            groupNamesNode.put("optionGroupName" + (i + 1), usable.get(i).getName());
        }

        ArrayNode combosNode = optionInfo.putArray("optionCombinations");
        int stockPer = product.getStock() != null ? product.getStock() : 0;
        List<List<String>> product2 = cartesian(usable);
        for (List<String> values : product2) {
            ObjectNode combo = combosNode.addObject();
            for (int i = 0; i < values.size(); i++) {
                combo.put("optionName" + (i + 1), values.get(i));
            }
            combo.put("stockQuantity", stockPer);
            combo.put("price", 0);
            combo.put("usable", true);
        }
        optionInfo.put("useStockManagement", true);
        return optionInfo;
    }

    /** optionGroups 값들의 카티션 곱. */
    private List<List<String>> cartesian(List<OptionGroup> groups) {
        List<List<String>> result = new ArrayList<>();
        result.add(new ArrayList<>());
        for (OptionGroup g : groups) {
            List<List<String>> next = new ArrayList<>();
            for (List<String> prefix : result) {
                for (String value : g.getValues()) {
                    List<String> combo = new ArrayList<>(prefix);
                    combo.add(value);
                    next.add(combo);
                }
            }
            result = next;
        }
        return result;
    }

    /** 상세설명 HTML 구성: 설명 텍스트 + 이미지들. 네이버는 detailContent(HTML) 를 필수로 요구한다. */
    private String buildDetailContent(Product product, List<String> naverImageUrls) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"text-align:center;\">");
        String desc = product.getDescription();
        if (desc == null || desc.isBlank()) {
            desc = product.getName();
        }
        sb.append("<p>").append(escapeHtml(desc).replace("\n", "<br>")).append("</p>");
        for (String url : naverImageUrls) {
            sb.append("<p><img src=\"").append(escapeHtml(url)).append("\" alt=\"")
                    .append(escapeHtml(product.getName())).append("\"></p>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * SEO 정보(detailAttribute.seoInfo): 검색결과 제목/설명 + 검색태그(sellerTags).
     * sellerTags 는 상품의 hashtags 를 최대 10개까지 {text} 형태로 전달한다(code 생략).
     */
    private ObjectNode buildSeoInfo(Product product) {
        ObjectNode seo = objectMapper.createObjectNode();
        String name = product.getName() != null ? product.getName() : "";
        seo.put("pageTitle", truncate(name, 100));

        String desc = product.getDescription();
        if (desc == null || desc.isBlank()) {
            desc = name;
        }
        seo.put("metaDescription", truncate(desc.replaceAll("\\s+", " ").trim(), 160));

        if (product.getHashtags() != null && !product.getHashtags().isEmpty()) {
            ArrayNode tags = objectMapper.createArrayNode();
            java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
            for (String raw : product.getHashtags()) {
                if (raw == null) {
                    continue;
                }
                String t = raw.trim();
                if (t.startsWith("#")) {
                    t = t.substring(1).trim();
                }
                if (t.isEmpty() || !seen.add(t)) {
                    continue;
                }
                tags.addObject().put("text", t);
                if (tags.size() >= MAX_SELLER_TAGS) {
                    break;
                }
            }
            if (!tags.isEmpty()) {
                seo.set("sellerTags", tags);
            }
        }
        return seo;
    }

    /**
     * 상품정보제공고시(detailAttribute.productInfoProvidedNotice) — 네이버 필수.
     * 품목군을 특정하기 어려운 키오스크 상품 특성상 기타 재화(ETC)로 구성하고,
     * 텍스트 항목은 위탁판매 관행대로 "상품상세참조"로 채운다. 품명/모델명은 상품명,
     * 소비자상담 전화는 .env 의 A/S 전화번호를 사용한다.
     */
    private ObjectNode buildProductInfoProvidedNotice(Product product) {
        ObjectNode notice = objectMapper.createObjectNode();
        notice.put("productInfoProvidedNoticeType", "ETC");
        ObjectNode etc = notice.putObject("etc");
        etc.put("returnCostReason", NOTICE_REFERENCE);
        etc.put("noRefundReason", NOTICE_REFERENCE);
        etc.put("qualityAssuranceStandard", NOTICE_REFERENCE);
        etc.put("compensationProcedure", NOTICE_REFERENCE);
        etc.put("troubleShootingContents", NOTICE_REFERENCE);
        String name = (product.getName() != null && !product.getName().isBlank())
                ? product.getName()
                : NOTICE_REFERENCE;
        etc.put("itemName", name);
        etc.put("modelName", name);
        etc.put("certificateDetails", NOTICE_REFERENCE);
        etc.put("manufacturer", NOTICE_REFERENCE);
        etc.put("customerServicePhoneNumber", nullToEmpty(props.getAsTelephone()));
        return notice;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private ObjectNode buildDeliveryInfo() {
        ObjectNode delivery = objectMapper.createObjectNode();
        delivery.put("deliveryType", "DELIVERY");
        delivery.put("deliveryAttributeType", "NORMAL");
        // 택배 배송상품은 택배사 코드 필수(예: 로젠="KGB")
        if (props.getDeliveryCompany() != null && !props.getDeliveryCompany().isBlank()) {
            delivery.put("deliveryCompany", props.getDeliveryCompany());
        }
        // 택배 배송상품은 택배사 코드 필수(예: 로젠="KGB")
        if (props.getDeliveryCompany() != null && !props.getDeliveryCompany().isBlank()) {
            delivery.put("deliveryCompany", props.getDeliveryCompany());
        }

        ObjectNode fee = delivery.putObject("deliveryFee");
        fee.put("deliveryFeeType", "PAID");
        fee.put("deliveryFeePayType", "PREPAID"); // 결제방식(선불) 필수
        fee.put("baseFee", props.getDeliveryBaseFee() != null ? props.getDeliveryBaseFee() : 3000);

        Long shippingAddressId = props.getShippingAddressIdAsLong();
        if (shippingAddressId != null) {
            delivery.put("shippingAddressId", shippingAddressId);
        }

        ObjectNode claim = delivery.putObject("claimDeliveryInfo");
        claim.put("returnDeliveryFee", props.getReturnDeliveryFee() != null ? props.getReturnDeliveryFee() : 3000);
        claim.put("exchangeDeliveryFee", props.getExchangeDeliveryFee() != null ? props.getExchangeDeliveryFee() : 6000);
        Long returnAddressId = props.getReturnAddressIdAsLong();
        if (returnAddressId != null) {
            claim.put("returnAddressId", returnAddressId);
        }
        return delivery;
    }

    private String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
