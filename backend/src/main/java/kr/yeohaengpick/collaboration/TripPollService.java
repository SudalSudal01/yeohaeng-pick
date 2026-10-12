package kr.yeohaengpick.collaboration;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import kr.yeohaengpick.common.DomainException;
import kr.yeohaengpick.trip.TripService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 여행 참여자가 함께 결정할 질문을 만들고 집계합니다.
 * 투표는 방문 일정 자체를 바꾸지 않으므로 여행 version과 분리하고, 마감·삭제만 투표 version으로 보호합니다.
 */
@Service
public class TripPollService {

  private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
  private static final int MAX_POLLS = 50;

  private final TripService trips;
  private final TripPollMapper polls;

  public TripPollService(TripService trips, TripPollMapper polls) {
    this.trips = trips;
    this.polls = polls;
  }

  public record OptionView(
    String id,
    String label,
    int sortOrder,
    int voteCount,
    boolean selectedByMe
  ) {}

  /** 다른 참여자의 개별 선택은 숨기고 후보별 합계와 로그인 회원의 선택만 반환합니다. */
  public record PollView(
    String id,
    String title,
    String selectionType,
    String status,
    LocalDateTime closesAt,
    LocalDateTime closedAt,
    int version,
    LocalDateTime createdAt,
    String creatorName,
    boolean canManage,
    int votedMemberCount,
    int eligibleMemberCount,
    List<OptionView> options
  ) {}

  @Transactional
  public List<PollView> list(String tripId, String member) {
    // 목록도 짧게 여행 행을 잠급니다. 투표·후보·표·참여 인원을 따로 읽는 사이에
    // 삭제나 참여 종료가 끼어 서로 다른 시점의 집계가 한 응답에 섞이지 않게 합니다.
    var trip = trips.access(tripId, member);
    return views(tripId, member, trip.get("ownerId").toString());
  }

  @Transactional
  public PollView create(
    String tripId,
    TripPollController.Create request,
    String member
  ) {
    var trip = trips.access(tripId, member);
    var labels = validate(request);
    // 여행 행을 먼저 잠근 상태에서 개수를 세므로 두 참여자의 동시 생성도 50개 상한을 넘지 않습니다.
    if (polls.count(tripId) >= MAX_POLLS) throw new DomainException(
      409,
      "한 여행에는 투표를 50개까지 만들 수 있어요. 끝난 투표를 정리한 뒤 다시 만들어 주세요."
    );
    String pollId = UUID.randomUUID().toString();
    polls.insert(
      pollId,
      tripId,
      member,
      request.title().trim(),
      request.selectionType(),
      request.closesAt()
    );
    for (int index = 0; index < labels.size(); index++) polls.option(
      pollId,
      UUID.randomUUID().toString(),
      labels.get(index),
      index
    );
    return view(tripId, pollId, member, trip.get("ownerId").toString());
  }

  @Transactional
  public PollView ballot(
    String tripId,
    String pollId,
    List<String> optionIds,
    String member
  ) {
    var trip = trips.access(tripId, member);
    var poll = requirePoll(tripId, pollId);
    if (!"OPEN".equals(effectiveStatus(poll, now()))) throw closed();
    if (optionIds == null) throw DomainException.bad(
      "선택할 후보 목록을 확인해 주세요."
    );
    var selected = new LinkedHashSet<>(optionIds);
    if (selected.size() != optionIds.size()) throw DomainException.bad(
      "같은 후보를 한 번만 선택해 주세요."
    );
    if (
      "SINGLE".equals(poll.get("selectionType")) && selected.size() > 1
    ) throw DomainException.bad(
      "하나만 고르는 투표예요. 후보 한 개를 선택해 주세요."
    );
    var available = polls
      .options(pollId)
      .stream()
      .map(option -> option.get("id").toString())
      .collect(java.util.stream.Collectors.toSet());
    if (!available.containsAll(selected)) throw DomainException.bad(
      "이 투표에 포함된 후보를 선택해 주세요."
    );
    // 후보 검증이 끝난 뒤 한국 현지 시각으로 한 번 더 확인합니다. 실패 전에 기존 표를 지우지 않습니다.
    if (polls.open(tripId, pollId, now()) != 1) throw closed();
    polls.clearBallot(pollId, member);
    for (String optionId : selected) polls.vote(pollId, optionId, member);
    return view(tripId, pollId, member, trip.get("ownerId").toString());
  }

  @Transactional
  public PollView close(
    String tripId,
    String pollId,
    int version,
    String member
  ) {
    var trip = trips.access(tripId, member);
    var poll = requirePoll(tripId, pollId);
    requireManager(poll, trip, member);
    requireVersion(poll, version);
    if (!"OPEN".equals(effectiveStatus(poll, now()))) throw closed();
    if (polls.close(tripId, pollId, version, now()) != 1) throw closed();
    return view(tripId, pollId, member, trip.get("ownerId").toString());
  }

  @Transactional
  public void delete(String tripId, String pollId, int version, String member) {
    var trip = trips.access(tripId, member);
    var poll = requirePoll(tripId, pollId);
    requireManager(poll, trip, member);
    requireVersion(poll, version);
    if (polls.delete(tripId, pollId, version) != 1) throw conflict();
    // 후보와 표는 FK cascade로 함께 지웁니다. 방문 일정·경비·예약·여행 version은 변경하지 않습니다.
  }

