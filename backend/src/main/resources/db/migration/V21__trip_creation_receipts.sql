-- 생성 성공과 같은 트랜잭션에서만 기록합니다. 삭제 후에는 요청 키를 남겨 재시도로 되살리지 않습니다.
CREATE TABLE trip_mutations (
  member_id VARCHAR(36) NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  operation VARCHAR(20) NOT NULL CHECK (operation IN ('TRIP_CREATE','TASK_ADD','EXPENSE_ADD')),
  request_id VARCHAR(36) NOT NULL,
  payload_hash VARCHAR(64) NOT NULL,
  trip_id VARCHAR(36) REFERENCES trips(id) ON DELETE SET NULL,
  target_id VARCHAR(36) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  PRIMARY KEY (member_id,operation,request_id)
);
CREATE INDEX idx_trip_mutations_trip ON trip_mutations(trip_id);
COMMENT ON TABLE trip_mutations IS '여행·준비물·경비 생성 성공의 회원별 비공개 재시도 영수증. 같은 작업·키·실효 입력은 한 번만 저장. 본문·응답 JSON은 보관하지 않으며 회원 탈퇴 시 제거';
COMMENT ON COLUMN trip_mutations.member_id IS '요청한 세션 회원 ID. 다른 회원의 영수증은 조회하지 않으며 회원 물리 삭제 시 함께 제거';
COMMENT ON COLUMN trip_mutations.operation IS '요청 키의 작업 범위. TRIP_CREATE=여행 생성, TASK_ADD=준비물 추가, EXPENSE_ADD=경비 추가. 기존 항목 수정은 제외';
COMMENT ON COLUMN trip_mutations.request_id IS '클라이언트가 논리적 생성 요청에 한 번 발급한 UUID 표준 소문자형. 같은 회원·작업 안에서만 유일하며 재시도에 재사용';
COMMENT ON COLUMN trip_mutations.payload_hash IS '실효 요청 입력을 정규 JSON으로 정렬한 SHA-256 64자리 16진수. 같은 키의 내용 변경은 거절. 원문 대신 해시만 저장하지만 회원별 비공개 행동 기록으로 관리';
COMMENT ON COLUMN trip_mutations.trip_id IS '생성 대상이 속한 여행 ID. 여행 삭제 시 NULL로 남겨 과거 성공을 다시 생성하지 않음. 재시도 응답 전 현재 참여 권한을 다시 검사';
COMMENT ON COLUMN trip_mutations.target_id IS '생성된 여행·준비물·경비 UUID. 작업별 대상 테이블을 다시 확인하며 삭제 후에도 식별자만 남겨 재생성을 방지. 본문·금액·개인 주소를 포함하지 않음';
COMMENT ON COLUMN trip_mutations.created_at IS '생성 성공 트랜잭션의 최초 영수증 시각. DB 서버 시각이며 재시도 시 갱신하지 않음';
