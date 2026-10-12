-- 공급자 원문을 자동 해석하지 않고 사용자가 당일 확인한 조건만 보존합니다.
-- NULL 금액은 미입력, 0원은 명시적 입력입니다. 기존 추천 비용은 별도 cost 열에 유지합니다.
ALTER TABLE itinerary_items ADD COLUMN visit_start VARCHAR(5);
ALTER TABLE itinerary_items ADD COLUMN visit_end VARCHAR(5);
ALTER TABLE itinerary_items ADD COLUMN visit_cost INTEGER CHECK (visit_cost >= 0 AND visit_cost <= 500000);
ALTER TABLE itinerary_items ADD COLUMN visit_note VARCHAR(500) NOT NULL DEFAULT '';
ALTER TABLE itinerary_items ADD CONSTRAINT visit_window_pair CHECK (
 (visit_start IS NULL AND visit_end IS NULL) OR (visit_start IS NOT NULL AND visit_end IS NOT NULL AND visit_start < visit_end)
);
