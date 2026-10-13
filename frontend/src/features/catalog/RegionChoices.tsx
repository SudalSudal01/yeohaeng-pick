import { MapPin } from "lucide-react";
import { useConfig } from "../../shared/providers";
import { ErrorState, Loading } from "../../shared/ui";

/**
 * 검색과 여행 만들기가 같은 서버 지역 목록을 사용합니다. 초기 로딩·오류를 명시해
 * 지역 버튼이 사라진 상태를 정상적인 빈 목록으로 오해하지 않도록 합니다.
 */
export default function RegionChoices({
  selected,
  onSelect,
  ordered = false,
}: {
  selected: string[];
  onSelect: (region: string) => void;
  ordered?: boolean;
}) {
  const config = useConfig();
  if (config.isPending) return <Loading text="여행 지역을 불러오고 있어요" />;
  if (config.error)
    return <ErrorState error={config.error} retry={() => config.refetch()} />;
  return (
    <div
      className={ordered ? "region-choices" : "filter-row"}
      role="group"
      aria-label={ordered ? "여행 지역 선택" : "탐색 지역 선택"}
    >
      {!ordered && (
        <span className="filter-label">
          <MapPin size={17} />
          지역
        </span>
      )}
      {config.data.regions.map((region) => (
        <button
          type="button"
          key={region}
          className={
            (ordered ? "region-choice " : "chip ") +
            (selected.includes(region) ? "selected" : "")
          }
          aria-pressed={selected.includes(region)}
          onClick={() => onSelect(region)}
        >
          {ordered ? (
            <>
              <MapPin size={20} />
              <strong>{region}</strong>
              {selected.includes(region) && (
                <span aria-label={`방문 순서 ${selected.indexOf(region) + 1}`}>
                  {selected.indexOf(region) + 1}
                </span>
              )}
            </>
          ) : (
            region
          )}
        </button>
      ))}
    </div>
  );
}
