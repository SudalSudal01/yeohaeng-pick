-- Oracle은 부모 후보 삭제 시 자식 외래키 열에 맞는 인덱스가 없으면 자식 테이블 잠금 범위가 커질 수 있습니다.
-- 실제 적용된 V16은 보존하고, 후보 복합 외래키와 같은 순서의 인덱스를 새 버전에서 추가합니다.
CREATE INDEX idx_poll_votes_option
  ON trip_poll_votes(poll_id, option_id);
