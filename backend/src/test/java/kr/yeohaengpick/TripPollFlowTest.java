package kr.yeohaengpick;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import kr.yeohaengpick.auth.AuthRateLimit;
import kr.yeohaengpick.common.JsonCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 여행 투표가 실제 HTTP·세션·CSRF·DB 경계를 모두 지키는지 H2와 Oracle에서 같은 흐름으로 검증합니다.
 * 다른 참여자의 개별 선택은 응답에서 숨기되, 현재 참여자의 표만 집계하고 참여가 끝나면 즉시 제거해야 합니다.
 */
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
class TripPollFlowTest extends HttpFlowDatabase {

  private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
  private static final String PASSWORD = "Poll-Test-Only12!";

  @LocalServerPort
  int port;

  @Autowired
  JsonCodec json;

  @Autowired
  JdbcTemplate db;

  @Autowired
  AuthRateLimit authRateLimit;

  @BeforeEach
  void isolateAuthenticationBudget() {
    // 모든 브라우저가 같은 루프백 IP를 쓰므로 이전 시나리오의 가입 횟수가 다음 시나리오를 막지 않게 합니다.
    (
      (Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(
        authRateLimit,
        "attempts"
      )
    ).clear();
  }

  Map<String, Object> createRequest(
    String title,
    String selectionType,
    String... labels
  ) {
    return Map.of(
      "title",
      title,
      "selectionType",
      selectionType,
      "options",
      Arrays.stream(labels)
        .map(label -> Map.of("label", label))
        .toList()
    );
  }

  Map<String, Object> create(
    Browser member,
    String trip,
    String selectionType,
    String... labels
  ) throws Exception {
    return member.call(
      "POST",
      "/trips/" + trip + "/polls",
      createRequest("함께 정할 선택", selectionType, labels)
    );
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> options(Map<String, Object> poll) {
    return (List<Map<String, Object>>) poll.get("options");
  }

  String option(Map<String, Object> poll, String label) {
    return options(poll)
      .stream()
      .filter(candidate -> label.equals(candidate.get("label")))
      .findFirst()
      .orElseThrow()
      .get("id")
      .toString();
  }

  Map<String, Object> poll(Browser member, String trip, String poll)
    throws Exception {
    return member
      .list("/trips/" + trip + "/polls")
      .stream()
      .filter(candidate -> poll.equals(candidate.get("id")))
      .findFirst()
      .orElseThrow();
  }

  Map<String, Integer> counts(Map<String, Object> poll) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (var option : options(poll))
      result.put(
        option.get("label").toString(),
        ((Number) option.get("voteCount")).intValue()
      );
    return result;
  }

  long selectedCount(Map<String, Object> poll) {
    return options(poll)
      .stream()
      .filter(option -> Boolean.TRUE.equals(option.get("selectedByMe")))
      .count();
  }

  String join(Browser owner, Browser companion, String trip) throws Exception {
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip, "email", companion.email)
    );
    var invitations = companion.list("/invitations");
    String invitation = invitations.getFirst().get("id").toString();
    companion.call(
      "POST",
      "/invitations/" + invitation + "/respond",
      Map.of("accept", true)
    );
    return invitation;
  }

  int tripVersion(Browser member, String trip) throws Exception {
    return (
      (Number) member.call("GET", "/trips/" + trip, null).get("version")
    ).intValue();
  }

  void assertStatus(
    Browser member,
    String method,
    String path,
    Object body,
    int expected
  ) throws Exception {
    var response = member.request(method, path, body, !method.equals("GET"));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
  }

  @Test
  void sessionCsrfAndInvitationAcceptanceProtectEveryPollRoute()
    throws Exception {
    var owner = new Browser().signup();
    var outsider = new Browser().signup();
    var visitor = new Browser();
    String trip = owner.trip().get("id").toString();
    String path = "/trips/" + trip + "/polls";

    assertStatus(visitor, "GET", path, null, 401);
    assertThat(
      owner
        .request(
          "POST",
          path,
          createRequest("CSRF 확인", "SINGLE", "A", "B"),
          false
        )
        .statusCode()
    ).isEqualTo(403);
    assertStatus(outsider, "GET", path, null, 403);
    assertStatus(
      outsider,
      "POST",
      path,
      createRequest("외부인 질문", "SINGLE", "A", "B"),
      403
    );

    // 초대장을 받은 것만으로는 비공개 투표를 볼 수 없고, 직접 수락한 뒤부터 같은 권한을 얻습니다.
    owner.call(
      "POST",
      "/invitations",
      Map.of("tripId", trip, "email", outsider.email)
    );
    assertStatus(outsider, "GET", path, null, 403);
    var invitation = outsider.list("/invitations").getFirst();
    outsider.call(
      "POST",
      "/invitations/" + invitation.get("id") + "/respond",
      Map.of("accept", true)
    );
    assertThat(outsider.list(path)).isEmpty();
    var created = create(outsider, trip, "SINGLE", "A", "B");
    assertThat(created)
      .containsEntry("canManage", true)
      .doesNotContainKey("creatorId");
  }

  @Test
  void creationValidatesQuestionSelectionOptionsDeadlineAndFiftyPollLimit()
    throws Exception {
    var owner = new Browser().signup();
    String trip = owner.trip().get("id").toString();
    String path = "/trips/" + trip + "/polls";

    for (Object invalid : List.of(
      createRequest(" ", "SINGLE", "A", "B"),
      createRequest("방식 오류", "PAIR", "A", "B"),
      createRequest("후보 부족", "SINGLE", "A"),
      createRequest(
        "후보 초과",
        "MULTIPLE",
        "1",
        "2",
        "3",
        "4",
        "5",
        "6",
        "7",
        "8",
        "9",
        "10",
        "11"
      ),
      createRequest("중복 후보", "MULTIPLE", " 바다 ", "바다"),
      createRequest("대소문자 중복", "MULTIPLE", "SEOUL", "seoul")
    ))
      assertStatus(owner, "POST", path, invalid, 400);

    var expired = new HashMap<>(createRequest("지난 마감", "SINGLE", "A", "B"));
    expired.put(
      "closesAt",
      LocalDateTime.now(KOREA).minusMinutes(1).withNano(0).toString()
    );
    assertStatus(owner, "POST", path, expired, 400);
    var outsideOracleRange = new HashMap<>(
      createRequest("DB 범위 밖 마감", "SINGLE", "A", "B")
    );
    outsideOracleRange.put("closesAt", "+10000-01-01T00:00:00");
    assertStatus(owner, "POST", path, outsideOracleRange, 400);

    var scheduled = new HashMap<>(
      createRequest("내일 마감", "SINGLE", "A", "B")
    );
    scheduled.put(
      "closesAt",
      LocalDateTime.now(KOREA).plusDays(1).withNano(0).toString()
    );
    assertThat(owner.call("POST", path, scheduled).get("status")).isEqualTo(
      "OPEN"
    );

    // 상한 검증은 HTTP 경계를 보되 준비 데이터는 바인딩 SQL로 넣어 H2·Oracle에서 빠르고 동일하게 만듭니다.
    owner.call("DELETE", "/trips/" + trip, null);
    trip = owner.trip().get("id").toString();
    path = "/trips/" + trip + "/polls";
    for (int index = 0; index < 50; index++) db.update(
      "INSERT INTO trip_polls(id,trip_id,created_by,title,selection_type) VALUES(?,?,?,?,?)",
      UUID.randomUUID().toString(),
      trip,
      owner.id,
      "상한 준비 " + index,
      "SINGLE"
    );
    assertStatus(
      owner,
      "POST",
      path,
      createRequest("쉰한 번째", "SINGLE", "A", "B"),
      409
    );
    owner.call("DELETE", "/trips/" + trip, null);
  }

  @Test
  void aggregateShowsCountsAndOnlyTheRequestingMembersOwnSelection()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    var observer = new Browser().signup();
    String trip = owner.trip().get("id").toString();
    join(owner, companion, trip);
    join(owner, observer, trip);
    var created = create(owner, trip, "MULTIPLE", "한옥", "바다", "미술관");
    String poll = created.get("id").toString();
    String hanok = option(created, "한옥");
    String sea = option(created, "바다");
    String museum = option(created, "미술관");

    owner.call(
      "PUT",
      "/trips/" + trip + "/polls/" + poll + "/ballot",
      Map.of("optionIds", List.of(hanok, museum))
    );
    companion.call(
      "PUT",
      "/trips/" + trip + "/polls/" + poll + "/ballot",
      Map.of("optionIds", List.of(sea, museum))
    );

    var ownerView = poll(owner, trip, poll);
    var companionView = poll(companion, trip, poll);
    var observerView = poll(observer, trip, poll);
    assertThat(counts(ownerView)).containsExactly(
      entry("한옥", 1),
      entry("바다", 1),
      entry("미술관", 2)
    );
    assertThat(ownerView.get("votedMemberCount")).isEqualTo(2);
    assertThat(ownerView.get("eligibleMemberCount")).isEqualTo(3);
    assertThat(selectedCount(ownerView)).isEqualTo(2);
    assertThat(selectedCount(companionView)).isEqualTo(2);
    assertThat(selectedCount(observerView)).isZero();
    assertThat(ownerView.get("canManage")).isEqualTo(true);
    assertThat(companionView.get("canManage")).isEqualTo(false);
    assertThat(observerView.get("canManage")).isEqualTo(false);
    assertThat(json.write(observerView)).doesNotContain(
      owner.id,
      companion.id,
      observer.id
    );
  }

  @Test
  void revoteReplacesTheBallotCancellationClearsItAndInvalidInputKeepsIt()
    throws Exception {
    var owner = new Browser().signup();
    String trip = owner.trip().get("id").toString();
    var single = create(owner, trip, "SINGLE", "아침", "저녁");
    var another = create(owner, trip, "MULTIPLE", "실내", "야외");
    String poll = single.get("id").toString();
    String base = "/trips/" + trip + "/polls/" + poll + "/ballot";
    String morning = option(single, "아침");
    String evening = option(single, "저녁");
    String indoor = option(another, "실내");

    owner.call("PUT", base, Map.of("optionIds", List.of(morning)));
    owner.call("PUT", base, Map.of("optionIds", List.of(evening)));
    assertThat(counts(poll(owner, trip, poll))).containsExactly(
      entry("아침", 0),
      entry("저녁", 1)
    );

    // 잘못된 재투표는 기존 표를 먼저 지우면 안 됩니다.
    for (Object invalid : List.of(
      Map.of("optionIds", List.of(evening, evening)),
      Map.of("optionIds", List.of(morning, evening)),
      Map.of("optionIds", List.of(indoor)),
      Map.of("optionIds", List.of(UUID.randomUUID().toString()))
    ))
      assertStatus(owner, "PUT", base, invalid, 400);
    assertThat(counts(poll(owner, trip, poll))).containsEntry("저녁", 1);

    owner.call("PUT", base, Map.of("optionIds", List.of()));
    var cancelled = poll(owner, trip, poll);
    assertThat(selectedCount(cancelled)).isZero();
    assertThat(cancelled.get("votedMemberCount")).isEqualTo(0);
    assertThat(counts(cancelled).values()).containsOnly(0);

    String otherTrip = owner.trip().get("id").toString();
    assertStatus(
      owner,
      "PUT",
      "/trips/" + otherTrip + "/polls/" + poll + "/ballot",
      Map.of("optionIds", List.of(morning)),
      404
    );
  }

  @Test
  void creatorAndOwnerManageLifecycleWhileOthersCannotAndExpiryIsFinal()
    throws Exception {
    var owner = new Browser().signup();
    var creator = new Browser().signup();
    var participant = new Browser().signup();
    String trip = owner.trip().get("id").toString();
    join(owner, creator, trip);
    join(owner, participant, trip);
    var created = create(creator, trip, "SINGLE", "기차", "버스");
    String poll = created.get("id").toString();
    String base = "/trips/" + trip + "/polls/" + poll;
    String train = option(created, "기차");

    assertThat(poll(creator, trip, poll).get("canManage")).isEqualTo(true);
    assertThat(poll(owner, trip, poll).get("canManage")).isEqualTo(true);
    var creatorManaged = create(
      creator,
      trip,
      "SINGLE",
      "직접 삭제 A",
      "직접 삭제 B"
    );
    assertStatus(
      creator,
      "DELETE",
      "/trips/" + trip + "/polls/" + creatorManaged.get("id"),
      Map.of("version", 0),
      204
    );
    assertStatus(
      participant,
      "POST",
      base + "/close",
      Map.of("version", 0),
      403
    );
    assertStatus(participant, "DELETE", base, Map.of("version", 0), 403);
    assertStatus(creator, "POST", base + "/close", Map.of("version", 1), 409);

    var closed = owner.call("POST", base + "/close", Map.of("version", 0));
    assertThat(closed.get("status")).isEqualTo("CLOSED");
    assertThat(closed.get("version")).isEqualTo(1);
    assertThat(closed.get("closedAt")).isNotNull();
    assertStatus(
      creator,
      "PUT",
      base + "/ballot",
      Map.of("optionIds", List.of(train)),
      409
    );
    assertStatus(owner, "POST", base + "/close", Map.of("version", 1), 409);
    assertStatus(owner, "DELETE", base, Map.of("version", 0), 409);
    assertStatus(owner, "DELETE", base, Map.of("version", 1), 204);
    assertThat(owner.list("/trips/" + trip + "/polls")).isEmpty();

    var expires = create(creator, trip, "SINGLE", "오전", "오후");
    String expiredPoll = expires.get("id").toString();
    db.update(
      "UPDATE trip_polls SET closes_at=? WHERE id=?",
      Timestamp.valueOf(LocalDateTime.now(KOREA).minusMinutes(1)),
      expiredPoll
    );
    var expired = poll(owner, trip, expiredPoll);
    assertThat(expired.get("status")).isEqualTo("EXPIRED");
    assertThat(expired.get("version")).isEqualTo(0);
    assertStatus(
      creator,
      "PUT",
      "/trips/" + trip + "/polls/" + expiredPoll + "/ballot",
      Map.of("optionIds", List.of(option(expires, "오전"))),
      409
    );
    assertStatus(
      owner,
      "POST",
      "/trips/" + trip + "/polls/" + expiredPoll + "/close",
      Map.of("version", 0),
      409
    );
  }

  @Test
  void concurrentDifferentMembersArePreservedAndSameMemberKeepsOneSingleChoice()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    String trip = owner.trip().get("id").toString();
    join(owner, companion, trip);
    var multiple = create(owner, trip, "MULTIPLE", "산", "바다");
    String multipleId = multiple.get("id").toString();
    String mountain = option(multiple, "산");
    String sea = option(multiple, "바다");
    String base = "/trips/" + trip + "/polls/" + multipleId + "/ballot";
    var differentMemberGate = new CyclicBarrier(2);

    var first = CompletableFuture.supplyAsync(() ->
      voteAtGate(differentMemberGate, owner, base, List.of(mountain))
    );
    var second = CompletableFuture.supplyAsync(() ->
      voteAtGate(differentMemberGate, companion, base, List.of(sea))
    );
    assertThat(
      List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))
    ).containsOnly(200);
    assertThat(counts(poll(owner, trip, multipleId))).containsExactly(
      entry("산", 1),
      entry("바다", 1)
    );

    // 같은 회원의 두 창도 각각의 HTTP 세션을 사용합니다. 마지막으로 직렬화된 요청 하나만 남아야 합니다.
    var sameMemberOtherWindow = new Browser(owner.email).login();
    var single = create(owner, trip, "SINGLE", "빨강", "파랑");
    String singleId = single.get("id").toString();
    String red = option(single, "빨강");
    String blue = option(single, "파랑");
    String singleBase = "/trips/" + trip + "/polls/" + singleId + "/ballot";
    var sameMemberGate = new CyclicBarrier(2);
    var redVote = CompletableFuture.supplyAsync(() ->
      voteAtGate(sameMemberGate, owner, singleBase, List.of(red))
    );
    var blueVote = CompletableFuture.supplyAsync(() ->
      voteAtGate(
        sameMemberGate,
        sameMemberOtherWindow,
        singleBase,
        List.of(blue)
      )
    );
    assertThat(
      List.of(
        redVote.get(20, TimeUnit.SECONDS),
        blueVote.get(20, TimeUnit.SECONDS)
      )
    ).containsOnly(200);
    var finalSingle = poll(owner, trip, singleId);
    assertThat(selectedCount(finalSingle)).isEqualTo(1);
    assertThat(
      counts(finalSingle).values().stream().mapToInt(Integer::intValue).sum()
    ).isEqualTo(1);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_poll_votes WHERE poll_id=? AND member_id=?",
        Integer.class,
        singleId,
        owner.id
      )
    ).isEqualTo(1);
  }

  int voteAtGate(
    CyclicBarrier gate,
    Browser member,
    String path,
    List<String> options
  ) {
    try {
      gate.await();
      return member
        .request("PUT", path, Map.of("optionIds", options), true)
        .statusCode();
    } catch (Exception exception) {
      throw new RuntimeException(exception);
    }
  }

  @Test
  void ballotAndCloseRaceCannotRecordAChoiceAfterThePollIsClosed()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    String trip = owner.trip().get("id").toString();
    join(owner, companion, trip);
    var created = create(owner, trip, "SINGLE", "동쪽", "서쪽");
    String poll = created.get("id").toString();
    String base = "/trips/" + trip + "/polls/" + poll;
    var gate = new CyclicBarrier(2);
    var ballot = CompletableFuture.supplyAsync(() ->
      voteAtGate(
        gate,
        companion,
        base + "/ballot",
        List.of(option(created, "동쪽"))
      )
    );
    var close = CompletableFuture.supplyAsync(() -> {
      try {
        gate.await();
        return owner
          .request("POST", base + "/close", Map.of("version", 0), true)
          .statusCode();
      } catch (Exception exception) {
        throw new RuntimeException(exception);
      }
    });
    int ballotStatus = ballot.get(20, TimeUnit.SECONDS);
    assertThat(close.get(20, TimeUnit.SECONDS)).isEqualTo(200);
    assertThat(ballotStatus).isIn(200, 409);

    var closed = poll(owner, trip, poll);
    assertThat(closed.get("status")).isEqualTo("CLOSED");
    assertThat(closed.get("votedMemberCount")).isEqualTo(
      ballotStatus == 200 ? 1 : 0
    );
    assertStatus(
      companion,
      "PUT",
      base + "/ballot",
      Map.of("optionIds", List.of(option(created, "서쪽"))),
      409
    );
  }

  @Test
  void leavingDeletesOnlyThatMembersVotesAndReinviteDoesNotReviveThem()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    String trip = owner.trip().get("id").toString();
    String oldInvitation = join(owner, companion, trip);
    var created = create(owner, trip, "SINGLE", "서울", "부산");
    String poll = created.get("id").toString();
    companion.call(
      "PUT",
      "/trips/" + trip + "/polls/" + poll + "/ballot",
      Map.of("optionIds", List.of(option(created, "서울")))
    );

    companion.call(
      "POST",
      "/trips/" + trip + "/leave",
      Map.of("version", tripVersion(owner, trip))
    );
    var afterLeave = poll(owner, trip, poll);
    assertThat(afterLeave.get("eligibleMemberCount")).isEqualTo(1);
    assertThat(afterLeave.get("votedMemberCount")).isEqualTo(0);
    assertThat(counts(afterLeave).values()).containsOnly(0);
    assertStatus(companion, "GET", "/trips/" + trip + "/polls", null, 403);
    assertStatus(
      companion,
      "POST",
      "/invitations/" + oldInvitation + "/respond",
      Map.of("accept", true),
      409
    );

    String newInvitation = join(owner, companion, trip);
    assertThat(newInvitation).isNotEqualTo(oldInvitation);
    var afterReturn = poll(companion, trip, poll);
    assertThat(selectedCount(afterReturn)).isZero();
    assertThat(afterReturn.get("votedMemberCount")).isEqualTo(0);
  }

  @Test
  void removalDeletesVotesAndADepartedCreatorLeavesTheSharedPollBehind()
    throws Exception {
    var owner = new Browser().signup();
    var companion = new Browser().signup();
    String trip = owner.trip().get("id").toString();
    join(owner, companion, trip);
    var created = create(companion, trip, "MULTIPLE", "시장", "공원");
    String poll = created.get("id").toString();
    String market = option(created, "시장");
    String park = option(created, "공원");
    owner.call(
      "PUT",
      "/trips/" + trip + "/polls/" + poll + "/ballot",
      Map.of("optionIds", List.of(market))
    );
    companion.call(
      "PUT",
      "/trips/" + trip + "/polls/" + poll + "/ballot",
      Map.of("optionIds", List.of(park))
    );

    owner.call(
      "POST",
      "/trips/" + trip + "/members/" + companion.id + "/remove",
      Map.of("version", tripVersion(owner, trip))
    );
    var remaining = poll(owner, trip, poll);
    assertThat(remaining).doesNotContainKey("creatorId");
    assertThat(remaining.get("eligibleMemberCount")).isEqualTo(1);
    assertThat(remaining.get("votedMemberCount")).isEqualTo(1);
    assertThat(counts(remaining)).containsExactly(
      entry("시장", 1),
      entry("공원", 0)
    );
    assertThat(remaining.get("canManage")).isEqualTo(true);
    assertStatus(companion, "GET", "/trips/" + trip + "/polls", null, 403);

    // 제거된 생성자를 새 초대로 다시 받아도 과거 표는 복구되지 않습니다.
    join(owner, companion, trip);
    var returned = poll(companion, trip, poll);
    assertThat(returned.get("canManage")).isEqualTo(true);
    assertThat(selectedCount(returned)).isZero();
    assertThat(returned.get("votedMemberCount")).isEqualTo(1);
  }

  @Test
  void withdrawalDeletesTheDepartedVoteButKeepsTheirSharedPollAndPeerVote()
    throws Exception {
    var owner = new Browser().signup();
    var departing = new Browser().signup();
    String trip = owner.trip().get("id").toString();
    join(owner, departing, trip);
    var created = create(departing, trip, "MULTIPLE", "한옥", "시장");
    String poll = created.get("id").toString();
    owner.call(
      "PUT",
      "/trips/" + trip + "/polls/" + poll + "/ballot",
      Map.of("optionIds", List.of(option(created, "한옥")))
    );
    departing.call(
      "PUT",
      "/trips/" + trip + "/polls/" + poll + "/ballot",
      Map.of("optionIds", List.of(option(created, "시장")))
    );

    assertThat(
      departing
        .request(
          "DELETE",
          "/auth/account",
          Map.of("password", PASSWORD, "confirmed", true),
          true
        )
        .statusCode()
    ).isEqualTo(204);

    var remaining = poll(owner, trip, poll);
    assertThat(remaining)
      .doesNotContainKey("creatorId")
      .containsEntry("creatorName", "탈퇴한 회원")
      .containsEntry("eligibleMemberCount", 1)
      .containsEntry("votedMemberCount", 1)
      .containsEntry("canManage", true);
    assertThat(counts(remaining)).containsExactly(
      entry("한옥", 1),
      entry("시장", 0)
    );
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_poll_votes WHERE member_id=?",
        Integer.class,
        departing.id
      )
    ).isZero();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_members WHERE trip_id=? AND member_id=?",
        Integer.class,
        trip,
        departing.id
      )
    ).isZero();
    assertThat(
      db.queryForObject(
        "SELECT created_by FROM trip_polls WHERE id=?",
        String.class,
        poll
      )
    ).isEqualTo(departing.id);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM members WHERE id=? AND deleted_at IS NOT NULL",
        Integer.class,
        departing.id
      )
    ).isEqualTo(1);

    // 익명 작성자 FK를 유지한 투표도 현재 소유자가 정상적으로 삭제할 수 있어야 합니다.
    assertStatus(
      owner,
      "DELETE",
      "/trips/" + trip + "/polls/" + poll,
      Map.of("version", remaining.get("version")),
      204
    );
    assertPollRows(poll, 0, 0, 0);
  }

  @Test
  void ballotAndWithdrawalRaceCannotLeaveAWithdrawnMembersVoteBehind()
    throws Exception {
    var owner = new Browser().signup();
    var departing = new Browser().signup();
    var secondWindow = new Browser(departing.email).login();
    String trip = owner.trip().get("id").toString();
    join(owner, departing, trip);
    var created = create(owner, trip, "SINGLE", "동쪽", "서쪽");
    String poll = created.get("id").toString();
    String ballotPath = "/trips/" + trip + "/polls/" + poll + "/ballot";
    String ballotCsrf = secondWindow.csrf();
    String withdrawalCsrf = departing.csrf();

    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new CyclicBarrier(2);
      var ballot = pool.submit(() -> {
        gate.await();
        return secondWindow
          .requestWithToken(
            "PUT",
            ballotPath,
            Map.of("optionIds", List.of(option(created, "동쪽"))),
            ballotCsrf
          )
          .statusCode();
      });
      var withdrawal = pool.submit(() -> {
        gate.await();
        return departing
          .requestWithToken(
            "DELETE",
            "/auth/account",
            Map.of("password", PASSWORD, "confirmed", true),
            withdrawalCsrf
          )
          .statusCode();
      });
      assertThat(withdrawal.get(20, TimeUnit.SECONDS)).isEqualTo(204);
      assertThat(ballot.get(20, TimeUnit.SECONDS)).isIn(200, 401);
    }

    var remaining = poll(owner, trip, poll);
    assertThat(remaining.get("eligibleMemberCount")).isEqualTo(1);
    assertThat(remaining.get("votedMemberCount")).isEqualTo(0);
    assertThat(counts(remaining).values()).containsOnly(0);
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_poll_votes WHERE member_id=?",
        Integer.class,
        departing.id
      )
    ).isZero();
    assertThat(
      db.queryForObject(
        "SELECT COUNT(*) FROM trip_members WHERE member_id=?",
        Integer.class,
        departing.id
      )
    ).isZero();
    assertStatus(secondWindow, "GET", "/auth/me", null, 401);
  }

  @Test
  void deletingPollAndTripCascadesOptionsAndVotesWithoutOrphans()
    throws Exception {
    var owner = new Browser().signup();
    String trip = owner.trip().get("id").toString();
    var created = create(owner, trip, "MULTIPLE", "봄", "가을");
    String poll = created.get("id").toString();
    owner.call(
      "PUT",
      "/trips/" + trip + "/polls/" + poll + "/ballot",
      Map.of("optionIds", List.of(option(created, "봄")))
    );
    assertStatus(
      owner,
      "DELETE",
      "/trips/" + trip + "/polls/" + poll,
      Map.of("version", 0),
      204
    );
    assertPollRows(poll, 0, 0, 0);

    var tripPoll = create(owner, trip, "SINGLE", "남쪽", "북쪽");
    String cascaded = tripPoll.get("id").toString();
    owner.call(
      "PUT",
      "/trips/" + trip + "/polls/" + cascaded + "/ballot",
      Map.of("optionIds", List.of(option(tripPoll, "남쪽")))
    );
    assertPollRows(cascaded, 1, 2, 1);
    owner.call("DELETE", "/trips/" + trip, null);
    assertPollRows(cascaded, 0, 0, 0);
    assertStatus(owner, "GET", "/trips/" + trip + "/polls", null, 404);
  }

  void assertPollRows(String poll, int polls, int options, int votes) {
    assertThat(count("trip_polls", "id", poll)).isEqualTo(polls);
    assertThat(count("trip_poll_options", "poll_id", poll)).isEqualTo(options);
    assertThat(count("trip_poll_votes", "poll_id", poll)).isEqualTo(votes);
  }

  int count(String table, String column, String id) {
    // 테이블과 컬럼은 테스트 코드의 고정 문자열만 전달하고 값은 항상 바인딩해 Oracle에서도 안전하게 실행합니다.
    return db.queryForObject(
      "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?",
      Integer.class,
      id
    );
  }

  class Browser {

    final String email;
    String id;
    final HttpClient client = HttpClient.newBuilder()
      .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
      .build();

    Browser() {
      this(UUID.randomUUID() + "@example.test");
    }

    Browser(String email) {
      this.email = email;
    }

    HttpResponse<String> request(
      String method,
      String path,
      Object body,
      boolean csrf
    ) throws Exception {
      return requestWithToken(method, path, body, csrf ? csrf() : null);
    }

    String csrf() throws Exception {
      return map(request("GET", "/auth/csrf", null, false))
        .get("token")
        .toString();
    }

    /** 미리 받은 토큰으로 실제 쓰기 요청의 시작점을 맞춰 탈퇴와의 경합을 검증합니다. */
    HttpResponse<String> requestWithToken(
      String method,
      String path,
      Object body,
      String csrf
    ) throws Exception {
      var builder = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + port + "/api" + path)
      ).header("Content-Type", "application/json");
      if (csrf != null) builder.header("X-XSRF-TOKEN", csrf);
      return client.send(
        builder
          .method(
            method,
            body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.write(body))
          )
          .build(),
        HttpResponse.BodyHandlers.ofString()
      );
    }

    Map<String, Object> call(String method, String path, Object body)
      throws Exception {
      var response = request(method, path, body, !method.equals("GET"));
      assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
      return response.body().startsWith("{") ? map(response) : Map.of();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(String path) throws Exception {
      var response = request("GET", path, null, false);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      return json.read(response.body(), List.class);
    }

    Browser signup() throws Exception {
      call(
        "POST",
        "/auth/register",
        Map.of(
          "email",
          email,
          "password",
          PASSWORD,
          "nickname",
          "투표 검증 여행자"
        )
      );
      return login();
    }

    Browser login() throws Exception {
      id = call(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", PASSWORD)
      )
        .get("id")
        .toString();
      return this;
    }

    Map<String, Object> trip() throws Exception {
      var conditions = Map.ofEntries(
        Map.entry("title", "여행 투표 검증"),
        Map.entry("regions", List.of("서울")),
        Map.entry("startDate", "2026-11-01"),
        Map.entry("endDate", "2026-11-01"),
        Map.entry("origin", "서울역"),
        Map.entry("partySize", 2),
        Map.entry("budget", 300000),
        Map.entry("transport", "TRANSIT"),
        Map.entry("preferences", List.of("관광", "전시")),
        Map.entry("lodging", "")
      );
      var response = request("POST", "/recommendations", conditions, true);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      var courses = json.mapper().readTree(response.body());
      Object items = json.read(
        courses.get(1).path("items").toString(),
        List.class
      );
      return call(
        "POST",
        "/trips",
        Map.of("conditions", conditions, "items", items)
      );
    }
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> map(HttpResponse<String> response) {
    return json.read(response.body(), Map.class);
  }
}
