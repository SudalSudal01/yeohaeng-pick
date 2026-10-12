-- 일정 항목은 공동 편집 때 교체 삽입하므로 항목 FK 대신 서비스에서 장소/순서 일치 여부를 검증합니다.
-- 서버가 발급한 조회 기록만 저장합니다. 개인 출발지 검색어와 지도 선은 저장하지 않습니다.
CREATE TABLE trip_route_timings (
  trip_id VARCHAR(36) NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
  to_item_id VARCHAR(36) NOT NULL,
  payload TEXT NOT NULL,
  PRIMARY KEY (trip_id, to_item_id)
);
