package kr.yeohaengpick.collaboration;

import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 클라이언트의 역할 표시와 무관하게 서비스가 실제 소유권·현재 참여·버전을 다시 검사합니다. */
@RestController
@RequestMapping("/api/trips/{id}")
public class TripMembershipController {

  private final TripMembershipService memberships;
  private final AuthService auth;

  public TripMembershipController(
    TripMembershipService memberships,
    AuthService auth
  ) {
    this.memberships = memberships;
    this.auth = auth;
  }

  public record Change(int version) {}

  @PostMapping("/leave")
  void leave(
    @PathVariable String id,
    @RequestBody Change request,
    Authentication a
  ) {
    memberships.leave(id, request.version(), auth.id(a));
  }

  @PostMapping("/members/{member}/remove")
  Object remove(
    @PathVariable String id,
    @PathVariable String member,
    @RequestBody Change request,
    Authentication a
  ) {
    return memberships.remove(id, request.version(), member, auth.id(a));
  }
}
