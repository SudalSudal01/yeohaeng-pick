package kr.yeohaengpick.community;

import jakarta.validation.constraints.*;
import java.util.*;
import kr.yeohaengpick.common.DomainException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 공개 검색만 담당합니다. 휴지통·운영진 숨김은 건수에도 포함하지 않으며 개인 정보는 조회하지 않습니다. */
@Service
public class CommunitySearchService {

  private final CommunitySearchMapper mapper;

  public CommunitySearchService(CommunitySearchMapper mapper) {
    this.mapper = mapper;
  }

  /** URL을 직접 수정해도 길이·페이지 범위를 검사합니다. 빈 값은 전체 조건, 페이지 번호는 1부터입니다. */
  public record Query(
    @Size(max = 100) String q,
    @Size(max = 50) String region,
    @Size(max = 30) String category,
    @Size(max = 20) String status,
    @Min(1) @Max(1000000) Integer page,
    @Min(1) @Max(30) Integer pageSize
  ) {
    public Query {
      q = q == null ? "" : q.strip();
      region = region == null ? "" : region.strip();
      category = category == null ? "" : category.strip();
      status = status == null ? "" : status.strip();
      page = page == null ? 1 : page;
      pageSize = pageSize == null ? 12 : pageSize;
    }
  }

  public record Page(
    List<Map<String, Object>> items,
    long total,
    int page,
    int pageSize,
    long totalPages
  ) {}

  /**
   * 검색은 최신 공개 목록을 읽는 두 쿼리(건수·한 페이지)입니다. 글쓰기나 삭제를 막는 잠금은 걸지 않습니다.
   * 마지막 페이지가 사라졌을 때 큰 OFFSET으로 빈 목록을 남기지 않도록 현재 마지막 페이지로 보정합니다.
   * 동시 등록/삭제 사이의 완전한 스냅샷은 아니므로 새로고침 시 건수와 페이지 구성은 바뀔 수 있습니다.
   */
  @Transactional(readOnly = true)
  public Page search(Query query) {
    if (
      !List.of("", "후기", "추천", "코스", "동행", "의견").contains(
        query.category()
      )
    ) {
      throw DomainException.bad("이야기 종류를 다시 선택해 주세요.");
    }
    if (
      !List.of("", "OPEN", "CLOSED", "CANCELLED").contains(query.status()) ||
      (!query.status().isEmpty() && !query.category().equals("동행"))
    ) {
      throw DomainException.bad("모집 상태는 동행 찾기에서 선택해 주세요.");
    }
    var params = new HashMap<String, Object>();
    params.put("category", query.category());
    params.put("status", query.status());
    params.put("q", literalPattern(query.q()));
    params.put("region", literalPattern(query.region()));
    params.put("hasQuery", !query.q().isEmpty());
    params.put("hasRegion", !query.region().isEmpty());
    params.put("pageSize", query.pageSize());
    long total = mapper.count(params);
    long totalPages = (total + query.pageSize() - 1) / query.pageSize();
    int page = (int) Math.min(query.page(), Math.max(1, totalPages));
    params.put("offset", (long) (page - 1) * query.pageSize());
    var items =
      total == 0 ? List.<Map<String, Object>>of() : mapper.page(params);
    for (var item : items) {
      String preview = Objects.toString(item.get("excerpt"), "");
      // H2의 SUBSTR는 UTF-16 코드 단위 경계에서 이모지를 자를 수 있습니다. 마지막 반쪽만 제외해 깨진 문자를 보내지 않습니다.
      if (
        !preview.isEmpty() &&
        Character.isHighSurrogate(preview.charAt(preview.length() - 1))
      ) {
        preview = preview.substring(0, preview.length() - 1);
      }
      item.put("excerpt", preview);
    }
    return new Page(items, total, page, query.pageSize(), totalPages);
  }

  /** %, _, !도 사용자가 입력한 글자로 찾습니다. SQL 구조는 고정하고 패턴 전체를 바인딩합니다. */
  private String literalPattern(String value) {
    return (
      "%" +
      value
        .toLowerCase(Locale.ROOT)
        .replace("!", "!!")
        .replace("%", "!%")
        .replace("_", "!_") +
      "%"
    );
  }
}
