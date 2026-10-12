package kr.yeohaengpick.collaboration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
import kr.yeohaengpick.auth.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 로그인 회원 ID를 URL 입력과 분리하고, 실제 여행 참여·관리 권한은 서비스에서 다시 검사합니다. */
@RestController
@RequestMapping("/api/trips/{tripId}/polls")
public class TripPollController {

  private final TripPollService polls;
  private final AuthService auth;

  public TripPollController(TripPollService polls, AuthService auth) {
    this.polls = polls;
    this.auth = auth;
  }

  public record Option(@NotBlank @Size(max = 100) String label) {}

  public record Create(
    @NotBlank @Size(max = 120) String title,
    @NotNull @Pattern(regexp = "SINGLE|MULTIPLE") String selectionType,
    @NotNull @Size(min = 2, max = 10) List<@NotNull @Valid Option> options,
    LocalDateTime closesAt
  ) {}

  public record Ballot(
    @NotNull
    @Size(max = 10)
    List<@NotNull @Pattern(
      regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"
    ) String> optionIds
  ) {}

  public record Version(@NotNull @Min(0) Integer version) {}

  @GetMapping
  List<TripPollService.PollView> list(
    @PathVariable String tripId,
    Authentication authentication
  ) {
    return polls.list(tripId, auth.id(authentication));
  }

  @PostMapping
  TripPollService.PollView create(
    @PathVariable String tripId,
    @Valid @RequestBody Create request,
    Authentication authentication
  ) {
    return polls.create(tripId, request, auth.id(authentication));
  }

  @PutMapping("/{pollId}/ballot")
  TripPollService.PollView ballot(
    @PathVariable String tripId,
    @PathVariable String pollId,
    @Valid @RequestBody Ballot request,
    Authentication authentication
  ) {
    return polls.ballot(
      tripId,
      pollId,
      request.optionIds(),
      auth.id(authentication)
    );
  }

  @PostMapping("/{pollId}/close")
  TripPollService.PollView close(
    @PathVariable String tripId,
    @PathVariable String pollId,
    @Valid @RequestBody Version request,
    Authentication authentication
  ) {
    return polls.close(
      tripId,
      pollId,
      request.version(),
      auth.id(authentication)
    );
  }

  @DeleteMapping("/{pollId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(
    @PathVariable String tripId,
    @PathVariable String pollId,
    @Valid @RequestBody Version request,
    Authentication authentication
  ) {
    polls.delete(tripId, pollId, request.version(), auth.id(authentication));
  }
}
