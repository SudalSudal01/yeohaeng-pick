package kr.yeohaengpick.trip;

import jakarta.validation.Valid;
import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 후보 조회도 여행 참여 권한을 확인합니다. 적용은 CSRF·버전 검사와 같은 트랜잭션으로 보호합니다. */
@RestController
@RequestMapping("/api/trips/{id}/lodgings")
public class LodgingChoiceController {

  private final LodgingChoiceService choices;
  private final AuthService auth;

  public LodgingChoiceController(
    LodgingChoiceService choices,
    AuthService auth
  ) {
    this.choices = choices;
    this.auth = auth;
  }

  @GetMapping("/candidates")
  Object candidates(
    @PathVariable String id,
    @RequestParam String region,
    @RequestParam(defaultValue = "") String query,
    Authentication a
  ) {
    return choices.candidates(id, auth.id(a), region, query);
  }

  @PostMapping("/preview")
  Object preview(
    @PathVariable String id,
    @Valid @RequestBody LodgingChoiceService.Change change,
    Authentication a
  ) {
    return choices.preview(id, auth.id(a), change);
  }

  @PostMapping("/apply")
  Object apply(
    @PathVariable String id,
    @Valid @RequestBody LodgingChoiceService.Change change,
    Authentication a
  ) {
    return choices.apply(id, auth.id(a), change);
  }
}
