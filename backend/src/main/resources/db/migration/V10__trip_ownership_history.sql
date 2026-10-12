-- 소유권 변경은 방문 일정 복원과 별도로 기록합니다. 기존 여행·참여·예약 데이터는 변경하지 않습니다.
CREATE TABLE trip_ownership_history (
 id VARCHAR(36) PRIMARY KEY,
 trip_id VARCHAR(36) NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
 previous_owner_id VARCHAR(36) NOT NULL REFERENCES members(id),
 new_owner_id VARCHAR(36) NOT NULL REFERENCES members(id),
 version INTEGER NOT NULL,
 revoked_invitations INTEGER NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_trip_ownership_history ON trip_ownership_history(trip_id, version);
