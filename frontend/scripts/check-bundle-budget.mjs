import { readFileSync, readdirSync, statSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const dist = join(root, "dist");
const html = readFileSync(join(dist, "index.html"), "utf8");

// 첫 HTML이 직접 요청하거나 modulepreload하는 JS만 합산합니다. 사용자가 다른 화면을 열 때 받는 lazy 청크는 초기 예산에 넣지 않습니다.
const initialAssets = [
  ...html.matchAll(
    /<(?:script|link)[^>]+(?:src|href)="\/assets\/([^"?]+\.js)"/g,
  ),
].map((match) => match[1]);
const uniqueInitialAssets = [...new Set(initialAssets)];

if (!uniqueInitialAssets.length) {
  throw new Error("빌드된 첫 화면 JavaScript 파일을 찾지 못했어요.");
}

const sizeOf = (name) => {
  const path = join(dist, "assets", name);
  const content = readFileSync(path);
  return { name, raw: statSync(path).size, gzip: gzipSync(content).length };
};
const sizes = uniqueInitialAssets.map(sizeOf);
const allJavaScript = readdirSync(join(dist, "assets"))
  .filter((name) => name.endsWith(".js"))
  .map(sizeOf);
const lazyJavaScript = allJavaScript.filter(
  ({ name }) => !uniqueInitialAssets.includes(name),
);
const raw = sizes.reduce((sum, asset) => sum + asset.raw, 0);
const gzip = sizes.reduce((sum, asset) => sum + asset.gzip, 0);

// 측정 당시 326KB/105KB에서 작은 해시·라이브러리 변동 여유만 둡니다. 특정 지연 화면 하나가 다시 비대해지는 회귀도 별도로 막습니다.
const budget = {
  initialRaw: 340_000,
  initialGzip: 112_000,
  anyChunkRaw: 500_000,
  lazyChunkRaw: 180_000,
};
console.table(sizes);
console.log(
  `초기 JavaScript 합계: ${(raw / 1000).toFixed(2)} kB / gzip ${(gzip / 1000).toFixed(2)} kB`,
);

if (raw > budget.initialRaw || gzip > budget.initialGzip) {
  throw new Error(
    `초기 번들 예산을 넘었어요. 기준은 ${budget.initialRaw / 1000} kB / gzip ${budget.initialGzip / 1000} kB입니다.`,
  );
}

// Vite의 큰 청크 경고와 관계없이 빌드를 실패시켜 CI에서도 회귀를 놓치지 않습니다. 지연 화면은 모바일에서 진입할 때 기다리므로 더 작은 기준을 적용합니다.
const oversizedChunks = allJavaScript.filter(
  ({ raw: chunkRaw }) => chunkRaw > budget.anyChunkRaw,
);
const oversizedLazyChunks = lazyJavaScript.filter(
  ({ raw: chunkRaw }) => chunkRaw > budget.lazyChunkRaw,
);

if (oversizedChunks.length || oversizedLazyChunks.length) {
  const offenders = [...oversizedChunks, ...oversizedLazyChunks]
    .filter(
      (asset, index, items) =>
        items.findIndex(({ name }) => name === asset.name) === index,
    )
    .map(
      ({ name, raw: chunkRaw }) => `${name} ${(chunkRaw / 1000).toFixed(2)} kB`,
    )
    .join(", ");
  throw new Error(
    `화면 청크 예산을 넘었어요: ${offenders}. 모든 청크는 ${budget.anyChunkRaw / 1000} kB, 지연 청크는 ${budget.lazyChunkRaw / 1000} kB 이하여야 합니다.`,
  );
}
