package kr.yeohaengpick.catalog;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/** 브라우저에 공개해도 되는 SDK 키와 기능 상태만 반환합니다. REST/AI 키는 절대 반환하지 않습니다. */
@RestController
public class CatalogController {

  private final CatalogService catalog;
  private final TourApiClient tourism;

  @Value("${app.kakao-js-key:}")
  private String mapKey;

  @Value("${app.gemini-key:}")
  private String aiKey;

  @Value("${app.mail-enabled:false}")
  private boolean mail;

  public CatalogController(CatalogService c, TourApiClient tourism) {
    catalog = c;
    this.tourism = tourism;
  }

  @GetMapping("/api/config")
  public Object config() {
    return Map.of(
      "mapKey",
      mapKey,
      "aiConfigured",
      !aiKey.isBlank(),
      "sampleData",
      catalog.isSample(),
      "emailConfigured",
      mail,
      "socialLoginConfigured",
      false,
      "tourismConfigured",
      tourism.configured()
    );
  }

  @GetMapping("/api/catalog/places")
  public Object places(
    @RequestParam(defaultValue = "서울") String region,
    @RequestParam(defaultValue = "") String query,
    @RequestParam(defaultValue = "전체") String category
  ) {
    return catalog.search(region, query, category);
  }
}
