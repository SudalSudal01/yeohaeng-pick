-- Oracle 19c 전용. 한글 JSON은 CLOB 전용 바인딩으로 저장해 유니코드 원문을 보존합니다.
CREATE TABLE favorite_places (
  member_id VARCHAR2(36 CHAR) NOT NULL REFERENCES members(id),
  place_id VARCHAR2(100 CHAR) NOT NULL,
  place_json CLOB NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  PRIMARY KEY (member_id, place_id)
);
COMMENT ON TABLE favorite_places IS '회원별 비공개 장소 찜. 최대 200곳, 같은 장소 중복 저장 불가. 여행과 독립하며 회원 탈퇴 시 제거';
COMMENT ON COLUMN favorite_places.member_id IS '찜한 회원 ID. 본인 세션으로만 접근하며 동행·커뮤니티·공개 공유에는 노출하지 않음';
COMMENT ON COLUMN favorite_places.place_id IS '서버 검색에서 확인한 공급자 포함 장소 ID. 회원과 함께 중복을 제한하며 추천 시 해당 공급자 장소를 재조회';
COMMENT ON COLUMN favorite_places.place_json IS '서버 검색 결과의 장소 이름·지역·분류·좌표·머무름 분·1인 참고 활동비 원·출처·지도 URL JSON. 저장 당시 정보이며 현재 운영·요금·예약 확정이 아님';
COMMENT ON COLUMN favorite_places.created_at IS '최초 찜 시각. DB 서버 시각이며 중복 요청으로 갱신하지 않음. 해제 후 다시 저장하면 새 시각';
