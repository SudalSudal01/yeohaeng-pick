package kr.yeohaengpick.messaging;

import java.util.*;
import kr.yeohaengpick.auth.MemberMapper;
import kr.yeohaengpick.common.*;
import kr.yeohaengpick.community.CommunityMapper;
import kr.yeohaengpick.trip.TripService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 수신 설정·상호 차단·요청 수락을 실제 전송 시점에 확인합니다. 대화 차단은 여행 참여 권한을 변경하지 않습니다. */
@Service
public class MessageService {

  private final MessageMapper messages;
  private final MemberMapper members;
  private final CommunityMapper community;
  private final TripService trips;
  private final MessageRateLimit rate;

  public MessageService(
    MessageMapper m,
    MemberMapper u,
    CommunityMapper c,
    TripService t,
    MessageRateLimit r
  ) {
    messages = m;
    members = u;
    community = c;
    trips = t;
    rate = r;
  }

  /** 닉네임 앞부분으로만 찾습니다. 이메일·개인 취향·가입일 등은 조회 결과에 포함하지 않습니다. */
  public Object recipients(String query, String member) {
    rate.check(member, "lookup", 60);
    String value = query == null ? "" : query.strip();
    if (
      value.codePointCount(0, value.length()) < 2 || value.length() > 30
    ) throw DomainException.bad("닉네임은 2~30자로 입력해 주세요.");
    String escaped = value
      .toLowerCase(Locale.ROOT)
      .replace("!", "!!")
      .replace("%", "!%")
      .replace("_", "!_");
    var found = messages.recipients(member, escaped + "%");
    boolean more = found.size() > 20;
    return Map.of(
      "items",
      new ArrayList<>(found.subList(0, Math.min(found.size(), 20))),
      "hasMore",
      more
    );
  }

  /** 커뮤니티에서 넘긴 ID도 같은 수신 조건으로 재검사합니다. 없거나 연락 불가이면 같은 응답입니다. */
  public Object recipient(String id, String member) {
    rate.check(member, "lookup", 60);
    checkIdentifier(id);
    var result = messages.recipient(member, id);
    if (result == null) throw new DomainException(
      404,
      "대화를 요청할 수 있는 회원을 찾지 못했어요."
    );
    return result;
  }

  public Object list(String member) {
    return messages.list(member);
  }

  private Map<String, Object> access(String id, String member) {
    var room = messages.room(id);
    if (room == null) throw new DomainException(
      404,
      "대화방을 찾을 수 없어요."
    );
    if (room.get("tripId") != null) trips.access(
      (String) room.get("tripId"),
      member
    );
    else if (
      !room.get("requesterId").equals(member) &&
      !room.get("recipientId").equals(member)
    ) throw new DomainException(403, "대화 참여자만 볼 수 있어요.");
    return room;
  }

  public Map<String, Object> detail(String id, String member) {
    return detail(id, null, member);
  }

  /** 최신 50개를 먼저 보여 주고 이전 메시지는 ID 커서로 조회합니다. 오래 대화해도 최신 글이 사라지지 않습니다. */
  public Map<String, Object> detail(String id, String before, String member) {
    var room = access(id, member);
    if (before != null) {
      checkIdentifier(before);
      var cursor = messages.message(before);
      if (
        cursor == null || !id.equals(cursor.get("conversationId"))
      ) throw DomainException.bad("이전 메시지 위치를 확인해 주세요.");
    }
    var page = new ArrayList<>(
      before == null
        ? messages.latestMessages(id)
        : messages.olderMessages(id, before)
    );
    boolean older = page.size() > 50;
    if (older) page.removeLast();
    Collections.reverse(page);
    room.put("messages", page);
    room.put("hasOlder", older);
    room.put("olderCursor", older ? page.getFirst().get("id") : null);
    boolean direct = room.get("tripId") == null;
    boolean blocked =
      direct &&
      community.blocked(
        (String) room.get("requesterId"),
        (String) room.get("recipientId")
      ) > 0;
    boolean active =
      !direct ||
      (MemberMapper.active(members.byId((String) room.get("requesterId"))) &&
        MemberMapper.active(members.byId((String) room.get("recipientId"))));
    room.put("blocked", blocked);
    room.put(
      "canSend",
      "ACTIVE".equals(room.get("status")) && active && !blocked
    );
    return room;
  }

