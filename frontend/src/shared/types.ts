/** 서버와 공유하는 화면 데이터 계약. 예약 확인 출처와 장소 출처를 명시적으로 보존합니다. */
export type Member = {
  id: string;
  email: string;
  nickname: string;
  role: "USER" | "ADMIN";
  verified: boolean;
  preferences: string[];
  dmScope: "ALL" | "COMPANIONS" | "OFF";
};
export type Config = {
  mapKey: string;
  aiConfigured: boolean;
  sampleData: boolean;
  emailConfigured: boolean;
  socialLoginConfigured: boolean;
};
export type Place = {
  id: string;
  name: string;
  region: string;
  category: string;
  latitude: number;
  longitude: number;
  duration: number;
  cost: number;
  indoor: boolean;
  source: string;
  url: string;
};
export type Conditions = {
  title: string;
  regions: string[];
  startDate: string;
  endDate: string;
  origin: string;
  partySize: number;
  budget: number;
  transport: "TRANSIT" | "CAR" | "WALK";
  preferences: string[];
  lodging: string;
  lodgingIds: string[];
};
export type Stop = {
  id: string;
  dayIndex: number;
  sortOrder: number;
  place: Place;
  startTime: string;
  fixed: boolean;
  bookingStatus: string;
};
export type Course = {
  variant: number;
  title: string;
  subtitle: string;
  engine: string;
  items: Stop[];
  estimatedCost: number;
  travelMinutes: number;
  warnings: string[];
  lodgings: Place[];
  lodgingEstimate: number;
};
export type Item = {
  id: string;
  dayIndex: number;
  sortOrder: number;
  placeId: string;
  placeName: string;
  region: string;
  category: string;
  latitude: number;
  longitude: number;
  startTime: string;
  duration: number;
  cost: number;
  indoor: boolean;
  source: string;
  placeUrl: string;
  fixed: boolean;
  bookingStatus: string;
  bookingReason: string;
};
export type Trip = Omit<Conditions, "lodgingIds"> & {
  id: string;
  ownerId: string;
  version: number;
  externalBookings: ExternalBooking[];
  schedule: BookingScheduleReport;
  routeTimings: RouteTiming[];
  lodgings: {
    placeId: string;
    region: string;
    name: string;
    nightlyEstimate: number;
    source: string;
    placeUrl: string;
    bookingStatus: string;
  }[];
  items: Item[];
  members: { id: string; nickname: string; preferences: string[] }[];
  tasks: { id: string; content: string; done: boolean }[];
  expenses: { id: string; label: string; amount: number; nickname: string }[];
  history: {
    id: string;
    summary: string;
    version: number;
    nickname: string;
    createdAt: string;
  }[];
};
export type Comparison = {
  items: Item[];
  estimatedCost: number;
  costDelta: number;
  travelDelta: number;
  canApply: boolean;
  conflicts: string[];
  summary: string;
};
export type Post = {
  id: string;
  authorId: string;
  nickname: string;
  category: string;
  region: string;
  title: string;
  body: string;
  createdAt: string;
  recruitmentDate?: string;
  capacity?: number;
  status: string;
  comments?: {
    id: string;
    authorId: string;
    nickname: string;
    body: string;
    createdAt: string;
  }[];
};
export type Room = {
  id: string;
  requesterId: string;
  recipientId: string;
  requesterName: string;
  recipientName: string;
  status: string;
  tripId?: string;
  messages?: {
    id: string;
    senderId: string;
    nickname: string;
    body: string;
    createdAt: string;
  }[];
};

/** 외부 예약은 자동 확인 여부를 구분하기 위해 별도의 상태를 유지합니다. */
export type ExternalBooking = {
  id: string;
  label: string;
  category: string;
  departure: string;
  arrival: string | null;
  departurePlace: string;
  arrivalPlace: string;
  beforeMinutes: number;
  afterMinutes: number;
  scheduleLinked: boolean;
  amount: number | null;
  status: string;
  reason: string;
};

/** 예약 시각은 한국 현지 시각입니다. 서버가 확인한 기록과 입력한 여유 시간으로 계산한 결과만 표시합니다. */
export type BookingScheduleReport = {
  windows: {
    bookingId: string;
    label: string;
    departure: string;
    arrival: string;
    leaveBy: string;
    resumeAt: string;
    departurePlace: string;
    arrivalPlace: string;
  }[];
  issues: {
    kind: string;
    bookingIds: string[];
    itemIds: string[];
    message: string;
  }[];
  unlinkedCount: number;
};
export type SchedulePlan = {
  strategy: "EARLIER" | "LATER";
  title: string;
  canApply: boolean;
  changes: {
    itemId: string;
    name: string;
    dayIndex: number;
    before: string;
    after: string;
  }[];
  totalShiftMinutes: number;
  conflicts: string[];
  items: Item[];
};

/** 경로 조회값은 일정의 추정 시간/활동비와 분리합니다. null 요금은 미제공이며 무료를 뜻하지 않습니다. */
export type TransportPoint = {
  id: string;
  label: string;
  latitude: number;
  longitude: number;
};
export type TransportOption = {
  id: string;
  mode: Conditions["transport"];
  distanceMeters: number;
  minutes: number;
  transfers: number | null;
  fare: number | null;
  toll: number | null;
  walkingMinutes: number | null;
  steps: {
    type: string;
    guidance: string;
    minutes: number;
    vehicles: string[];
  }[];
  paths: { latitude: number; longitude: number }[][];
};
export type TransportResult = {
  from: TransportPoint;
  to: TransportPoint;
  mode: Conditions["transport"];
  queriedAt: string;
  source: string;
  options: TransportOption[];
  externalUrl: string;
  quoteToken: string;
};
export type SelectedRoute = {
  result: TransportResult;
  option: TransportOption;
};

/** 명시적으로 적용한 구간만 보존합니다. 다른 구간의 추정값이나 예약 확정과 구분합니다. */
export type RouteTiming = {
  fromKind: "ITEM" | "LODGING";
  fromId: string;
  toItemId: string;
  dayIndex: number;
  mode: Conditions["transport"];
  minutes: number;
  bufferMinutes: number;
  queriedAt: string;
  source: string;
  /** 숙소 출발은 HH:mm, 전날→다음 날 구간은 한국 현지 yyyy-MM-ddTHH:mm입니다. 같은 날 방문 사이는 null입니다. */
  originDeparture: string | null;
};
export type RouteTimingPlan = {
  strategy: "DEPARTURE" | "ARRIVAL";
  title: string;
  canApply: boolean;
  departure: string;
  arrival: string;
  changes: SchedulePlan["changes"];
  conflicts: string[];
  items: Item[];
  timing: RouteTiming;
};
