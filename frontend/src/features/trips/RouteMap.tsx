import { useEffect, useRef } from "react";
import type { Item } from "../../shared/types";
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
  Marker: new (opts: unknown) => unknown;
  LatLngBounds: new () => { extend: (position: unknown) => void };
  Polyline: new (opts: unknown) => unknown;
};
declare global {
  interface Window {
    kakao?: { maps: KakaoMapSdk };
  }
}

/** 지도 SDK가 없으면 실제 지도를 흉내 내지 않고 좌표 기반 동선 도식을 제공합니다. 위치 권한은 요청하지 않습니다. */
export default function RouteMap({ items }: { items: Item[] }) {
  const host = useRef<HTMLDivElement>(null);
  const config = useConfig();
  const key = config.data?.mapKey;
  useEffect(() => {
    if (!key || !host.current || !items.length) return;
    let cancelled = false;
    const render = () =>
      window.kakao?.maps.load(() => {
        if (cancelled || !host.current || !window.kakao) return;
        const sdk = window.kakao.maps;
        const map = new sdk.Map(host.current, {
          center: new sdk.LatLng(items[0].latitude, items[0].longitude),
          level: 6,
        });
        const bounds = new sdk.LatLngBounds();
        const path = items.map((i) => new sdk.LatLng(i.latitude, i.longitude));
        path.forEach((position) => {
          new sdk.Marker({ position, map });
          bounds.extend(position);
        });
        new sdk.Polyline({
          map,
          path,
          strokeWeight: 4,
          strokeColor: "#247463",
          strokeOpacity: 0.8,
        });
        map.setBounds(bounds);
      });
    if (window.kakao) render();
    else {
      const script = document.createElement("script");
      script.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(key)}&autoload=false`;
      script.onload = render;
      document.head.appendChild(script);
      return () => {
        cancelled = true;
      };
    }
    return () => {
      cancelled = true;
    };
  }, [key, items]);
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
    <div className="route-map-panel">
      <div className="route-map-heading">
        <strong>
          <MapPin size={17} />
          {key ? "여행 지도" : "동선 미리보기"}
        </strong>
        <span className="badge subtle">
          {key ? "카카오맵" : "좌표 기반 도식"}
        </span>
      </div>
      {key ? (
        <div className="kakao-map" ref={host} />
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
      <div className="map-legend">
        {items.map((i, n) => (
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
