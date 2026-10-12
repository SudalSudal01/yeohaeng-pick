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

  public MessageService(
    MessageMapper m,
    MemberMapper u,
    CommunityMapper c,
    TripService t
  ) {
    messages = m;
    members = u;
    community = c;
    trips = t;
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

  public Object detail(String id, String member) {
    var room = access(id, member);
    room.put("messages", messages.messages(id));
    return room;
  }

  @Transactional
  public Object request(
    String email,
    String recipientId,
    String body,
    String member
  ) {
    checkBody(body);
    var recipient =
      recipientId != null
        ? members.byId(recipientId)
        : email != null
          ? members.byEmail(email.trim().toLowerCase(Locale.ROOT))
          : null;
    if (recipient == null) throw DomainException.bad(
      "연락할 수 있는 회원을 찾지 못했어요."
    );
    String other = (String) recipient.get("id");
    if (
      other.equals(member) ||
      community.blocked(member, other) > 0 ||
      recipient.get("dmScope").equals("OFF") ||
      (recipient.get("dmScope").equals("COMPANIONS") &&
        messages.companions(member, other) == 0)
    ) throw new DomainException(
      403,
      "현재 이 회원에게 새 대화를 요청할 수 없어요."
    );
    var existing = messages.existing(member, other);
    if (existing != null) return detail((String) existing.get("id"), member);
    String id = UUID.randomUUID().toString();
    messages.create(id, member, other, "PENDING", null);
    messages.send(UUID.randomUUID().toString(), id, member, body.trim());
    return detail(id, member);
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
  public Object send(String id, String body, String member) {
    checkBody(body);
    var room = access(id, member);
    if (!room.get("status").equals("ACTIVE")) throw new DomainException(
      409,
      "상대방이 대화 요청을 수락한 뒤 메시지를 보낼 수 있어요."
    );
    if (
      room.get("tripId") == null &&
      community.blocked(
        (String) room.get("requesterId"),
        (String) room.get("recipientId")
      ) > 0
    ) throw new DomainException(403, "메시지를 보낼 수 없는 대화예요.");
    messages.send(UUID.randomUUID().toString(), id, member, body.trim());
    return detail(id, member);
  }

  public Object respond(String id, boolean accept, String member) {
    access(id, member);
    if (
      messages.respond(id, member, accept ? "ACTIVE" : "DECLINED") != 1
    ) throw new DomainException(
      409,
      "이미 처리됐거나 수락할 수 없는 요청이에요."
    );
    return detail(id, member);
  }

  public Object block(String other, boolean enable, String member) {
    if (
      other.equals(member) || members.byId(other) == null
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

  private void checkBody(String body) {
    if (
      body == null || body.isBlank() || body.length() > 2000
    ) throw DomainException.bad("메시지는 1~2,000자로 입력해 주세요.");
  }

  public void requireMessageAccess(String id, String member) {
    var m = messages.message(id);
    if (m == null) throw new DomainException(404, "메시지를 찾을 수 없어요.");
    access((String) m.get("conversationId"), member);
  }

  public Object reportedMessage(String id) {
    return messages.message(id);
  }
}
