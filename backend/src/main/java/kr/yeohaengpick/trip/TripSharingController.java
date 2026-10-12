package kr.yeohaengpick.trip;

import jakarta.validation.Valid;
import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 공개 조회와 로그인/CSRF가 필요한 생성 경로를 분리합니다. /share/** 공개 허용 규칙으로 쓰기가 열리지 않습니다. */
@RestController
@RequestMapping("/api")
public class TripSharingController {

  private final TripSharingService sharing;
  private final AuthService auth;

  public TripSharingController(TripSharingService sharing, AuthService auth) {
    this.sharing = sharing;
    this.auth = auth;
  }

  @GetMapping("/share/{token}")
  Object shared(@PathVariable String token) {
    return sharing.shared(token);
  }

  @PostMapping("/trips/import")
  Object importTrip(
    @Valid @RequestBody TripSharingService.ImportRequest request,
    Authentication authentication
  ) {
    return sharing.importTrip(request, auth.id(authentication));
  }
}
