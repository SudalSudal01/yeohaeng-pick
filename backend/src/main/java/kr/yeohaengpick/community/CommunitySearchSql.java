package kr.yeohaengpick.community;

/**
 * 목록과 건수의 공개·검색 조건을 한 곳에서 관리해 필터와 페이지 수가 어긋나는 것을 막습니다.
 * 사용자 문자열을 SQL에 이어 붙이지 않습니다. MyBatis의 if는 조건 유무만 고르고 값은 모두 #{}로 바인딩합니다.
 */
public final class CommunitySearchSql {

  private CommunitySearchSql() {}

  private static final String FILTERS = """
  FROM posts p JOIN members m ON p.author_id=m.id
  WHERE p.deleted_at IS NULL AND p.hidden=
  <choose><when test="_databaseId == 'oracle'">0</when><otherwise>FALSE</otherwise></choose>
  <if test="category != ''">AND p.category=#{category}</if>
  <if test="status != ''">AND p.status=#{status}</if>
  <if test="hasRegion">AND LOWER(p.region) LIKE #{region} ESCAPE '!'</if>
  <if test="hasQuery">
    AND (LOWER(p.title) LIKE #{q} ESCAPE '!' OR LOWER(p.body) LIKE #{q} ESCAPE '!')
  </if>
  """;

  public static String count() {
    return "<script>SELECT COUNT(*) " + FILTERS + "</script>";
  }

  public static String page() {
    // LOWER/LIKE는 Oracle CLOB 전체를 검색합니다. SUBSTR는 응답 미리보기에만 사용해 긴 본문 뒷부분도 찾습니다.
    // 작성 시각이 같은 글도 ID를 두 번째 정렬 키로 고정해 변경 없는 목록에서 페이지 중복/누락을 막습니다.
    return (
      "<script>SELECT p.id,p.category,p.region,p.title,SUBSTR(p.body,1,280) AS excerpt," +
      "m.nickname,p.created_at,p.recruitment_date,p.capacity,p.status " +
      FILTERS +
      " ORDER BY p.created_at DESC,p.id DESC OFFSET #{offset} ROWS FETCH NEXT #{pageSize} ROWS ONLY</script>"
    );
  }
}
