package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import kr.yeohaengpick.auth.AuthService;
import kr.yeohaengpick.common.JsonCodec;
import kr.yeohaengpick.community.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/** 공개 GET을 실제 HTTP로 실행합니다. 실사용 스키마 대신 H2/Oracle QA의 허구 글만 만들고 정리합니다. */
@SpringBootTest(
  webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
  properties = {
    "spring.datasource.url=jdbc:h2:mem:flows;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.sample-data=true",
    "app.kakao-rest-key=",
    "app.gemini-key=",
    "app.mail-enabled=false",
    "app.tour-api-key=",
  }
)
class CommunitySearchFlowTest extends HttpFlowDatabase {

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  AuthService auth;

  @Autowired
  CommunityPostService posts;

  @Autowired
  JdbcTemplate jdbc;

  final HttpClient visitor = HttpClient.newHttpClient();
  final List<String> created = new ArrayList<>();
  String owner;
  String marker;

  @BeforeEach
  void fixture() {
    marker = "검색검증" + UUID.randomUUID().toString().substring(0, 8);
    owner = auth
      .register(
        UUID.randomUUID() + "@example.test",
        "Search-Test-Only12!",
        "검색 검증 작성자"
      )
      .get("id")
      .toString();
  }

  @AfterEach
  void cleanup() {
    // 테스트가 실패해도 자신이 만든 글만 제거합니다. 다른 테스트/실사용 데이터를 일괄 초기화하지 않습니다.
    for (String id : created) jdbc.update("DELETE FROM posts WHERE id=?", id);
    if (owner != null) jdbc.update("DELETE FROM members WHERE id=?", owner);
  }

  @SuppressWarnings("unchecked")
  String seed(String category, String region, String title, String body) {
    var post = (Map<String, Object>) posts.create(
      new CommunityController.Post(
        category,
        region,
        title,
        body,
        category.equals("동행") ? LocalDate.of(2026, 11, 9) : null,
        category.equals("동행") ? 2 : null
      ),
      owner
    );
    String id = post.get("id").toString();
    created.add(id);
    return id;
  }

