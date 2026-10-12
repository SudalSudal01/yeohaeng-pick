package kr.yeohaengpick.catalog;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

/** 관광공사의 원문을 표시용 텍스트로 정리합니다. 요금·휴무·시간표를 숫자/영업 가능 판정으로 추측하지 않습니다. */
public final class TourismInfo {

  private TourismInfo() {}

  public static final Map<String, String> TYPES = Map.of(
    "12",
    "관광지",
    "14",
    "문화시설",
    "15",
    "축제·공연·행사",
    "25",
    "여행코스",
    "28",
    "레포츠",
    "32",
    "숙박",
    "38",
    "쇼핑",
    "39",
    "음식점"
  );

  public record Content(
    String id,
    String typeId,
    String category,
    String title,
    String address,
    String telephone,
    String providerUpdatedAt
  ) {}

  public record Field(String label, String text, String status) {}

  public record Detail(
    Content content,
    String overview,
    String homepageUrl,
    Field hours,
    Field restDays,
    Field fees,
    List<Field> additional,
    String queriedAt,
    String source,
    String sourceUrl
  ) {}

  public record Search(
    List<Content> items,
    int totalCount,
    int page,
    int pageSize,
    String queriedAt
  ) {}

  /** HTML은 실행/삽입하지 않습니다. 개행은 남겨 긴 운영 안내도 읽기 쉽게 표시합니다. */
  public static String text(String value) {
    String clean = HtmlUtils.htmlUnescape(Objects.toString(value, ""))
      .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
      .replaceAll("(?i)<br\\s*/?>|</(?:p|div|li)\\s*>", "\n")
      .replaceAll("(?s)<[^>]*>", "")
      // 계절별 시각 안내가 ')['로 붙어 온 경우도 내용은 유지하고 문단만 나눕니다.
      .replaceAll("(?<=\\))(?=\\[)", "\n")
      .replace('\u00a0', ' ')
      .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
      .replaceAll("[ \\t]+", " ")
      .replaceAll("\\n{3,}", "\n\n")
      .trim();
    return clean.length() > 5000 ? clean.substring(0, 5000) + "…" : clean;
  }

  private static String value(JsonNode node, String key) {
    return text(node.path(key).asText(""));
  }

  public static Content content(JsonNode node) {
    String type = node.path("contenttypeid").asText();
    return new Content(
      node.path("contentid").asText(),
      type,
      TYPES.getOrDefault(type, "관광정보"),
      value(node, "title"),
      (value(node, "addr1") + " " + value(node, "addr2")).trim(),
      value(node, "tel"),
      value(node, "modifiedtime")
    );
  }

  private static Field field(String label, String text) {
    return new Field(label, text, text.isBlank() ? "NOT_PROVIDED" : "PROVIDED");
  }

  /** 홈페이지가 HTML 링크로 제공돼도 http(s) 주소 한 개만 따로 추출해 안전한 새 창 링크로 사용합니다. */
  static String homepage(String raw) {
    String decoded = HtmlUtils.htmlUnescape(Objects.toString(raw, "")).trim();
    var match = Pattern.compile("(?i)href\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(
      decoded
    );
    String url = match.find() ? match.group(1).trim() : decoded;
    try {
      var uri = URI.create(url);
      return ("https".equalsIgnoreCase(uri.getScheme()) ||
        "http".equalsIgnoreCase(uri.getScheme())) &&
        uri.getHost() != null &&
        uri.getUserInfo() == null &&
        !url.matches("(?s).*[\\p{Cntrl}].*")
        ? url
        : "";
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  public static Detail detail(
    JsonNode common,
    JsonNode intro,
    List<JsonNode> repeated,
    String queriedAt
  ) {
    var content = content(common);
    String type = content.typeId();
    String hourKey = switch (type) {
      case "12" -> "usetime";
      case "14" -> "usetimeculture";
      case "15" -> "playtime";
      case "28" -> "usetimeleports";
      case "38" -> "opentime";
      case "39" -> "opentimefood";
      default -> "";
    };
    String restKey = switch (type) {
      case "12" -> "restdate";
      case "14" -> "restdateculture";
      case "28" -> "restdateleports";
      case "38" -> "restdateshopping";
      case "39" -> "restdatefood";
      default -> "";
    };
    String feeKey = switch (type) {
      case "14" -> "usefee";
      case "15" -> "usetimefestival";
      case "28" -> "usefeeleports";
      default -> "";
    };
    String hours = value(intro, hourKey);
    if (type.equals("32")) hours = joinLabeled(
      intro,
      List.of(
        Map.entry("checkintime", "체크인"),
        Map.entry("checkouttime", "체크아웃")
      )
    );
    String fees = value(intro, feeKey);
    // 관광지의 입장료는 반복정보에 제공될 수 있습니다. '무료' 원문도 그대로 표시하며 원 단위 비용을 생성하지 않습니다.
    if (type.equals("12")) fees = repeated
      .stream()
      .filter(i ->
        value(i, "infoname").matches(".*(?:입장료|이용요금|이용료).*")
      )
      .map(i -> value(i, "infoname") + ": " + value(i, "infotext"))
      .reduce((a, b) -> a + "\n" + b)
      .orElse("");
    var extra = new ArrayList<Field>();
    if (
      type.equals("15") &&
      (!value(intro, "eventstartdate").isBlank() ||
        !value(intro, "eventenddate").isBlank())
    ) add(
      extra,
      "행사 기간",
      (value(intro, "eventstartdate").isBlank()
        ? "시작 미제공"
        : value(intro, "eventstartdate")) +
        " ~ " +
        (value(intro, "eventenddate").isBlank()
          ? "종료 미제공"
          : value(intro, "eventenddate"))
    );
    if (type.equals("25")) {
      add(extra, "코스 일정", value(intro, "schedule"));
      add(extra, "소요 시간", value(intro, "taketime"));
    }
    if (type.equals("39")) {
      add(extra, "대표 메뉴", value(intro, "firstmenu"));
      add(extra, "취급 메뉴", value(intro, "treatmenu"));
    }
    String suffix = switch (type) {
      case "14" -> "culture";
      case "28" -> "leports";
      case "32" -> "lodging";
      case "38" -> "shopping";
      case "39" -> "food";
      default -> "";
    };
    add(extra, "문의", value(intro, "infocenter" + suffix));
    add(extra, "주차 안내", value(intro, "parking" + suffix));
    return new Detail(
      content,
      value(common, "overview"),
      homepage(common.path("homepage").asText()),
      field(type.equals("32") ? "숙소 이용 시각" : "이용 시간", hours),
      field("쉬는 날", value(intro, restKey)),
      field("이용요금 안내", fees),
      extra,
      queriedAt,
      "한국관광공사 TourAPI",
      "https://www.data.go.kr/data/15101578/openapi.do"
    );
  }

  private static void add(List<Field> fields, String label, String text) {
    if (!text.isBlank()) fields.add(field(label, text));
  }

  private static String joinLabeled(
    JsonNode node,
    List<Map.Entry<String, String>> labels
  ) {
    return labels
      .stream()
      .filter(e -> !value(node, e.getKey()).isBlank())
      .map(e -> e.getValue() + " " + value(node, e.getKey()))
      .reduce((a, b) -> a + "\n" + b)
      .orElse("");
  }
}