  @Transactional
  public Object request(
    String email,
    String recipientId,
    String body,
    String member
  ) {
    // 이메일 조회 경로를 남겨 두면 닉네임과 이메일 계정의 연결을 추정할 수 있습니다.
    // 예전 클라이언트의 이메일 전용/혼합 요청은 조회하지 않고 명시적으로 거절합니다.
    if (email != null) throw DomainException.bad(
      "닉네임으로 상대를 검색하고 선택한 뒤 요청해 주세요."
    );
    checkIdentifier(recipientId);
    rate.check(member, "request", 12);
    checkBody(body);
    // 이미 탈퇴했거나 없는 회원 ID는 요청 대상 자체가 유효하지 않습니다.
    // 이메일로 계정을 찾지는 않으며, 공개 ID를 선택한 뒤 사라진 대상을 400으로 안내합니다.
    if (
      !MemberMapper.active(members.byId(recipientId))
    ) throw DomainException.bad("대화를 요청할 수 있는 회원을 찾지 못했어요.");
    lockPair(member, recipientId);
    if (
      !MemberMapper.active(members.byId(recipientId))
    ) throw DomainException.bad("대화를 요청할 수 있는 회원을 찾지 못했어요.");
    var recipient = messages.recipient(member, recipientId);
    if (recipient == null) throw unavailable();
    String other = (String) recipient.get("id");
    var existing = messages.existing(member, other);
    if (existing != null) {
      var result = detail((String) existing.get("id"), member);
      result.put("requestCreated", false);
      return result;
    }
    String id = UUID.randomUUID().toString();
    messages.create(id, member, other, "PENDING", null);
    messages.send(UUID.randomUUID().toString(), id, member, body.trim());
    var result = detail(id, member);
    result.put("requestCreated", true);
    return result;
  }

  @Transactional
  public Object group(String trip, String member) {
    trips.access(trip, member);
    var room = messages.group(trip);
    if (room == null) {
      String id = UUID.randomUUID().toString();
      messages.create(id, member, member, "ACTIVE", trip);
      return detail(id, member);
    }
    return detail((String) room.get("id"), member);
  }

  @Transactional
  public Object send(String id, String body, String requestId, String member) {
    rate.check(member, "send", 90);
    checkBody(body);
    var room = access(id, member);
    if (room.get("tripId") == null) {
      lockPair(
        (String) room.get("requesterId"),
        (String) room.get("recipientId")
      );
      room = access(id, member);
    }
    if (!room.get("status").equals("ACTIVE")) throw new DomainException(
      409,
      "CLOSED".equals(room.get("status"))
        ? "종료된 대화는 이전 메시지만 확인할 수 있어요."
        : "상대방이 대화 요청을 수락한 뒤 메시지를 보낼 수 있어요."
    );
    // CLOSED 상태는 읽기 전용 계약(409)으로 먼저 처리하고, 활동 중 방의 상대 상태를 방어적으로 검사합니다.
    if (
      room.get("tripId") == null &&
      (!MemberMapper.active(members.byId((String) room.get("requesterId"))) ||
        !MemberMapper.active(members.byId((String) room.get("recipientId"))))
    ) throw new DomainException(403, "메시지를 보낼 수 없는 대화예요.");
    if (
      room.get("tripId") == null &&
      community.blocked(
        (String) room.get("requesterId"),
        (String) room.get("recipientId")
      ) > 0
    ) throw new DomainException(403, "메시지를 보낼 수 없는 대화예요.");
    String messageId = messageIdentifier(requestId);
    var previous = messages.message(messageId);
    if (previous != null) {
      // 성공 응답만 유실된 요청을 재시도해도 같은 메시지는 한 번만 저장합니다.
      // 다른 작성자/방/본문으로 키를 재사용하면 기존 내용은 공개하지 않고 거절합니다.
      if (
        !id.equals(previous.get("conversationId")) ||
        !member.equals(previous.get("senderId")) ||
        !body.trim().equals(previous.get("body"))
      ) throw new DomainException(
        409,
        "이미 다른 메시지에 사용한 전송 식별자예요. 내용을 확인해 주세요."
      );
      return detail(id, member);
    }
    messages.send(messageId, id, member, body.trim());
    return detail(id, member);
  }

