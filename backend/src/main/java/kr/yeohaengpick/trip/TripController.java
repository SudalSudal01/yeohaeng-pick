package kr.yeohaengpick.trip;

import jakarta.validation.Valid;
import java.util.*;
import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** URL에서 받은 여행 ID와 로그인 회원을 서비스에 함께 넘겨 객체 단위 권한을 검증합니다. */
@RestController
@RequestMapping("/api")
public class TripController {

  private final TripService trips;
  private final AuthService auth;

  public TripController(TripService t, AuthService a) {
    trips = t;
    auth = a;
  }

  @PostMapping("/recommendations")
  Object recommend(@Valid @RequestBody TripRequest r) {
    return trips.recommend(r);
  }

  @GetMapping("/trips")
  Object list(Authentication a) {
    return trips.list(auth.id(a));
  }

  @PostMapping("/trips")
  Object create(@Valid @RequestBody TripService.Create c, Authentication a) {
    return trips.create(c, auth.id(a));
  }

  @GetMapping("/trips/{id}")
  Object detail(@PathVariable String id, Authentication a) {
    return trips.detail(id, auth.id(a));
  }

  @GetMapping("/trips/{id}/alternatives/{item}")
  Object alternatives(
    @PathVariable String id,
    @PathVariable String item,
    @RequestParam(defaultValue = "false") boolean rain,
    Authentication a
  ) {
    return trips.alternatives(id, item, auth.id(a), rain);
  }

  @PostMapping("/trips/{id}/preview")
  Object preview(
    @PathVariable String id,
    @RequestBody TripService.Change c,
    Authentication a
  ) {
    return trips.preview(id, c, auth.id(a));
  }

  @PostMapping("/trips/{id}/apply")
  Object apply(
    @PathVariable String id,
    @RequestBody TripService.Change c,
    Authentication a
  ) {
    return trips.apply(id, c, auth.id(a));
  }

  public record ScheduleChange(
    @jakarta.validation.constraints.Min(0) int version,
    @jakarta.validation.constraints.Pattern(regexp = "EARLIER|LATER")
    String strategy
  ) {}

  @PostMapping("/trips/{id}/schedule/preview")
  Object schedulePreview(
    @PathVariable String id,
    @Valid @RequestBody ScheduleChange c,
    Authentication a
  ) {
    return trips.schedulePreview(id, c.version, auth.id(a));
  }

  @PostMapping("/trips/{id}/schedule/apply")
  Object scheduleApply(
    @PathVariable String id,
    @Valid @RequestBody ScheduleChange c,
    Authentication a
  ) {
    return trips.scheduleApply(id, c.version, c.strategy, auth.id(a));
  }

  @PostMapping("/trips/{id}/schedule/whole/preview")
  Object wholeSchedulePreview(
    @PathVariable String id,
    @RequestBody WholeSchedule.Request r,
    Authentication a
  ) {
    return trips.wholeSchedulePreview(id, r, auth.id(a));
  }

  @PostMapping("/trips/{id}/schedule/whole/apply")
  Object wholeScheduleApply(
    @PathVariable String id,
    @RequestBody WholeSchedule.Request r,
    Authentication a
  ) {
    return trips.wholeScheduleApply(id, r, auth.id(a));
  }

  @PutMapping("/trips/{id}/booking")
  Object booking(
    @PathVariable String id,
    @RequestBody TripService.Booking b,
    Authentication a
  ) {
    return trips.booking(id, b, auth.id(a));
  }

  @PutMapping("/trips/{id}/items/{item}/conditions")
  Object visitConditions(
    @PathVariable String id,
    @PathVariable String item,
    @RequestBody VisitConditions.Request r,
    Authentication a
  ) {
    return trips.visitConditions(id, item, r, auth.id(a));
  }

  @PutMapping("/trips/{id}/lodging-booking")
  Object lodgingBooking(
    @PathVariable String id,
    @RequestBody TripService.Booking b,
    Authentication a
  ) {
    return trips.lodgingBooking(id, b, auth.id(a));
  }

  public record Restore(String historyId, int version) {}

  @PutMapping("/trips/{id}/lodgings/{place}/stay")
  Object lodgingStay(
    @PathVariable String id,
    @PathVariable String place,
    @RequestBody LodgingStay.Request r,
    Authentication a
  ) {
    return trips.lodgingStay(id, place, r, auth.id(a));
  }

  @PostMapping("/trips/{id}/undo")
  Object undo(
    @PathVariable String id,
    @RequestBody Restore r,
    Authentication a
  ) {
    return trips.undo(id, r.historyId, r.version, auth.id(a));
  }

  public record Share(boolean enabled) {}

  @PostMapping("/trips/{id}/share")
  Object share(
    @PathVariable String id,
    @RequestBody Share s,
    Authentication a
  ) {
    return trips.share(id, s.enabled, auth.id(a));
  }

  @DeleteMapping("/trips/{id}")
  void delete(@PathVariable String id, Authentication a) {
    trips.delete(id, auth.id(a));
  }

  public record Task(String content, String taskId, Boolean done) {}

  @PostMapping("/trips/{id}/tasks")
  Object task(@PathVariable String id, @RequestBody Task t, Authentication a) {
    return trips.task(id, auth.id(a), t.content, t.taskId, t.done);
  }

  public record Expense(String label, int amount) {}

  @PostMapping("/trips/{id}/expenses")
  Object expense(
    @PathVariable String id,
    @RequestBody Expense e,
    Authentication a
  ) {
    return trips.expense(id, auth.id(a), e.label, e.amount);
  }
}
