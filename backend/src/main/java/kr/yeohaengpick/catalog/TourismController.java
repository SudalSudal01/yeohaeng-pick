package kr.yeohaengpick.catalog;

import org.springframework.web.bind.annotation.*;

/** 공개 관광정보만 읽습니다. 조회 결과를 회원의 방문 일정·예산·예약 상태로 자동 저장하지 않습니다. */
@RestController
@RequestMapping("/api/catalog/tourism")
public class TourismController {

  private final TourismService service;

  public TourismController(TourismService service) {
    this.service = service;
  }

  @GetMapping("/search")
  Object search(
    @RequestParam String region,
    @RequestParam String query,
    @RequestParam(defaultValue = "1") int page
  ) {
    return service.search(region, query, page);
  }

  @GetMapping("/{id}")
  Object detail(@PathVariable String id) {
    return service.detail(id);
  }
}
