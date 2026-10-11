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
  Object invite(@RequestBody Invite i, Authentication a) {
    String member = auth.id(a);
    var trip = trips.access(i.tripId, member);
    if (!trip.get("ownerId").equals(member)) throw new DomainException(
      403,
      "여행 소유자만 초대할 수 있어요."
    );
    var recipient = members.byEmail(i.email.trim().toLowerCase(Locale.ROOT));
    if (recipient == null) throw DomainException.bad(
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
    if (
      invites.respond(id, member, r.accept ? "ACCEPTED" : "DECLINED") != 1
    ) throw new DomainException(409, "이미 처리된 초대예요.");
    String trip = (String) invite.get("tripId");
    if (r.accept) {
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
