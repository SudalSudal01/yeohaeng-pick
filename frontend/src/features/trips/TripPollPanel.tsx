import { useCallback, useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Archive, Plus, Vote } from "lucide-react";
import { accessEnded, api, ApiError } from "../../shared/api";
import { useToast } from "../../shared/providers";
import type { TripPoll } from "../../shared/types";
import { ErrorState, Loading } from "../../shared/ui";
import TripPollCard from "./TripPollCard";
import TripPollCreateModal, {
  type CreateTripPollBody,
} from "./TripPollCreateModal";
import { groupTripPolls } from "./tripPolls";
import "./tripPolls.css";

/** 인증·참여 권한이 끝난 응답에는 캐시된 비공개 투표를 계속 표시하지 않습니다. */
const pollAccessLost = (error: unknown) =>
  accessEnded(error) || (error instanceof ApiError && error.status === 401);

/**
 * 투표는 여행 일정 version과 별도로 동기화합니다. 동행의 표 때문에 일정 비교·예약 편집이
 * 충돌하지 않게 하고, 화면을 열어 둔 동안에는 15초마다 새 표와 마감 상태를 확인합니다.
 */
export default function TripPollPanel({ tripId }: { tripId: string }) {
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [mutationAccessFailure, setMutationAccessFailure] = useState<{
    tripId: string;
    error: unknown;
  } | null>(null);
  const mutationAccessError =
    mutationAccessFailure?.tripId === tripId
      ? mutationAccessFailure.error
      : null;
  const closeCreate = useCallback(() => setCreating(false), []);
  const query = useQuery({
    queryKey: ["trip-polls", tripId],
    queryFn: () => api<TripPoll[]>(`/trips/${tripId}/polls`),
    enabled: mutationAccessError == null,
    refetchInterval: (current) =>
      mutationAccessError || pollAccessLost(current.state.error)
        ? false
        : 15000,
    retry: false,
    gcTime: 0,
  });
  const grouped = groupTripPolls(query.data ?? []);
  const privateAccessLost =
    mutationAccessError != null || pollAccessLost(query.error);
  const privateAccessError = mutationAccessError ?? query.error;

  useEffect(() => {
    if (!pollAccessLost(query.error)) return;
    // 접근 종료를 한 번 확인한 뒤에는 창 포커스·네트워크 재연결의 후속 실패가
    // 원래 오류를 덮더라도 비공개 캐시를 다시 보여 주지 않습니다.
    setMutationAccessFailure((current) =>
      current?.tripId === tripId ? current : { tripId, error: query.error },
    );
  }, [query.error, tripId]);

  async function retryPrivateAccess() {
    const result = await query.refetch();
    if (!result.error)
      setMutationAccessFailure((current) =>
        current?.tripId === tripId ? null : current,
      );
  }

  async function refreshAfterChange(error?: unknown) {
    if (error && pollAccessLost(error)) {
      // 변경 요청에서 401·403·404를 받으면 재조회보다 먼저 비공개 결과를 가립니다.
      // 403은 소유권 변경, 404는 다른 창의 투표 삭제일 수도 있어 바로 재조회하되,
      // 그 재조회가 네트워크 오류로 끝나도 이미 확인한 접근 종료 상태는 유지합니다.
      setMutationAccessFailure({ tripId, error });
      const result = await query.refetch();
      if (!result.error)
        setMutationAccessFailure((current) =>
          current?.tripId === tripId ? null : current,
        );
      return;
    }
    // 성공 뒤 최신 집계를 받고, 409 충돌이면 현재 상태로 초안을 다시 맞춥니다.
    if (!error || (error instanceof ApiError && error.status === 409))
      await query.refetch();
  }

  function showMutationError(error: unknown) {
    // 상태 변경 재조회로 카드나 모달이 접혀도 실패 이유가 사라지지 않게 전역 알림에도 남깁니다.
    toast(
      error instanceof Error ? error.message : "투표를 처리하지 못했어요.",
      true,
    );
  }

  async function create(body: CreateTripPollBody) {
    try {
      await api(`/trips/${tripId}/polls`, { method: "POST", body });
      await refreshAfterChange();
      toast("동행 투표를 시작했어요.");
    } catch (error) {
      await refreshAfterChange(error);
      showMutationError(error);
      throw error;
    }
  }

  async function saveBallot(pollId: string, optionIds: string[]) {
    try {
      await api(`/trips/${tripId}/polls/${pollId}/ballot`, {
        method: "PUT",
        body: { optionIds },
      });
      await refreshAfterChange();
      toast(
        optionIds.length
          ? "내 선택을 투표에 저장했어요."
          : "내 투표를 취소했어요.",
      );
    } catch (error) {
      await refreshAfterChange(error);
      showMutationError(error);
      throw error;
    }
  }

  async function closePoll(pollId: string, version: number) {
    try {
      await api(`/trips/${tripId}/polls/${pollId}/close`, {
        method: "POST",
        body: { version },
      });
      await refreshAfterChange();
      toast("투표를 마감했어요. 결과는 계속 확인할 수 있어요.");
    } catch (error) {
      await refreshAfterChange(error);
      showMutationError(error);
      throw error;
    }
  }

  async function deletePoll(pollId: string, version: number) {
    try {
      await api(`/trips/${tripId}/polls/${pollId}`, {
        method: "DELETE",
        body: { version },
      });
      await refreshAfterChange();
      toast("투표를 삭제했어요.");
    } catch (error) {
      await refreshAfterChange(error);
      showMutationError(error);
      throw error;
    }
  }

  return (
    <section
      className="panel trip-poll-panel"
      aria-labelledby="trip-poll-heading"
    >
      <div className="trip-poll-panel-heading">
        <div>
          <h2 id="trip-poll-heading">
            <Vote size={22} />
            함께 결정하는 투표
          </h2>
          <p>
            동행 모두가 질문을 만들고 선택할 수 있어요. 결과를 확인한 뒤 일정은
            직접 바꿔 주세요.
          </p>
        </div>
        <button
          type="button"
          className="button primary"
          onClick={() => setCreating(true)}
          disabled={
            query.isPending ||
            privateAccessLost ||
            (!query.data && !!query.error)
          }
        >
          <Plus size={17} />새 투표
        </button>
      </div>

      {privateAccessLost ? (
        <div className="trip-poll-query-error">
          <ErrorState
            error={privateAccessError}
            retry={() => void retryPrivateAccess()}
          />
        </div>
      ) : query.error && query.data ? (
        <div className="trip-poll-query-error trip-poll-refresh-error">
          <ErrorState error={query.error} retry={() => void query.refetch()} />
          <p>
            마지막으로 불러온 투표는 계속 볼 수 있어요. 저장 전 선택도 그대로
            유지했어요.
          </p>
        </div>
      ) : query.isPending ? (
        <Loading text="동행 투표를 불러오고 있어요" />
      ) : query.error && !query.data ? (
        <div className="trip-poll-query-error">
          <ErrorState error={query.error} retry={() => void query.refetch()} />
        </div>
      ) : !query.data?.length ? (
        <div className="trip-poll-empty">
          <span className="trip-poll-empty-icon">
            <Vote size={27} />
          </span>
          <h3>아직 만든 투표가 없어요</h3>
          <p>
            식당, 방문 장소, 출발 시간처럼 함께 정할 내용을 첫 투표로 올려
            보세요.
          </p>
          <button
            type="button"
            className="button secondary"
            onClick={() => setCreating(true)}
          >
            <Plus size={17} />첫 투표 만들기
          </button>
        </div>
      ) : (
        <>
          {grouped.open.length ? (
            <div className="trip-poll-list" aria-label="진행 중인 투표">
              {grouped.open.map((poll) => (
                <TripPollCard
                  key={poll.id}
                  poll={poll}
                  saveBallot={saveBallot}
                  closePoll={closePoll}
                  deletePoll={deletePoll}
                />
              ))}
            </div>
          ) : (
            <div className="trip-poll-no-open" role="status">
              현재 진행 중인 투표가 없어요. 아래에서 지난 결과를 확인하거나 새
              투표를 시작해 주세요.
            </div>
          )}

          {grouped.finished.length > 0 && (
            <details className="trip-poll-archive">
              <summary>
                <Archive size={17} />
                마감된 투표 {grouped.finished.length}개
              </summary>
              <div className="trip-poll-list" aria-label="마감된 투표">
                {grouped.finished.map((poll) => (
                  <TripPollCard
                    key={poll.id}
                    poll={poll}
                    saveBallot={saveBallot}
                    closePoll={closePoll}
                    deletePoll={deletePoll}
                  />
                ))}
              </div>
            </details>
          )}
        </>
      )}

      {creating && !privateAccessLost && (
        <TripPollCreateModal close={closeCreate} create={create} />
      )}
    </section>
  );
}