  private List<String> validate(TripPollController.Create request) {
    if (
      request.closesAt() != null && request.closesAt().getYear() > 9999
    ) throw DomainException.bad(
      "투표 마감 연도는 9999년까지 입력할 수 있어요."
    );
    if (
      request.closesAt() != null && !request.closesAt().isAfter(now())
    ) throw DomainException.bad(
      "투표 마감은 현재보다 뒤의 한국 현지 시각으로 정해 주세요."
    );
    List<String> labels = new ArrayList<>();
    Set<String> unique = new HashSet<>();
    for (var option : request.options()) {
      String label = option.label().trim();
      if (
        !unique.add(label.toLowerCase(Locale.ROOT))
      ) throw DomainException.bad(
        "같은 이름의 후보가 있어요. 서로 구분되는 이름을 입력해 주세요."
      );
      labels.add(label);
    }
    return labels;
  }

  private Map<String, Object> requirePoll(String tripId, String pollId) {
    var poll = polls.lock(tripId, pollId);
    if (poll == null) throw new DomainException(404, "투표를 찾을 수 없어요.");
    return poll;
  }

  private void requireManager(
    Map<String, Object> poll,
    Map<String, Object> trip,
    String member
  ) {
    if (
      !member.equals(poll.get("createdBy")) &&
      !member.equals(trip.get("ownerId"))
    ) throw new DomainException(
      403,
      "투표를 만든 사람이나 현재 여행 소유자만 관리할 수 있어요."
    );
  }

  private void requireVersion(Map<String, Object> poll, int version) {
    if (version < 0 || number(poll, "version") != version) throw conflict();
  }

  private DomainException conflict() {
    return new DomainException(
      409,
      "투표 상태가 변경됐어요. 최신 결과를 확인한 뒤 다시 진행해 주세요."
    );
  }

  private DomainException closed() {
    return new DomainException(
      409,
      "이미 마감된 투표예요. 현재 결과를 확인해 주세요."
    );
  }

  private PollView view(
    String tripId,
    String pollId,
    String member,
    String owner
  ) {
    return views(tripId, member, owner)
      .stream()
      .filter(poll -> poll.id().equals(pollId))
      .findFirst()
      .orElseThrow(() -> new DomainException(404, "투표를 찾을 수 없어요."));
  }

  private List<PollView> views(String tripId, String member, String owner) {
    var rows = polls.polls(tripId);
    var options = polls.optionsForTrip(tripId);
    var votes = polls.activeVotes(tripId);
    int eligible = polls.eligibleMembers(tripId);
    // 한 응답 안의 모든 투표는 같은 기준 시각으로 판정해 경계 순간에도 상태가 엇갈리지 않게 합니다.
    LocalDateTime viewedAt = now();

    Map<String, List<Map<String, Object>>> optionsByPoll = new HashMap<>();
    for (var option : options)
      optionsByPoll
        .computeIfAbsent(option.get("pollId").toString(), ignored ->
          new ArrayList<>()
        )
        .add(option);

    Map<String, Map<String, Integer>> counts = new HashMap<>();
    Map<String, Set<String>> voters = new HashMap<>();
    Map<String, Set<String>> mine = new HashMap<>();
    for (var vote : votes) {
      String poll = vote.get("pollId").toString();
      String option = vote.get("optionId").toString();
      String voter = vote.get("memberId").toString();
      counts
        .computeIfAbsent(poll, ignored -> new HashMap<>())
        .merge(option, 1, Integer::sum);
      voters.computeIfAbsent(poll, ignored -> new HashSet<>()).add(voter);
      if (member.equals(voter)) mine
        .computeIfAbsent(poll, ignored -> new HashSet<>())
        .add(option);
    }

    List<PollView> result = new ArrayList<>();
    for (var row : rows) {
      String id = row.get("id").toString();
      var pollCounts = counts.getOrDefault(id, Map.of());
      var myOptions = mine.getOrDefault(id, Set.of());
      var optionViews = optionsByPoll
        .getOrDefault(id, List.of())
        .stream()
        .map(option -> {
          String optionId = option.get("id").toString();
          return new OptionView(
            optionId,
            option.get("label").toString(),
            number(option, "sortOrder"),
            pollCounts.getOrDefault(optionId, 0),
            myOptions.contains(optionId)
          );
        })
        .toList();
      String creator = row.get("createdBy").toString();
      result.add(
        new PollView(
          id,
          row.get("title").toString(),
          row.get("selectionType").toString(),
          effectiveStatus(row, viewedAt),
          time(row.get("closesAt")),
          time(row.get("closedAt")),
          number(row, "version"),
          time(row.get("createdAt")),
          row.get("creatorName").toString(),
          member.equals(creator) || member.equals(owner),
          voters.getOrDefault(id, Set.of()).size(),
          eligible,
          optionViews
        )
      );
    }
    return List.copyOf(result);
  }

  private String effectiveStatus(
    Map<String, Object> poll,
    LocalDateTime checkedAt
  ) {
    if ("CLOSED".equals(poll.get("status"))) return "CLOSED";
    LocalDateTime closesAt = time(poll.get("closesAt"));
    return closesAt != null && !closesAt.isAfter(checkedAt)
      ? "EXPIRED"
      : "OPEN";
  }

  private LocalDateTime now() {
    return LocalDateTime.now(KOREA);
  }

  private int number(Map<String, Object> row, String key) {
    return ((Number) row.get(key)).intValue();
  }

  private LocalDateTime time(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDateTime time) return time;
    if (value instanceof Timestamp time) return time.toLocalDateTime();
    return LocalDateTime.parse(value.toString().replace(' ', 'T'));
  }
}
