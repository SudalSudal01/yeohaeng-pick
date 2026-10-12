/** 여행 날짜는 시간대 없는 달력 값입니다. UTC 자정끼리 계산해 윤년/월 경계/일광절약시간의 영향을 피합니다. */
const day = 86_400_000;
export function sharedTripDays(start: string, end: string) {
  return (
    Math.round(
      (Date.parse(end + "T00:00:00Z") - Date.parse(start + "T00:00:00Z")) / day,
    ) + 1
  );
}
export function importedEndDate(start: string, days: number) {
  const value = Date.parse(start + "T00:00:00Z") + (days - 1) * day;
  return Number.isFinite(value)
    ? new Date(value).toISOString().slice(0, 10)
    : "";
}
export function localToday() {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
