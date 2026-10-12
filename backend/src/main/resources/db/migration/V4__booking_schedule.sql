-- 기존 기록은 자동으로 시간 제약에 포함하지 않습니다. 사용자가 도착 시간과 여유 시간을 확인한 뒤 연결합니다.
ALTER TABLE external_bookings ADD COLUMN arrival VARCHAR(16);
ALTER TABLE external_bookings ADD COLUMN departure_place VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE external_bookings ADD COLUMN arrival_place VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE external_bookings ADD COLUMN before_minutes INTEGER NOT NULL DEFAULT 30;
ALTER TABLE external_bookings ADD COLUMN after_minutes INTEGER NOT NULL DEFAULT 30;
ALTER TABLE external_bookings ADD COLUMN schedule_linked BOOLEAN NOT NULL DEFAULT FALSE;
