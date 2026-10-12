-- 후보나 투표를 삭제할 때 자식 표를 빠르게 찾도록 복합 외래키 순서와 같은 인덱스를 둡니다.
-- 기본키는 (poll_id, member_id, option_id)라서 (poll_id, option_id) 조회를 충분히 지원하지 못합니다.
CREATE INDEX idx_poll_votes_option
  ON trip_poll_votes(poll_id, option_id);
