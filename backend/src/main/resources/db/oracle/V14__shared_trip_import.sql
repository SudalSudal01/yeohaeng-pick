-- Oracle 19c 전용. 원본 여행 외래키를 두지 않아 원본 삭제/공유 종료 후에도 독립 복사본을 유지합니다.
CREATE TABLE trip_imports (
  member_id VARCHAR2(36 CHAR) NOT NULL REFERENCES members(id),
  request_id VARCHAR2(36 CHAR) NOT NULL,
  trip_id VARCHAR2(36 CHAR) REFERENCES trips(id) ON DELETE SET NULL,
  payload_hash VARCHAR2(64 CHAR) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  PRIMARY KEY (member_id, request_id)
);
CREATE INDEX idx_trip_imports_trip ON trip_imports(trip_id);
COMMENT ON TABLE trip_imports IS '공개 일정 가져오기 중복 방지 기록. 회원별 요청 키로 한 번만 생성하며 원본 ID·공유 토큰·예약·개인정보를 보관하지 않음. 회원 탈퇴 시 제거';
COMMENT ON COLUMN trip_imports.member_id IS '가져오기를 요청한 회원 ID. 해당 회원의 재시도만 확인하며 공개 응답에서 제외';
COMMENT ON COLUMN trip_imports.request_id IS '클라이언트가 가져오기 양식별 생성한 UUID. 같은 회원·키·내용의 재전송은 기존 복사본을 반환';
COMMENT ON COLUMN trip_imports.trip_id IS '생성한 독립 여행 ID. 삭제 시 NULL로 남겨 같은 요청 재전송에 의한 복구를 방지. 소유권·참여 종료 후 접근은 별도 검사';
COMMENT ON COLUMN trip_imports.payload_hash IS '요청 내용의 SHA-256 64자리 16진수. 동일 키에 다른 내용을 보내는 재사용을 거절. 원문 토큰·출발지는 보관하지 않음';
COMMENT ON COLUMN trip_imports.created_at IS '가져오기 트랜잭션의 최초 기록 시각. 서버 DB 시각이며 재시도 시 갱신하지 않음';
