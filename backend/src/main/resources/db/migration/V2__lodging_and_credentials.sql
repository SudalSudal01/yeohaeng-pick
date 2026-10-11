-- 숙소의 좌표를 일정과 분리해 보존합니다. 장소 교체나 일정 복원으로 숙소 예약이 바뀌지 않습니다.
CREATE TABLE trip_lodgings (
 trip_id VARCHAR(36) NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
 place_id VARCHAR(100) NOT NULL, region VARCHAR(50) NOT NULL, name VARCHAR(150) NOT NULL,
 latitude DOUBLE PRECISION NOT NULL, longitude DOUBLE PRECISION NOT NULL,
 nightly_estimate INTEGER NOT NULL, source VARCHAR(30) NOT NULL, place_url TEXT NOT NULL,
 booking_status VARCHAR(30) NOT NULL DEFAULT 'NONE', booking_reason VARCHAR(150) NOT NULL DEFAULT '',
 PRIMARY KEY(trip_id, region)
);
-- 비밀번호 변경 이후 이전에 발급된 모든 세션을 다음 요청부터 거절하기 위한 버전입니다.
ALTER TABLE members ADD COLUMN credential_version INTEGER NOT NULL DEFAULT 0;
