-- 기존 댓글 본문·작성 시각을 보존하며 수정 충돌과 공개 제외 상태만 추가합니다.
ALTER TABLE comments ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE comments ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE comments ADD COLUMN deleted_at TIMESTAMP;