  private String messageIdentifier(String requestId) {
    if (requestId == null) return UUID.randomUUID().toString();
    try {
      if (requestId.length() != 36) throw new IllegalArgumentException();
      return UUID.fromString(requestId).toString();
    } catch (IllegalArgumentException e) {
      throw DomainException.bad("메시지 전송 식별자를 확인해 주세요.");
    }
  }

  @Transactional
  public Object respond(String id, boolean accept, String member) {
    var room = access(id, member);
    if (room.get("tripId") != null) throw new DomainException(
      409,
      "처리할 수 없는 요청이에요."
    );
    lockPair(
      (String) room.get("requesterId"),
      (String) room.get("recipientId")
    );
    // 기다리는 동안 상태가 바뀔 수 있으므로 잠금 뒤 다시 읽습니다. 종료·이미 처리된 방은
    // 차단·탈퇴 검사보다 먼저 상태 충돌로 돌려주어 읽기 전용이라는 기존 계약을 유지합니다.
    room = access(id, member);
    if (!"PENDING".equals(room.get("status"))) throw new DomainException(
      409,
      "이미 처리됐거나 수락할 수 없는 요청이에요."
    );
    if (
      accept &&
      (community.blocked(
        (String) room.get("requesterId"),
        (String) room.get("recipientId")
      ) > 0 ||
        !MemberMapper.active(members.byId((String) room.get("requesterId"))) ||
        !MemberMapper.active(members.byId((String) room.get("recipientId"))))
    ) throw unavailable();
    if (
      messages.respond(id, member, accept ? "ACTIVE" : "DECLINED") != 1
    ) throw new DomainException(
      409,
      "이미 처리됐거나 수락할 수 없는 요청이에요."
    );
    return detail(id, member);
  }

  @Transactional
  public Object block(String other, boolean enable, String member) {
    checkIdentifier(other);
    rate.check(member, "block", 30);
    lockPair(member, other);
    if (
      other.equals(member) || !MemberMapper.active(members.byId(other))
    ) throw DomainException.bad("회원을 확인해 주세요.");
    if (enable) {
      if (
        community
          .blocks(member)
          .stream()
          .noneMatch(b -> b.get("blockedId").equals(other))
      ) community.block(member, other);
    } else community.unblock(member, other);
    return community.blocks(member);
  }

  public Object blocks(String member) {
    return community.blocks(member);
  }

  private void checkIdentifier(String id) {
    if (
      id == null || id.isBlank() || id.length() > 36
    ) throw DomainException.bad(
      "상대를 다시 선택하거나 대화방을 확인해 주세요."
    );
  }

  /** 모든 개인 대화 변경이 같은 회원 행을 같은 순서로 잠그므로 반대 방향 요청도 교착되지 않습니다.
   * 여행 행은 여기서 잠그지 않습니다. 단체방은 기존 여행 잠금 경계를 따릅니다. */
  private void lockPair(String a, String b) {
    if (a.equals(b)) throw unavailable();
    String first = a.compareTo(b) < 0 ? a : b,
      second = a.compareTo(b) < 0 ? b : a;
    if (
      messages.lockMember(first) == null || messages.lockMember(second) == null
    ) throw unavailable();
  }

  private DomainException unavailable() {
    return new DomainException(
      403,
      "현재 이 회원에게 새 대화를 요청할 수 없어요."
    );
  }

  private void checkBody(String body) {
    if (
      body == null || body.isBlank() || body.length() > 2000
    ) throw DomainException.bad("메시지는 1~2,000자로 입력해 주세요.");
  }

  /** 신고된 메시지 한 건만 복사합니다. 읽을 수 있는 대화인지 검사한 뒤 공개 닉네임만 덧붙입니다. */
  public Map<String, Object> reportEvidence(String id, String member) {
    var m = messages.message(id);
    if (m == null) throw new DomainException(404, "메시지를 찾을 수 없어요.");
    access((String) m.get("conversationId"), member);
    var evidence = kr.yeohaengpick.community.ReportService.pick(
      m,
      "id",
      "senderId",
      "body",
      "createdAt"
    );
    evidence.put(
      "nickname",
      members.byId((String) m.get("senderId")).get("nickname")
    );
    return evidence;
  }
}
