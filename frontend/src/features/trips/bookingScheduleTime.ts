/** 한국 현지 시각을 그대로 표시합니다. Date의 UTC 변환으로 날짜가 달라지지 않도록 문자열을 사용합니다. */
export function scheduleTime(value: string) {
  return value.slice(5, 10).replace("-", "/") + " " + value.slice(11, 16);
}
