import { useQuery } from "@tanstack/react-query";
import { Check, Hotel, ArrowUpRight } from "lucide-react";
import { api, money } from "../../shared/api";
import type { Place } from "../../shared/types";
import { Loading, ErrorState } from "../../shared/ui";

/** 각 지역의 조회된 숙소를 비교합니다. 객실 재고·실시간 숙박 요금이 제공되지 않으면 금액을 확정하지 않습니다. */
export default function LodgingPicker({
  regions,
  selected,
  onChange,
}: {
  regions: string[];
  selected: string[];
  onChange: (ids: string[]) => void;
}) {
  const query = useQuery({
    queryKey: ["lodging-candidates", regions],
    queryFn: async () =>
      (
        await Promise.all(
          regions.map((r) =>
            api<Place[]>(
              "/catalog/places?region=" +
                encodeURIComponent(r) +
                "&query=" +
                encodeURIComponent("숙소"),
            ),
          ),
        )
      )
        .flat()
        .filter((p) => p.category === "숙소"),
  });
  if (query.isPending) return <Loading text="숙소 후보를 불러와요" />;
  if (query.error) return <ErrorState error={query.error} />;
  return (
    <div className="lodging-picker">
      <h3>
        <Hotel size={19} /> 숙소 후보도 함께 골라요
      </h3>
      <p className="small muted">
        지역별 한 곳을 선택하면 숙소와 가까운 장소부터 여행해요. 미정이면
        선택하지 않아도 괜찮아요.
      </p>
      {regions.map((region) => (
        <div className="lodging-region" key={region}>
          <strong>{region}</strong>
          {query.data
            ?.filter((p) => p.region === region)
            .map((p) => (
              <div
                className={
                  "lodging-option " +
                  (selected.includes(p.id) ? "selected" : "")
                }
                key={p.id}
              >
                <button
                  type="button"
                  aria-pressed={selected.includes(p.id)}
                  onClick={() => {
                    const others = selected.filter(
                      (id) =>
                        !query.data.some(
                          (other) => other.region === region && other.id === id,
                        ),
                    );
                    onChange(
                      selected.includes(p.id) ? others : [...others, p.id],
                    );
                  }}
                >
                  <Hotel size={22} />
                  <span>
                    <strong>{p.name}</strong>
                    <small>
                      {p.source === "SAMPLE"
                        ? money(p.cost) + " / 1객실 1박 · 예시"
                        : "객실 요금 확인 필요"}
                    </small>
                  </span>
                  {selected.includes(p.id) && <Check size={18} />}
                </button>
                <a
                  className="text-button"
                  href={p.url}
                  target="_blank"
                  rel="noreferrer"
                >
                  위치·예약 안내
                  <ArrowUpRight size={14} />
                </a>
              </div>
            ))}
          {!query.data?.some((p) => p.region === region) && (
            <p className="muted small">
              현재 검색된 숙소가 없어요. 선택 없이 여행을 준비할 수 있어요.
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
