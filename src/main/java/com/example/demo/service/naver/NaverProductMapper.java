package com.example.demo.service.naver;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.demo.config.NaverProperties;
import com.example.demo.entity.CategoryRef;
import com.example.demo.entity.Combination;
import com.example.demo.entity.OptionGroup;
import com.example.demo.entity.Product;
import com.example.demo.repository.CategoryRepository;
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

    private static final int MIN_TAG_LENGTH = 2;
    private static final int MAX_TAG_LENGTH = 25;
    private static final int MAX_PRODUCT_NAME_LENGTH = 100; // 네이버 상품명 최대 길이

    /**
     * 배관·설비 도메인 검색어 시드 사전. 상품명에 stem 이 포함되면 관련 검색태그를 함께 붙인다.
     * (예전 수동 등록 상품들이 실제로 쓰던 태그 어휘 — 네이버 커머스API 실측 기반으로 구성.)
     * 이 스토어 카탈로그(배관용품) 특성에 맞춘 값이며, 품목이 확장되면 여기에 추가한다.
     */
    private static final java.util.LinkedHashMap<String, List<String>> STEM_SEED_TAGS = new java.util.LinkedHashMap<>();

    /** 상품명에 등장하면 브랜드/제조사로 채택할 알려진 제조사(구체적 표기를 먼저 검사). */
    private static final java.util.LinkedHashMap<String, String> KNOWN_MAKERS = new java.util.LinkedHashMap<>();

    static {
        STEM_SEED_TAGS.put("멀티조인트", List.of("멀티조인트", "카플링", "배관연결"));
        STEM_SEED_TAGS.put("노허브", List.of("노허브", "카플링", "배관연결"));
        STEM_SEED_TAGS.put("아답타", List.of("아답타", "배관연결", "배관보수"));
        STEM_SEED_TAGS.put("에뎁타", List.of("아답타", "배관연결"));
        STEM_SEED_TAGS.put("어댑터", List.of("아답타", "배관연결"));
        STEM_SEED_TAGS.put("엘보", List.of("엘보", "배관연결"));
        STEM_SEED_TAGS.put("조인트", List.of("조인트", "배관연결"));
        STEM_SEED_TAGS.put("소켓", List.of("연결소켓", "배관연결"));
        STEM_SEED_TAGS.put("커플링", List.of("카플링", "커플링"));
        STEM_SEED_TAGS.put("카플링", List.of("카플링", "커플링"));
        STEM_SEED_TAGS.put("커프링", List.of("카플링", "커플링"));
        STEM_SEED_TAGS.put("카프링", List.of("카플링", "커플링"));
        STEM_SEED_TAGS.put("동관", List.of("동관", "배관보수"));
        STEM_SEED_TAGS.put("엑셀", List.of("엑셀부속", "배관보수"));
        STEM_SEED_TAGS.put("주름", List.of("주름소켓", "변환소켓"));
        STEM_SEED_TAGS.put("피비", List.of("PB부속", "배관보수"));
        STEM_SEED_TAGS.put("pb", List.of("PB부속", "배관보수"));
        STEM_SEED_TAGS.put("밸브", List.of("밸브", "배관보수"));
        STEM_SEED_TAGS.put("수전", List.of("수전", "수도꼭지"));
        STEM_SEED_TAGS.put("수도꼭지", List.of("수도꼭지", "수전"));
        STEM_SEED_TAGS.put("보온", List.of("보온재", "배관보수"));
        STEM_SEED_TAGS.put("니플", List.of("니플", "배관연결"));

        KNOWN_MAKERS.put("영남메탈", "영남메탈");
        KNOWN_MAKERS.put("대성금속", "대성금속");
        KNOWN_MAKERS.put("대성", "대성금속");
        KNOWN_MAKERS.put("이지조인트", "조인탑");
        KNOWN_MAKERS.put("ez-joint", "조인탑");
        KNOWN_MAKERS.put("조인탑", "조인탑");
        KNOWN_MAKERS.put("럭키", "럭키");
        KNOWN_MAKERS.put("천일", "천일");
        KNOWN_MAKERS.put("보광", "보광");
    }

    private final NaverProperties props;
    private final ObjectMapper objectMapper;
    /** 검색태그 자동생성 시 카테고리 ID(예: "pipes") → 한글명("배관용품") 해석용. */
    private final CategoryRepository categoryRepository;

    /**
     * @param statusType "SALE"(판매) 또는 "SUSPENSION"(판매중지)
     */
    public ObjectNode toProductPayload(Product product, long leafCategoryId, List<String> naverImageUrls, String statusType) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode origin = root.putObject("originProduct");

        int[] plan = pricingPlan(product); // [salePrice, immediateDiscount, optionBase]
        origin.put("statusType", statusType);
        origin.put("leafCategoryId", leafCategoryId);
        // 검색 노출의 1순위 신호. ERP 상품명에 카테고리/규격/도메인 검색어를 덧붙여 키워드를 보강한다.
        origin.put("name", buildSearchName(product));
        origin.put("salePrice", plan[0]);
        origin.put("stockQuantity", effectiveStock(product));
        origin.put("detailContent", buildDetailContent(product, naverImageUrls));
        origin.put("productState", "NEW");

        // 옵션 가격대가 넓어(예: 7,000~29,000) 기본 판매가 기준 옵션가 범위(-50%~+100%)를 벗어나는 경우,
        // 판매가를 올리고 즉시할인으로 실제가를 맞춘다(있을 때만). plan[1] = 즉시할인액(원).
        if (plan[1] > 0) {
            ObjectNode benefit = origin.putObject("customerBenefit");
            ObjectNode disc = benefit.putObject("immediateDiscountPolicy");
            ObjectNode method = disc.putObject("discountMethod");
            method.put("value", plan[1]);
            method.put("unitType", "WON");
        }

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

        // 검색품질(브랜드/제조사/모델) — 네이버 검색품질 체크의 '브랜드/제조사 입력 안됨' 해소.
        ObjectNode shoppingSearch = buildNaverShoppingSearchInfo(product);
        if (!shoppingSearch.isEmpty()) {
            detailAttribute.set("naverShoppingSearchInfo", shoppingSearch);
        }

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

    /**
     * 넓은 옵션 가격대를 네이버 옵션가 범위(판매가의 -50%~+100%) 안에 넣기 위한 가격 계획.
     * 반환: [salePrice(판매가), immediateDiscount(즉시할인액,원), optionBase(옵션 추가금 기준가)].
     *
     *  - 옵션 없음, 또는 기본 판매가로 모든 옵션이 범위에 들어오면: 그대로 [단가, 0, 단가].
     *  - 넓으면: 대표(추가금 0)를 "재고>0 옵션가 중 최저가" 로 잡아 메인가격을 가장 낮은 옵션가에 맞춘다.
     *    추가금 = combo - 최저재고옵션가 ≥ 0 이 되고, 넓은 스프레드는 판매가↑·즉시할인으로 흡수한다.
     *    고객 결제가 = (판매가-할인)+추가금 = 최저 + (combo-최저) = combo 로 정확히 유지된다.
     */
    private int[] pricingPlan(Product product) {
        int base = product.getPrice() != null ? product.getPrice() : 0;
        List<Combination> combos = activeCombinations(product);
        if (combos.isEmpty()) {
            return new int[] { base, 0, base };
        }
        int lowP = Integer.MAX_VALUE;
        int highP = Integer.MIN_VALUE;
        boolean baseIsInStockCombo = false;
        for (Combination c : combos) {
            int p = c.getPrice() != null ? c.getPrice() : 0;
            int st = c.getStock() != null ? c.getStock() : 0;
            lowP = Math.min(lowP, p);
            highP = Math.max(highP, p);
            if (p == base && st > 0) {
                baseIsInStockCombo = true;
            }
        }
        // 1) 기본 판매가가 재고>0 옵션가와 일치하고 모든 옵션이 -50%~+100% 안이면: 할인 없이 그대로.
        //    (delta 0 인 옵션이 존재 → 네이버 "옵션가 0 옵션 필수" 조건도 충족)
        if (base > 0 && baseIsInStockCombo && lowP >= base - base / 2 && highP <= base * 2) {
            return new int[] { base, 0, base };
        }
        // 2) 그 외: 즉시할인으로 옵션가를 판매가의 ±50%(할인 시 실측 상한) 안에 배치한다.
        //    대표(추가금 0)는 "재고>0 옵션가 중 최저가" 로 잡아 메인가격을 가장 낮은 옵션가에 맞춘다.
        //    → 모든 재고 옵션의 추가금이 0 이상이 되고, 넓은 스프레드는 판매가↑·즉시할인이 흡수한다.
        int rep = -1;
        for (Combination c : combos) {
            int p = c.getPrice() != null ? c.getPrice() : 0;
            int st = c.getStock() != null ? c.getStock() : 0;
            if (st <= 0) {
                continue;
            }
            if (rep < 0 || p < rep) {
                rep = p; // 재고>0 옵션가 중 최저 → 필수 0원(추가금) 옵션이자 메인가격
            }
        }
        if (rep < 0) { // 전부 재고 0인 예외적 경우
            rep = lowP;
        }
        // 추가금 = combo - rep ∈ [lowP-rep, highP-rep]. 최대 절대값이 판매가의 ~50% 이내가 되도록 판매가를 키운다.
        int bestWorst = Math.max(highP - rep, rep - lowP);
        int salePrice = Math.max((int) Math.ceil(bestWorst * 2.4), rep); // 즉시할인 = salePrice-rep ≥ 0 보장
        int discount = salePrice - rep;
        return new int[] { salePrice, discount, rep };
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
            // 네이버 옵션가는 절대가격이 아니라 "추가금(증감액)" 이다. pricingPlan 이 정한 기준가(optionBase)를
            // 빼서 추가금으로 변환한다. (넓은 스프레드는 판매가↑+즉시할인으로 기준가를 최저옵션가에 맞춰둠)
            int basePrice = pricingPlan(product)[2];
            int comboPrice = c.getPrice() != null ? c.getPrice() : 0;
            combo.put("price", comboPrice - basePrice); // 추가금 = 조합 절대가 - 기준가
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
        seo.put("pageTitle", truncate(buildSearchName(product), 100));

        String desc = product.getDescription();
        if (desc == null || desc.isBlank()) {
            desc = product.getName() != null ? product.getName() : "";
        }
        seo.put("metaDescription", truncate(desc.replaceAll("\\s+", " ").trim(), 160));

        List<String> sellerTags = resolveSellerTags(product);
        if (!sellerTags.isEmpty()) {
            ArrayNode tags = objectMapper.createArrayNode();
            for (String t : sellerTags) {
                tags.addObject().put("text", t);
            }
            seo.set("sellerTags", tags);
        }
        return seo;
    }

    /**
     * 검색태그(sellerTags) 결정.
     * 1) 상품에 해시태그가 있으면 그대로 사용.
     * 2) 없으면(ERP 자동수집 상품 등) 상품명 토큰 + 카테고리 한글명으로 자동 생성한다.
     *    네이버는 자기네 태그 사전에 걸린 태그만 '검색 적용'으로 카운트하므로, 실제 검색어에
     *    가까운 상품명/카테고리 토큰을 넣어 검색 적용 확률을 높인다.
     */
    private List<String> resolveSellerTags(Product product) {
        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
        if (product.getHashtags() != null) {
            for (String raw : product.getHashtags()) {
                addTag(tags, raw);
            }
        }
        if (tags.isEmpty()) {
            // 상품명 토큰 → 도메인 시드 태그/카테고리 순으로 채운다(앞쪽이 우선순위).
            for (String token : tokenize(product.getName())) {
                addTag(tags, token);
            }
            for (String keyword : deriveKeywords(product)) {
                addTag(tags, keyword);
            }
        }
        List<String> result = new ArrayList<>(tags);
        return result.size() > MAX_SELLER_TAGS ? result.subList(0, MAX_SELLER_TAGS) : result;
    }

    /**
     * 상품의 검색 키워드 도출: 도메인 시드 태그(상품명 stem 매칭) + 카테고리 한글명 + 규격.
     * 상품명 키워드 보강(buildSearchName)과 검색태그(resolveSellerTags) 양쪽에서 공유한다.
     */
    private List<String> deriveKeywords(Product product) {
        java.util.LinkedHashSet<String> kws = new java.util.LinkedHashSet<>();
        String lower = (product.getName() != null ? product.getName() : "").toLowerCase();
        for (java.util.Map.Entry<String, List<String>> e : STEM_SEED_TAGS.entrySet()) {
            if (lower.contains(e.getKey().toLowerCase())) {
                kws.addAll(e.getValue());
            }
        }
        kws.addAll(categoryNames(product));
        if (product.getGyu() != null && !product.getGyu().isBlank()) {
            kws.add(product.getGyu().trim());
        }
        return new ArrayList<>(kws);
    }

    /**
     * ERP 상품명에 카테고리/규격/도메인 검색어를 덧붙여 검색 키워드를 보강한 상품명.
     * 이미 이름에 들어있는 단어는 중복 추가하지 않고, 네이버 상품명 최대 길이(100자)를 넘지 않게 자른다.
     */
    private String buildSearchName(Product product) {
        String base = product.getName() != null ? product.getName().trim() : "";
        if (base.isEmpty()) {
            return base;
        }
        // 이미 이름에 등장하는 단어(소문자) 집합
        java.util.Set<String> present = new java.util.HashSet<>();
        for (String w : tokenize(base)) {
            present.add(w.toLowerCase());
        }
        StringBuilder sb = new StringBuilder(base);
        for (String kw : deriveKeywords(product)) {
            String w = kw.trim();
            if (w.isEmpty() || present.contains(w.toLowerCase())) {
                continue;
            }
            if (sb.length() + 1 + w.length() > MAX_PRODUCT_NAME_LENGTH) {
                continue; // 이건 건너뛰고 더 짧은 키워드가 있으면 채운다
            }
            sb.append(' ').append(w);
            present.add(w.toLowerCase());
        }
        return sb.toString();
    }

    /** 태그 정규화: '#'/양끝 구두점 제거, 2~25자만 허용, LinkedHashSet 으로 중복 제거. */
    private void addTag(java.util.Set<String> tags, String raw) {
        if (raw == null) {
            return;
        }
        String t = raw.trim();
        if (t.startsWith("#")) {
            t = t.substring(1).trim();
        }
        // 앞뒤 구두점/특수문자만 정리(한글·영숫자·내부 공백은 보존)
        t = t.replaceAll("^[^0-9A-Za-z가-힣]+", "").replaceAll("[^0-9A-Za-z가-힣]+$", "");
        if (t.length() < MIN_TAG_LENGTH) {
            return;
        }
        if (t.length() > MAX_TAG_LENGTH) {
            t = t.substring(0, MAX_TAG_LENGTH);
        }
        tags.add(t);
    }

    /** 상품명을 공백/구분자 기준으로 토큰화. */
    private List<String> tokenize(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : name.split("[\\s/,()\\[\\]{}·|~\\-]+")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    /** 상품 카테고리 ID(예: "pipes")를 한글명("배관용품")으로 해석. 소분류를 대분류보다 먼저. */
    private List<String> categoryNames(Product product) {
        if (product.getCategories() == null || product.getCategories().isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (CategoryRef ref : product.getCategories()) {
            addCategoryName(names, ref.getSubCategory());
            addCategoryName(names, ref.getMainCategory());
        }
        return names;
    }

    private void addCategoryName(List<String> names, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return;
        }
        categoryRepository.findById(categoryId)
                .map(c -> c.getName())
                .filter(n -> n != null && !n.isBlank())
                .ifPresent(names::add);
    }

    /**
     * 검색품질용 브랜드/제조사/모델(detailAttribute.naverShoppingSearchInfo).
     * 예전 수동 등록 관행을 반영: 상품명에서 알려진 제조사가 검출되면 그 값을, 없으면 설정(.env) 기본값을 쓴다.
     * 설정 기본값이 비어 있으면(제조사 공란 선호) 해당 필드는 넣지 않는다.
     * 모델명은 (보강 전) 원래 상품명을 재활용한다.
     */
    private ObjectNode buildNaverShoppingSearchInfo(Product product) {
        ObjectNode info = objectMapper.createObjectNode();
        String detectedMaker = detectMaker(product.getName());
        // 상품에 직접 지정한 브랜드가 최우선(사용자 명시 의도 > 상품명 자동검출 > .env 기본값)
        String explicitBrand = (product.getBrandName() != null && !product.getBrandName().isBlank())
                ? product.getBrandName().trim()
                : null;

        String brand = explicitBrand != null ? explicitBrand
                : (detectedMaker != null ? detectedMaker : props.getBrandName());
        if (brand != null && !brand.isBlank()) {
            info.put("brandName", brand.trim());
        }
        String manufacturer = explicitBrand != null ? explicitBrand
                : (detectedMaker != null ? detectedMaker : props.getManufacturerName());
        if (manufacturer != null && !manufacturer.isBlank()) {
            info.put("manufacturerName", manufacturer.trim());
        }
        if (product.getName() != null && !product.getName().isBlank()) {
            info.put("modelName", truncate(product.getName(), MAX_PRODUCT_NAME_LENGTH));
        }
        return info;
    }

    /** 상품명에 알려진 제조사 표기가 있으면 표준 제조사명을 반환(없으면 null). 구체적 표기를 먼저 검사. */
    private String detectMaker(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String lower = name.toLowerCase();
        for (java.util.Map.Entry<String, String> e : KNOWN_MAKERS.entrySet()) {
            if (lower.contains(e.getKey().toLowerCase())) {
                return e.getValue();
            }
        }
        return null;
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
