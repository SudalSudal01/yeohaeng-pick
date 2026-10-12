import { useCallback, useRef, useState } from "react";
import { Loader2, Save } from "lucide-react";
import { api, ApiError } from "../../shared/api";
import type { Post } from "../../shared/types";
import { ErrorState, Modal } from "../../shared/ui";

/** 작성과 수정을 같은 양식으로 다룹니다. 열린 시점 버전을 보존하고 사용자 선택 없이 초안을 최신 글로 덮지 않습니다. */
export default function PostEditor({
  initial,
  latestVersion,
  close,
  done,
  reload,
}: {
  initial?: Post;
  latestVersion?: number;
  close: () => void;
  done: (post: Post) => Promise<void>;
  reload?: () => Promise<void>;
}) {
  const [form, setForm] = useState({
    category: initial?.category ?? "후기",
    region: initial?.region ?? "서울",
    title: initial?.title ?? "",
    body: initial?.body ?? "",
    recruitmentDate: initial?.recruitmentDate?.slice(0, 10) ?? "",
    capacity: initial?.capacity ?? 2,
  });
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<Error | null>(null);
  const safeClose = useCallback(() => {
    if (!busyRef.current) close();
  }, [close]);
  const stale =
    !!initial &&
    (initial.version !== latestVersion ||
      (error instanceof ApiError && error.status === 409));
  const unavailable =
    error instanceof ApiError && (error.status === 403 || error.status === 404);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (busyRef.current || stale || unavailable) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const content = {
        ...form,
        recruitmentDate: form.category === "동행" ? form.recruitmentDate : null,
        capacity: form.category === "동행" ? form.capacity : null,
      };
      const saved = await api<Post>(
        "/community/posts" + (initial ? "/" + initial.id : ""),
        {
          method: initial ? "PUT" : "POST",
          body: initial ? { version: initial.version, content } : content,
        },
      );
      await done(saved);
    } catch (e) {
      setError(e as Error);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }
  return (
    <Modal
      title={initial ? "여행 이야기 수정하기" : "여행 이야기 나누기"}
      onClose={safeClose}
      className="post-editor-modal"
    >
      <p className="post-editor-intro">
        {initial
          ? "제목·내용과 여행 조건을 다듬어요. 댓글과 개인 대화는 유지되고, 모집 상태는 별도로 관리해요."
          : "공개되는 이야기예요. 연락처와 예약 번호는 적지 않고 비공개 대화로 나눠 주세요."}
      </p>
      <form onSubmit={submit}>
        <fieldset disabled={busy} className="post-editor-fields">
          <div className="form-grid">
            <label>
              이야기 종류
              <select
                value={form.category}
                disabled={!!initial}
                onChange={(e) => setForm({ ...form, category: e.target.value })}
              >
                {["후기", "추천", "코스", "동행", "의견"].map((c) => (
                  <option key={c}>{c}</option>
                ))}
              </select>
              {initial && (
                <span className="field-help">
                  등록한 종류는 유지해요. 다른 이야기는 새 글로 나눠 주세요.
                </span>
              )}
            </label>
            <label>
              여행 지역
              <input
                value={form.region}
                onChange={(e) => setForm({ ...form, region: e.target.value })}
                maxLength={50}
                required
              />
            </label>
          </div>
          <label>
            제목
            <input
              value={form.title}
              onChange={(e) => setForm({ ...form, title: e.target.value })}
              placeholder="어떤 이야기를 나누고 싶나요?"
              maxLength={120}
              required
            />
          </label>
          {form.category === "동행" && (
            <div className="form-grid">
              <label>
                여행 날짜
                <input
                  type="date"
                  value={form.recruitmentDate}
                  onChange={(e) =>
                    setForm({ ...form, recruitmentDate: e.target.value })
                  }
                  onInput={(e) =>
                    setForm({ ...form, recruitmentDate: e.currentTarget.value })
                  }
                  required
                />
              </label>
              <label>
                모집 인원
                <input
                  type="number"
                  min={1}
                  max={20}
                  step={1}
                  value={form.capacity}
                  onChange={(e) =>
                    setForm({ ...form, capacity: Number(e.target.value) })
                  }
                  required
                />
              </label>
            </div>
          )}
          <label>
            내용
            <textarea
              rows={7}
              value={form.body}
              onChange={(e) => setForm({ ...form, body: e.target.value })}
              maxLength={10000}
              required
              placeholder={
                form.category === "동행"
                  ? "예산, 이동 방식, 여행 스타일과 함께 조율할 조건을 적어 주세요."
                  : "추천하는 이유와 직접 경험한 내용을 들려주세요."
              }
            />
          </label>
        </fieldset>
        <p className="field-help">
          {form.body.length.toLocaleString()} / 10,000자 ·{" "}
          {initial
            ? "변경 내용은 저장한 뒤 공개돼요."
            : "이야기 종류는 등록 후 바꿀 수 없어요."}
        </p>
        {error && <ErrorState error={error} />}
        {stale && (
          <div className="notice danger" role="status">
            다른 곳에서 글이 변경됐어요. 작성 중인 내용을 복사해 둔 뒤 최신 글로
            다시 작성해 주세요.
          </div>
        )}
        <div className="post-form-actions">
          {stale ? (
            <button
              type="button"
              className="button secondary"
              onClick={reload}
              disabled={busy}
            >
              최신 글로 다시 작성하기
            </button>
          ) : (
            <button
              className="button primary"
              disabled={
                busy ||
                unavailable ||
                !form.title.trim() ||
                !form.body.trim() ||
                !form.region.trim()
              }
            >
              {busy ? (
                <Loader2 size={17} className="spin" />
              ) : (
                <Save size={17} />
              )}{" "}
              {busy
                ? "저장하고 있어요"
                : initial
                  ? "변경 내용 저장하기"
                  : "이야기 등록하기"}
            </button>
          )}
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={safeClose}
          >
            돌아가기
          </button>
        </div>
      </form>
    </Modal>
  );
}
