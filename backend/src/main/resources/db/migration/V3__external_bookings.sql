-- 제휴 콜백 없이도 교통·항공·기타 예매의 실패/취소를 명시적으로 기록할 수 있습니다.
CREATE TABLE external_bookings (
 id VARCHAR(36) PRIMARY KEY, trip_id VARCHAR(36) NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
 label VARCHAR(100) NOT NULL, category VARCHAR(20) NOT NULL, departure VARCHAR(16) NOT NULL,
 amount INTEGER, status VARCHAR(30) NOT NULL DEFAULT 'NEEDS_CONFIRMATION', reason VARCHAR(150) NOT NULL DEFAULT '',
 updated_by VARCHAR(36) NOT NULL REFERENCES members(id)
);