  HttpResponse<String> get(Map<String, String> params) throws Exception {
    String query = params
      .entrySet()
      .stream()
      .map(
        e ->
          e.getKey() +
          "=" +
          URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)
      )
      .collect(java.util.stream.Collectors.joining("&"));
    return visitor.send(
      HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/api/community/posts?" + query)
      )
        .GET()
        .build(),
      HttpResponse.BodyHandlers.ofString()
    );
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> page(Map<String, String> params) throws Exception {
    var response = get(params);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json.read(response.body(), Map.class);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> items(Map<String, Object> result) {
    return (List<Map<String, Object>>) result.get("items");
  }

  @Test
  void pagesReachOlderThan100AndTieOrderIsStableWithoutPrivateFields()
    throws Exception {
    var visible = new ArrayList<String>();
    for (int i = 0; i < 105; i++) visible.add(
      seed("후기", "서울", marker + " 이야기 " + i, "공개 미리보기")
    );
    // 같은 시각의 다수 글도 정렬 키가 고정되어야 페이지를 넘길 때 중복되지 않습니다.
    for (String id : visible)
      jdbc.update(
        "UPDATE posts SET created_at=? WHERE id=?",
        Timestamp.valueOf("2026-01-01 12:00:00"),
        id
      );
    String hidden = seed("후기", "서울", marker + " 운영 숨김", "숨긴 본문");
    posts.hide(hidden, true);
    String deleted = seed(
      "후기",
      "서울",
      marker + " 작성자 삭제",
      "삭제한 본문"
    );
    posts.delete(deleted, 0, owner);
    var actual = new ArrayList<String>();
    for (int number = 1; number <= 9; number++) {
      var result = page(Map.of("q", marker, "page", String.valueOf(number)));
      assertThat(((Number) result.get("total")).longValue()).isEqualTo(105);
      assertThat(result.get("page")).isEqualTo(number);
      assertThat(((Number) result.get("totalPages")).intValue()).isEqualTo(9);
      assertThat(items(result)).hasSize(number == 9 ? 9 : 12);
      for (var item : items(result)) {
        assertThat(item).containsOnlyKeys(
          "id",
          "category",
          "region",
          "title",
          "excerpt",
          "nickname",
          "createdAt",
          "recruitmentDate",
          "capacity",
          "status"
        );
        actual.add(item.get("id").toString());
      }
    }
    visible.sort(Comparator.reverseOrder());
    assertThat(actual).containsExactlyElementsOf(visible);
    assertThat(items(page(Map.of("q", marker))))
      .extracting(p -> p.get("id").toString())
      .containsExactlyElementsOf(visible.subList(0, 12));
    var clamped = page(Map.of("q", marker, "page", "1000000"));
    assertThat(clamped.get("page")).isEqualTo(9);
    assertThat(items(clamped)).hasSize(9);
  }

  @Test
  void combinedCategoryRegionAndRecruitmentStatusHaveMatchingCounts()
    throws Exception {
    String open = seed("동행", "부산 해운대", marker + " 함께", "해변과 전시");
    String closed = seed(
      "동행",
      "부산 해운대",
      marker + " 마감",
      "해변과 전시"
    );
    posts.status(closed, "CLOSED", 0, owner);
    String cancelled = seed(
      "동행",
      "부산 해운대",
      marker + " 취소",
      "해변과 전시"
    );
    posts.status(cancelled, "CANCELLED", 0, owner);
    seed("동행", "서울", marker + " 서울", "다른 지역");
    seed("후기", "부산 해운대", marker + " 후기", "다른 종류");
    for (var entry : Map.of(
      "OPEN",
      open,
      "CLOSED",
      closed,
      "CANCELLED",
      cancelled
    ).entrySet()) {
      var result = page(
        Map.of(
          "q",
          marker,
          "category",
          "동행",
          "region",
          "해운대",
          "status",
          entry.getKey()
        )
      );
      assertThat(((Number) result.get("total")).intValue()).isEqualTo(1);
      assertThat(items(result))
        .extracting(p -> p.get("id"))
        .containsExactly(entry.getValue());
      assertThat(items(result).getFirst().get("recruitmentDate")).isEqualTo(
        "2026-11-09"
      );
    }
    assertThat(
      items(page(Map.of("q", marker, "category", "동행", "region", "부산")))
    ).hasSize(3);
    assertThat(
      items(page(Map.of("q", marker, "category", "후기", "region", "부산")))
    ).hasSize(1);
  }

  @Test
  void fullClobSearchFindsTailUnicodeAndTreatsWildcardCharactersLiterally()
    throws Exception {
    String tail = marker + "한글🚶 50%_할인! O'REILLY";
    String longBody = "가".repeat(8191) + "🚶" + "나".repeat(500) + tail;
    String matching = seed("후기", "부산_100%!", "본문 검색 테스트", longBody);
    seed("후기", "부산A100X!", "비슷한 글", tail.replace("%_", "AB"));
    var result = page(
      Map.of("q", tail.toLowerCase(Locale.ROOT), "region", "_100%!")
    );
    assertThat(items(result))
      .extracting(p -> p.get("id"))
      .containsExactly(matching);
    assertThat(items(result).getFirst().get("excerpt").toString())
      .hasSize(280)
      .isEqualTo("가".repeat(280));
    assertThat(items(page(Map.of("q", marker + "%' OR 1=1 --")))).isEmpty();
    // 미리보기 경계의 이모지도 절반으로 깨져 내려오지 않아야 합니다.
    String boundary = seed(
      "추천",
      "서울",
      marker + " 경계",
      "가".repeat(279) + "🚶" + "뒷부분"
    );
    String excerpt = items(page(Map.of("q", marker + " 경계")))
      .getFirst()
      .get("excerpt")
      .toString();
    assertThat(excerpt).doesNotContain("\uFFFD");
    assertThat(
      Character.isHighSurrogate(excerpt.charAt(excerpt.length() - 1))
    ).isFalse();
    assertThat(boundary).isNotBlank();
  }

  @Test
  void emptyAndRemovedLastPageReturnUsableMetadata() throws Exception {
    var empty = page(Map.of("q", marker, "page", "20"));
    assertThat(empty.get("page")).isEqualTo(1);
    assertThat(((Number) empty.get("totalPages")).intValue()).isZero();
    assertThat(items(empty)).isEmpty();
    String id = seed("코스", "제주", marker, "하루 코스");
    var one = page(Map.of("q", marker, "pageSize", "1", "page", "2"));
    assertThat(one.get("page")).isEqualTo(1);
    posts.delete(id, 0, owner);
    assertThat(items(page(Map.of("q", marker, "pageSize", "1")))).isEmpty();
  }

  @Test
  void invalidSearchParametersAreClientErrorsRatherThanSqlFailures()
    throws Exception {
    for (var params : List.of(
      Map.of("page", "0"),
      Map.of("page", "-1"),
      Map.of("page", "abc"),
      Map.of("page", "1.5"),
      Map.of("page", "1000001"),
      Map.of("pageSize", "0"),
      Map.of("pageSize", "31"),
      Map.of("q", "가".repeat(101)),
      Map.of("region", "가".repeat(51)),
      Map.of("category", "없는종류"),
      Map.of("status", "OPEN"),
      Map.of("category", "후기", "status", "OPEN"),
      Map.of("category", "동행", "status", "UNKNOWN")
    ))
      assertThat(get(params).statusCode()).as(params.toString()).isEqualTo(400);
    assertThat(get(Map.of("q", "   ", "region", "   ")).statusCode()).isEqualTo(
      200
    );
  }
}
