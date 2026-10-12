package kr.yeohaengpick.collaboration;

import java.util.*;
import kr.yeohaengpick.auth.*;
import kr.yeohaengpick.common.*;
import kr.yeohaengpick.community.CommunityMapper;
import kr.yeohaengpick.trip.*;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** 초대를 수락해야 일정 접근 권한이 생깁니다. 동행의 기본 취향은 변경하지 않고 여행에만 합칩니다. */
@RestController
@RequestMapping("/api/invitations")
public class InvitationController {

  private final InvitationMapper invites;
  private final TripService trips;
  private final TripMapper mapper;
  private final MemberMapper members;
  private final CommunityMapper community;
  private final AuthService auth;
  private final JsonCodec json;

  public InvitationController(
    InvitationMapper i,
    TripService t,
    TripMapper tm,
    MemberMapper m,
    CommunityMapper c,
    AuthService a,
    JsonCodec j
  ) {
    invites = i;
    trips = t;
    mapper = tm;
    members = m;
    community = c;
    auth = a;
    json = j;
  }

  public record Invite(String tripId, String email) {}

  public record Response(boolean accept) {}

  @GetMapping
  Object list(Authentication a) {
    return invites.list(auth.id(a));
  }

  @PostMapping
  @Transactional
  Object invite(@RequestBody Invite i, Authentication a) {
    String member = auth.id(a);
    var trip = trips.access(i.tripId, member);
    if (!trip.get("ownerId").equals(member)) throw new DomainException(
      403,
      "여행 소유자만 초대할 수 있어요."
    );
    var recipient = members.byEmail(i.email.trim().toLowerCase(Locale.ROOT));
    if (!MemberMapper.active(recipient)) throw DomainException.bad(
      "초대할 수 있는 회원을 찾지 못했어요."
    );
    String other = (String) recipient.get("id");
    if (community.blocked(member, other) > 0) throw new DomainException(
      403,
      "이 회원에게 초대를 보낼 수 없어요."
    );
    if (
      mapper.access(i.tripId, other) > 0 || invites.pending(i.tripId, other) > 0
    ) throw new DomainException(409, "이미 참여했거나 초대를 받은 회원이에요.");
    invites.insert(UUID.randomUUID().toString(), i.tripId, member, other);
    return Map.of("message", "여행 초대를 보냈어요.");
  }

  @PostMapping("/{id}/respond")
  @Transactional
  Object respond(
    @PathVariable String id,
    @RequestBody Response r,
    Authentication a
  ) {
    String member = auth.id(a);
    var invite = invites.get(id);
    if (
      invite == null || !invite.get("recipientId").equals(member)
    ) throw new DomainException(404, "초대를 찾을 수 없어요.");
    if (
      community.blocked(member, (String) invite.get("senderId")) > 0
    ) throw new DomainException(403, "차단한 회원의 초대예요.");
    // 초대 응답도 참여 해제·편집과 같은 잠금 순서(여행→초대)를 사용합니다.
    // 수락과 해제가 겹쳐도 이전 초대가 참여 권한을 되살리지 못하게 합니다.
    String trip = (String) invite.get("tripId");
    if (mapper.lock(trip) == null) throw new DomainException(
      404,
      "여행을 찾을 수 없어요."
    );
    if (
      invites.respond(id, member, r.accept ? "ACCEPTED" : "DECLINED") != 1
    ) throw new DomainException(409, "이미 처리된 초대예요.");
    if (r.accept) {
      if (mapper.access(trip, member) > 0) throw new DomainException(
        409,
        "이미 참여 중인 여행이에요."
      );
      mapper.member(trip, member);
      Set<String> merged = new LinkedHashSet<>(
        json.strings((String) mapper.get(trip).get("preferences"))
      );
      merged.addAll(
        json.strings((String) members.byId(member).get("preferences"))
      );
      invites.preferences(trip, json.write(merged));
    }
    return Map.of("tripId", trip, "accepted", r.accept);
  }
}
