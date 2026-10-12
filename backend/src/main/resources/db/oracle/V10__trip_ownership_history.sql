-- Oracle 19c 전용 이력. 공통 마이그레이션의 동일 버전과 기능을 맞춥니다.
-- 소유권 변경은 방문 일정 복원과 별도로 기록합니다. 기존 여행·참여·예약 데이터는 변경하지 않습니다.
CREATE TABLE trip_ownership_history (
 id VARCHAR2(36 CHAR) PRIMARY KEY,
 trip_id VARCHAR2(36 CHAR) NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
 previous_owner_id VARCHAR2(36 CHAR) NOT NULL REFERENCES members(id),
 new_owner_id VARCHAR2(36 CHAR) NOT NULL REFERENCES members(id),
 version NUMBER(10) NOT NULL,
 revoked_invitations NUMBER(10) NOT NULL,
 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX idx_trip_ownership_history ON trip_ownership_history(trip_id, version);
