import { useEffect, useRef, useState } from "react";
import type { Item, SelectedRoute } from "../../shared/types";
import { useConfig } from "../../shared/providers";
import { MapPin, ArrowUpRight } from "lucide-react";

// SDK를 전역으로 넓게 any 처리하지 않고 필요한 표면만 정의합니다.
type KakaoMapSdk = {
  load: (callback: () => void) => void;
  LatLng: new (lat: number, lon: number) => unknown;
  Map: new (
    el: HTMLElement,
    opts: unknown,
  ) => { setBounds: (bounds: unknown) => void };
  Marker: new (opts: unknown) => { setMap: (map: unknown) => void };
  LatLngBounds: new () => { extend: (position: unknown) => void };
  Polyline: new (opts: unknown) => { setMap: (map: unknown) => void };
};
declare global {
  interface Window {
    kakao?: { maps: KakaoMapSdk };
  }
}

let sdkLoading: Promise<KakaoMapSdk> | undefined;
/** 지도와 경로를 바꿔도 SDK 스크립트를 중복 삽입하지 않습니다. 실패하면 빈 지도 대신 다시 시도할 수 있습니다. */
function loadSdk(key: string) {
  if (sdkLoading) return sdkLoading;
  sdkLoading = new Promise<KakaoMapSdk>((resolve, reject) => {
    const timer = window.setTimeout(
      () => reject(new Error("지도 연결 시간이 초과됐어요.")),
      15000,
    );
    const ready = () =>
      window.kakao?.maps.load(() => {
        window.clearTimeout(timer);
        resolve(window.kakao!.maps);
      });
    if (window.kakao) {
      ready();
      return;
    }
    const script = document.createElement("script");
    script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`;
    script.onload = ready;
    script.onerror = () => {
      window.clearTimeout(timer);
      script.remove();
      reject(new Error("지도를 불러오지 못했어요."));
    };
    document.head.appendChild(script);
  }).catch((error) => {
    sdkLoading = undefined;
    throw error;
  });
  return sdkLoading;
}

/** 지도 SDK가 없으면 실제 지도를 흉내 내지 않고 좌표 기반 동선 도식을 제공합니다. 위치 권한은 요청하지 않습니다. */
export default function RouteMap({
  items,
  route,
  onClear,
}: {
  items: Item[];
  route?: SelectedRoute | null;
  onClear?: () => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const mapRef = useRef<InstanceType<KakaoMapSdk["Map"]> | null>(null);
  const overlays = useRef<{ setMap: (map: unknown) => void }[]>([]);
  const [state, setState] = useState("loading");
  const [retry, setRetry] = useState(0);
  const config = useConfig();
  const key = config.data?.mapKey;
  useEffect(() => {
    if (!key || !host.current || !items.length) return;
    let cancelled = false;
    setState("loading");
    loadSdk(key)
      .then((sdk) => {
        if (cancelled || !host.current) return;
        const locations = route ? [route.result.from, route.result.to] : items;
        // 경로를 바꿀 때 지도 인스턴스까지 다시 만들면 타일과 이벤트가 중첩됩니다.
        // 지도는 유지하고 이전 마커/선만 해제한 뒤 새 경로를 표시합니다.
        if (!mapRef.current) {
          host.current.replaceChildren();
          mapRef.current = new sdk.Map(host.current, {
            center: new sdk.LatLng(
              locations[0].latitude,
              locations[0].longitude,
            ),
            level: 6,
          });
        }
        const map = mapRef.current;
        overlays.current.forEach((overlay) => overlay.setMap(null));
        overlays.current = [];
        const bounds = new sdk.LatLngBounds();
        const path = locations.map(
          (i) => new sdk.LatLng(i.latitude, i.longitude),
        );
        path.forEach((position) => {
          overlays.current.push(new sdk.Marker({ position, map }));
          bounds.extend(position);
        });
        // 조회 전 점선은 방문 순서입니다. 조회 후에는 공급자가 반환한 도로/단계별 실제 선만 그립니다.
        const paths = route
          ? route.option.paths.map((points) =>
              points.map((p) => new sdk.LatLng(p.latitude, p.longitude)),
            )
          : [path];
        paths.forEach((segment) => {
          segment.forEach((position) => bounds.extend(position));
          overlays.current.push(
            new sdk.Polyline({
              map,
              path: segment,
              strokeWeight: 4,
              strokeColor: "#247463",
              strokeOpacity: 0.8,
              strokeStyle: route ? "solid" : "dash",
            }),
          );
        });
        map.setBounds(bounds);
        setState("ready");
      })
      .catch(() => {
        if (!cancelled) setState("error");
      });
    return () => {
      cancelled = true;
    };
  }, [key, items, route, retry]);
  const xs = items.map((i) => i.longitude),
    ys = items.map((i) => i.latitude);
  const minX = Math.min(...xs),
    maxX = Math.max(...xs),
    minY = Math.min(...ys),
    maxY = Math.max(...ys);
  const points = items.map((i) => ({
    x: 60 + ((i.longitude - minX) / Math.max(maxX - minX, 0.01)) * 280,
    y: 340 - ((i.latitude - minY) / Math.max(maxY - minY, 0.01)) * 260,
  }));
  return (
    <div className="route-map-panel" id="trip-route-map">
      <div className="route-map-heading">
        <strong>
          <MapPin size={17} />
          {key ? (route ? "선택한 이동 경로" : "여행 지도") : "동선 미리보기"}
        </strong>
        <span className="badge subtle">
          {key ? "카카오맵" : "좌표 기반 도식"}
        </span>
      </div>
      {key ? (
        <div className="map-container">
          <div
            className="kakao-map"
            ref={host}
            aria-label={route ? "조회한 이동 경로 지도" : "여행 방문 순서 지도"}
          />
          {state === "loading" && (
            <p className="map-status" role="status">
              지도를 불러오고 있어요…
            </p>
          )}
          {state === "error" && (
            <div className="map-status" role="alert">
              <p>지도를 불러오지 못했어요.</p>
              <button
                className="button secondary"
                onClick={() => setRetry((n) => n + 1)}
              >
                지도 다시 연결
              </button>
            </div>
          )}
        </div>
      ) : (
        <div className="route-diagram">
          <svg
            viewBox="0 0 400 400"
            aria-label="선택한 날짜의 좌표 기반 방문 순서"
          >
            <defs>
              <pattern
                id="grid"
                width="38"
                height="38"
                patternUnits="userSpaceOnUse"
              >
                <path
                  d="M 38 0 L 0 0 0 38"
                  fill="none"
                  stroke="#e0e9e3"
                  strokeWidth="1"
                />
              </pattern>
            </defs>
            <rect width="400" height="400" fill="url(#grid)" />
            <path
              d="M-30 90 Q100 190 160 130 T450 280"
              stroke="#c4dfdc"
              strokeWidth="38"
              fill="none"
            />
            <polyline
              points={points.map((p) => `${p.x},${p.y}`).join(" ")}
              fill="none"
              stroke="#247463"
              strokeWidth="3"
              strokeDasharray="6 5"
            />
            {points.map((p, i) => (
              <g key={items[i].id}>
                <circle
                  cx={p.x}
                  cy={p.y}
                  r="17"
                  fill={i === 0 ? "#247463" : "white"}
                  stroke="#247463"
                  strokeWidth="2"
                />
                <text
                  x={p.x}
                  y={p.y + 5}
                  textAnchor="middle"
                  fontSize="14"
                  fill={i === 0 ? "white" : "#247463"}
                  fontWeight="700"
                >
                  {i + 1}
                </text>
              </g>
            ))}
          </svg>
          <p>
            지도 키 설정 전 동선 도식이에요.
            <br />
            실제 도로와 이동 경로는 표시하지 않아요.
          </p>
        </div>
      )}
      <p className="map-route-caption small muted">
        {route
          ? route.option.paths.length
            ? `${route.option.minutes}분 · 공급자가 조회한 경로예요.`
            : "경로 좌표가 제공되지 않아 출발·도착점만 표시해요."
          : "점선은 방문 순서예요. 실제 경로는 ‘이동 경로 비교’에서 조회해 주세요."}
      </p>
      {route && (
        <div className="map-selected-route">
          <p>
            {route.result.from.label}
            <br />→ {route.result.to.label}
          </p>
          <button className="text-button" onClick={onClear}>
            전체 방문 순서로 돌아가기
          </button>
        </div>
      )}
      <div className="map-legend">
        {route
          ? [route.result.from, route.result.to].map((point, n) => (
              <a
                key={n}
                href={route.result.externalUrl}
                target="_blank"
                rel="noreferrer"
              >
                <span>{n + 1}</span>
                {n === 0 ? "출발" : "도착"} · {point.label}
                <ArrowUpRight size={14} />
              </a>
            ))
          : items.map((i, n) => (
              <a key={i.id} href={i.placeUrl} target="_blank" rel="noreferrer">
                <span>{n + 1}</span>
                {i.placeName}
                <ArrowUpRight size={14} />
              </a>
            ))}
      </div>
    </div>
  );
}
