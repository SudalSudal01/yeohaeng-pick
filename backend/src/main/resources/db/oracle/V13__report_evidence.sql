-- 접수 당시의 최소 공개 정보만 보존합니다. 이전 신고에 현재 내용을 덧씌우지 않습니다.
ALTER TABLE reports ADD evidence CLOB;
ALTER TABLE reports ADD captured_at TIMESTAMP;
ALTER TABLE reports ADD resolved_at TIMESTAMP;
ALTER TABLE reports ADD resolved_by VARCHAR2(36 CHAR) REFERENCES members(id);
COMMENT ON COLUMN reports.evidence IS '신고 접수 당시 대상 한 건의 화이트리스트 JSON. 글 본문 또는 메시지 한 건 또는 회원 공개 닉네임만 포함. 이전 신고는 NULL이며 현재 내용으로 소급 생성하지 않음';
COMMENT ON COLUMN reports.captured_at IS '신고 증거를 보존한 DB 시각. 기존 신고는 NULL';
COMMENT ON COLUMN reports.resolved_at IS '관리자가 최초 처리를 완료한 DB 시각. 대기 중 또는 이전 처리 기록은 NULL';
COMMENT ON COLUMN reports.resolved_by IS '최초 처리 관리자 회원 ID. 대기 중 또는 이전 처리 기록은 NULL';
