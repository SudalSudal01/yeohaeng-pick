import { useCallback, useEffect, useRef, useState } from "react";
import { ListChecks, Loader2, Plus, Trash2 } from "lucide-react";
import { ErrorState, Modal } from "../../shared/ui";
import type { TripPoll } from "../../shared/types";
import { kstDateTimeInputValue, validateTripPollDraft } from "./tripPolls";

type OptionInput = { key: number; label: string };

export type CreateTripPollBody = {
  title: string;
  selectionType: TripPoll["selectionType"];
  options: { label: string }[];
  closesAt: string | null;
};

/** 선택지 입력은 안정적인 key를 사용해 중간 항목을 지워도 다른 입력의 포커스와 내용이 바뀌지 않게 합니다. */
export default function TripPollCreateModal({
  close,
  create,
}: {
  close: () => void;
  create: (body: CreateTripPollBody) => Promise<void>;
}) {
  const nextKey = useRef(3);
  const busyRef = useRef(false);
  const optionInputs = useRef(new Map<number, HTMLInputElement>());
  const focusAfterRemoval = useRef<number | null>(null);
  const [title, setTitle] = useState("");
  const [selectionType, setSelectionType] =
    useState<TripPoll["selectionType"]>("SINGLE");
  const [options, setOptions] = useState<OptionInput[]>([
    { key: 1, label: "" },
    { key: 2, label: "" },
  ]);
  const [closesAt, setClosesAt] = useState("");
  const [validation, setValidation] = useState("");
  const [error, setError] = useState<Error | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (focusAfterRemoval.current == null) return;
    // 삭제한 위치의 다음 입력을 우선하고 마지막 항목이었다면 바로 앞 입력으로 이동합니다.
    // 키보드 사용자의 포커스가 제거된 DOM과 함께 페이지 본문으로 빠지지 않게 합니다.
    optionInputs.current.get(focusAfterRemoval.current)?.focus();
    focusAfterRemoval.current = null;
  }, [options]);

  const safeClose = useCallback(() => {
    // 저장 중 창이 닫히면 성공 여부를 확인하지 못하므로 응답을 받은 뒤에만 닫을 수 있게 합니다.
    if (!busyRef.current) close();
  }, [close]);

  function clearFeedback() {
    // 사용자가 문제를 고치기 시작하면 이미 해결 중인 검증·서버 오류를 화면에 남겨 혼란을 주지 않습니다.
    setValidation("");
    setError(null);
  }

  function changeOption(key: number, label: string) {
    setOptions((current) =>
      current.map((option) =>
        option.key === key ? { ...option, label } : option,
      ),
    );
    clearFeedback();
  }

  function removeOption(key: number) {
    setOptions((current) => {
      if (current.length <= 2) return current;
      const removedIndex = current.findIndex((option) => option.key === key);
      if (removedIndex < 0) return current;
      const remaining = current.filter((option) => option.key !== key);
      focusAfterRemoval.current =
        remaining[Math.min(removedIndex, remaining.length - 1)]?.key ?? null;
      return remaining;
    });
    clearFeedback();
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (busyRef.current) return;
    const draft = {
      title,
      selectionType,
      options: options.map((option) => option.label),
      closesAt,
    };
    const invalid = validateTripPollDraft(draft);
    if (invalid) {
      setValidation(invalid);
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setError(null);
    setValidation("");
    try {
      await create({
        title: title.trim(),
        selectionType,
        options: options.map((option) => ({ label: option.label.trim() })),
        // 빈 문자열과 null을 구분하는 서버 계약에 맞춰, 마감 없음은 명시적으로 null을 보냅니다.
        closesAt: closesAt || null,
      });
      close();
    } catch (caught) {
      setError(caught as Error);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  }

  return (
    <Modal
      title="새 동행 투표 만들기"
      onClose={safeClose}
      className="trip-poll-create-modal"
    >
      <form className="trip-poll-create-form" onSubmit={submit}>
        <p className="field-help">
          일정과 예약은 투표만으로 바뀌지 않아요. 결과를 확인한 뒤 필요한 변경을
          직접 반영해 주세요.
        </p>
        <label className="trip-poll-title-field">
          함께 정할 질문
          <textarea
            value={title}
            onChange={(event) => {
              setTitle(event.target.value);
              clearFeedback();
            }}
            rows={3}
            maxLength={120}
            placeholder="예: 첫날 저녁은 어디에서 먹을까요?"
            aria-describedby="trip-poll-title-help"
            disabled={busy}
            required
          />
          <span id="trip-poll-title-help" className="poll-field-meta">
            질문 {title.length} / 120자
          </span>
        </label>

        <fieldset className="trip-poll-type-field">
          <legend>선택 방식</legend>
          <div className="trip-poll-type-options">
            <label className={selectionType === "SINGLE" ? "selected" : ""}>
              <input
                type="radio"
                name="trip-poll-selection-type"
                value="SINGLE"
                checked={selectionType === "SINGLE"}
                onChange={() => {
                  setSelectionType("SINGLE");
                  clearFeedback();
                }}
                disabled={busy}
              />
              <span>
                <strong>하나만 선택</strong>
                <small>가장 마음에 드는 한 곳을 골라요.</small>
              </span>
            </label>
            <label className={selectionType === "MULTIPLE" ? "selected" : ""}>
              <input
                type="radio"
                name="trip-poll-selection-type"
                value="MULTIPLE"
                checked={selectionType === "MULTIPLE"}
                onChange={() => {
                  setSelectionType("MULTIPLE");
                  clearFeedback();
                }}
                disabled={busy}
              />
              <span>
                <strong>여러 개 선택</strong>
                <small>가능한 후보를 모두 표시할 수 있어요.</small>
              </span>
            </label>
          </div>
        </fieldset>

        <fieldset className="trip-poll-option-field">
          <legend>선택지</legend>
          <p className="field-help">2개부터 10개까지 만들 수 있어요.</p>
          <div className="trip-poll-option-inputs">
            {options.map((option, index) => (
              <div className="trip-poll-option-input" key={option.key}>
                <label>
                  <span className="sr-only">선택지 {index + 1}</span>
                  <input
                    ref={(node) => {
                      if (node) optionInputs.current.set(option.key, node);
                      else optionInputs.current.delete(option.key);
                    }}
                    value={option.label}
                    onChange={(event) =>
                      changeOption(option.key, event.target.value)
                    }
                    placeholder={`선택지 ${index + 1}`}
                    maxLength={100}
                    disabled={busy}
                    required
                  />
                </label>
                <button
                  type="button"
                  className="icon-button outlined"
                  onClick={() => removeOption(option.key)}
                  disabled={busy || options.length <= 2}
                  aria-label={`선택지 ${index + 1} 삭제`}
                  title={
                    options.length <= 2
                      ? "선택지는 두 개 이상 필요해요."
                      : undefined
                  }
                >
                  <Trash2 size={17} />
                </button>
              </div>
            ))}
          </div>
          <button
            type="button"
            className="button secondary trip-poll-add-option"
            onClick={() => {
              if (options.length >= 10) return;
              setOptions((current) => [
                ...current,
                { key: nextKey.current++, label: "" },
              ]);
              clearFeedback();
            }}
            disabled={busy || options.length >= 10}
          >
            <Plus size={17} />
            선택지 추가 ({options.length}/10)
          </button>
        </fieldset>

        <label>
          마감 시각 <span className="muted">· 한국 시간, 선택 사항</span>
          <input
            type="datetime-local"
            value={closesAt}
            min={kstDateTimeInputValue(new Date())}
            onChange={(event) => {
              setClosesAt(event.target.value);
              clearFeedback();
            }}
            disabled={busy}
            aria-describedby="trip-poll-close-help"
          />
          <span id="trip-poll-close-help" className="field-help">
            정하지 않으면 투표 관리자가 직접 마감할 때까지 열려 있어요.
          </span>
        </label>

        {validation && (
          <p className="field-error" role="alert">
            {validation}
          </p>
        )}
        {error && <ErrorState error={error} />}

        <div className="trip-poll-create-actions">
          <button className="button primary" disabled={busy}>
            {busy ? (
              <Loader2 size={18} className="spin" />
            ) : (
              <ListChecks size={18} />
            )}
            {busy ? "투표를 만들고 있어요" : "투표 시작하기"}
          </button>
          <button
            type="button"
            className="button secondary"
            onClick={safeClose}
            disabled={busy}
          >
            취소
          </button>
        </div>
      </form>
    </Modal>
  );
}
