package kr.yeohaengpick.collaboration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
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

  /** 누락 값을 trim하기 전에 검증합니다. 메일의 앞뒤 공백만 정리하고 초대 권한은 서버에서 따로 확인합니다. */
  public record Invite(
    @NotBlank @Size(max = 36) String tripId,
    @NotBlank @Email @Size(max = 254) String email
  ) {
    public Invite {
      tripId = tripId == null ? null : tripId.strip();
      email = email == null ? null : email.strip();
    }
  }

  /** 수락 여부가 빠진 요청을 거절로 간주해 대기 초대를 소모하지 않습니다. */
  public record Response(@NotNull Boolean accept) {}

  @GetMapping
  Object list(Authentication a) {
    return invites.list(auth.id(a));
  }

  @PostMapping
  @Transactional
  Object invite(@Valid @RequestBody Invite i, Authentication a) {
    String member = auth.id(a);
    // 사전 검사는 잠금 없이 수행합니다. 공유 일정 가져오기가 회원→여행 순서로 잠그므로
    // 여기서 여행을 먼저 잠근 채 회원을 기다리면 같은 공유 원본에서 서로 교착할 수 있습니다.
    requireOwner(mapper.get(i.tripId), member);
    var recipient = members.byEmail(i.email.trim().toLowerCase(Locale.ROOT));
    if (!MemberMapper.active(recipient)) throw DomainException.bad(
      "초대할 수 있는 회원을 찾지 못했어요."
    );
    String other = (String) recipient.get("id");
    lockPair(member, other);
    var trip = trips.access(i.tripId, member);
    requireOwner(trip, member);
    if (!MemberMapper.active(members.byId(other))) throw DomainException.bad(
      "초대할 수 있는 회원을 찾지 못했어요."
    );
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
    @Valid @RequestBody Response r,
    Authentication a
  ) {
    String member = auth.id(a);
    var invite = invites.get(id);
    if (
      invite == null || !invite.get("recipientId").equals(member)
    ) throw new DomainException(404, "초대를 찾을 수 없어요.");
    // 예비 조회에서는 수신자와 여행 ID만 확인하고 회원→여행→초대 순서로 잠급니다.
    // 공유 가져오기·차단과 같은 순서를 지켜 회원과 원본 여행을 반대로 기다리지 않습니다.
    String trip = (String) invite.get("tripId");
    String sender = (String) invite.get("senderId");
    lockPair(member, sender);
    var lockedTrip = mapper.lock(trip);
    if (lockedTrip == null) throw new DomainException(
      404,
      "여행을 찾을 수 없어요."
    );
    // 기다리는 동안 소유권 이전·참여 해제가 초대를 종료할 수 있어 잠금 뒤 최신 상태를 다시 읽습니다.
    invite = invites.get(id);
    if (
      invite == null ||
      !member.equals(invite.get("recipientId")) ||
      !trip.equals(invite.get("tripId")) ||
      !sender.equals(invite.get("senderId"))
    ) throw new DomainException(404, "초대를 찾을 수 없어요.");
    if (!"PENDING".equals(invite.get("status"))) throw new DomainException(
      409,
      "이미 처리된 초대예요."
    );
    if (
      r.accept && !sender.equals(lockedTrip.get("ownerId"))
    ) throw new DomainException(
      409,
      "여행 소유자가 변경되어 이전 초대를 수락할 수 없어요. 새 초대를 받아 주세요."
    );
    if (
      r.accept &&
      (!MemberMapper.active(members.byId(sender)) ||
        community.blocked(member, sender) > 0)
    ) throw new DomainException(403, "차단한 회원의 초대예요.");
    // 거절은 참여 권한을 만들지 않습니다. 차단한 상대의 기존 초대도 받은 사람이 직접 정리할 수 있습니다.
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

  private void requireOwner(Map<String, Object> trip, String member) {
    if (trip == null) throw new DomainException(404, "여행을 찾을 수 없어요.");
    if (!member.equals(trip.get("ownerId"))) throw new DomainException(
      403,
      "여행 소유자만 초대할 수 있어요."
    );
  }

  /** 회원끼리는 ID 순서로 잠그며 여행 잠금보다 먼저 호출합니다. 공유 가져오기·개인 대화 차단과 공통 순서입니다. */
  private void lockPair(String member, String other) {
    var ordered = new TreeSet<>(List.of(member, other));
    for (String id : ordered)
      if (invites.lockMember(id) == null) throw DomainException.bad(
        "초대할 수 있는 회원을 찾지 못했어요."
      );
  }
}
