-- Oracle 19c 전용 이력. 공통 마이그레이션의 동일 버전과 기능을 맞춥니다.
-- 기존 댓글 본문·작성 시각을 보존하며 수정 충돌과 공개 제외 상태만 추가합니다.
ALTER TABLE comments ADD version NUMBER(10) DEFAULT 0 NOT NULL;
ALTER TABLE comments ADD updated_at TIMESTAMP;
ALTER TABLE comments ADD deleted_at TIMESTAMP;
