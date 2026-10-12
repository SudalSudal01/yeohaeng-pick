-- 기존 숙소/예약 상태는 유지하고 사용자가 확인한 숙박 조건만 선택적으로 보관합니다.
-- total_amount는 전체 숙박 기간·전체 객실의 합계이며 결제 확인이나 자동 조회 가격이 아닙니다.
ALTER TABLE trip_lodgings ADD COLUMN check_in_date DATE;
ALTER TABLE trip_lodgings ADD COLUMN check_out_date DATE;
ALTER TABLE trip_lodgings ADD COLUMN guest_count INTEGER;
ALTER TABLE trip_lodgings ADD COLUMN room_count INTEGER;
ALTER TABLE trip_lodgings ADD COLUMN total_amount INTEGER;
ALTER TABLE trip_lodgings ADD COLUMN check_in_time VARCHAR(5);
ALTER TABLE trip_lodgings ADD COLUMN check_out_time VARCHAR(5);
