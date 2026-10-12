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
  tourismConfigured: boolean;
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
/** 보관 당시의 공개 장소 정보입니다. 현재 요금/영업/예약 확인 결과와 구분합니다. */
export type Favorite = { place: Place; savedAt: string };
/** 관광공사 안내는 원문·출처·시각을 유지합니다. 금액/영업 가능 판정으로 자동 환산하지 않습니다. */
export type TourismContent = {
  id: string;
  typeId: string;
  category: string;
  title: string;
  address: string;
  telephone: string;
  providerUpdatedAt: string;
};
export type TourismField = {
  label: string;
  text: string;
  status: "PROVIDED" | "NOT_PROVIDED";
};
export type TourismDetail = {
  content: TourismContent;
  overview: string;
  homepageUrl: string;
  hours: TourismField;
  restDays: TourismField;
  fees: TourismField;
  additional: TourismField[];
  queriedAt: string;
  source: string;
  sourceUrl: string;
};
export type TourismSearch = {
  items: TourismContent[];
  totalCount: number;
  page: number;
  pageSize: number;
  queriedAt: string;
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
  favoriteIds?: string[];
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
  /** 방문일 기준 사용자 확인값. 금액 null은 미입력, 0은 직접 입력한 무료 예상입니다. */
  visitStart?: string | null;
  visitEnd?: string | null;
  visitCost?: number | null;
  visitNote?: string;
};
/** 공개 계약은 개인 일정 타입과 분리합니다. 예약·고정·직접 입력 비용/메모·내부 항목 ID가 들어오지 않습니다. */
export type SharedTrip = {
  title: string;
  regions: string[];
  startDate: string;
  endDate: string;
  transport: Conditions["transport"];
  snapshot: string;
  items: Pick<
    Item,
    | "dayIndex"
    | "sortOrder"
    | "placeId"
    | "placeName"
    | "region"
    | "category"
    | "latitude"
    | "longitude"
    | "startTime"
    | "duration"
    | "cost"
    | "indoor"
    | "source"
    | "placeUrl"
  >[];
};
export type Trip = Omit<Conditions, "lodgingIds"> & {
  id: string;
  ownerId: string;
  version: number;
  externalBookings: ExternalBooking[];
  schedule: BookingScheduleReport;
  routeTimings: RouteTiming[];
  lodgings: Lodging[];
  items: Item[];
  visitConditionIssues?: string[];
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
  /** 방문 복원으로 되돌릴 수 없는 소유권 변경 기록입니다. 현재 참여자에게만 제공합니다. */
  ownershipHistory: {
    id: string;
    previousOwnerId: string;
    newOwnerId: string;
    previousOwnerName: string;
    newOwnerName: string;
    version: number;
    revokedInvitations: number;
    createdAt: string;
  }[];
};
/** 숙박비는 전체 기간·전체 객실의 직접 입력 참고 금액이며 예약 성공/결제 여부와 별개입니다. */
export type Lodging = {
  placeId: string;
  region: string;
  name: string;
  nightlyEstimate: number;
  source: string;
  placeUrl: string;
  bookingStatus: string;
  checkInDate?: string | null;
  checkOutDate?: string | null;
  guestCount?: number | null;
  roomCount?: number | null;
  totalAmount?: number | null;
  checkInTime?: string | null;
  checkOutTime?: string | null;
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

/** 숙소 앞뒤 구간의 좌표 기반 비교입니다. 실제 조회 영수증·객실 가격·운행 보장을 뜻하지 않습니다. */
export type LodgingChoicePreview = {
  candidate: Place;
  currentName: string | null;
  legs: {
    dayIndex: number;
    direction: "INBOUND" | "OUTBOUND";
    placeName: string;
    beforeMinutes: number | null;
    afterMinutes: number;
  }[];
  beforeMinutes: number | null;
  afterMinutes: number;
  minutesDelta: number | null;
  clearedRouteCount: number;
  resetsStay: boolean;
  conflicts: string[];
  canApply: boolean;
};
/** 공개 중인 댓글만 내려옵니다. 수정 버전은 부모 게시글 버전과 독립적입니다. */
export type CommunityComment = {
  id: string;
  authorId: string;
  nickname: string;
  body: string;
  createdAt: string;
  version: number;
  updatedAt?: string | null;
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
  version: number;
  updatedAt?: string | null;
  comments?: CommunityComment[];
};
/** 공개 목록은 본문 전체와 관리 정보를 포함하지 않습니다. 본문은 상세 API에서 조회합니다. */
export type PostSummary = Pick<
  Post,
  | "id"
  | "nickname"
  | "category"
  | "region"
  | "title"
  | "createdAt"
  | "recruitmentDate"
  | "capacity"
  | "status"
> & { excerpt: string };
/** 공개 검색의 페이지 번호는 1부터입니다. 결과가 없으면 totalPages=0, page=1입니다. */
export type CommunityPostPage = {
  items: PostSummary[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
};
/** 휴지통에는 작성자 본인의 제목·삭제 시각만 제공합니다. 본문·댓글은 복원 후 다시 조회합니다. */
export type DeletedPost = Pick<
  Post,
  "id" | "category" | "region" | "title" | "version"
> & { deletedAt: string; hidden: boolean };
export type Room = {
  id: string;
  requesterId: string;
  recipientId: string;
  requesterName: string;
  recipientName: string;
  status: "PENDING" | "ACTIVE" | "DECLINED" | "CLOSED";
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

/** 전체 시간 조정은 장소·날짜·비용을 유지합니다. 구간 수는 같은 날 연속 방문 기준입니다. */
export type WholeSchedulePlan = {
  strategy: "EARLY" | "LATE";
  title: string;
  canApply: boolean;
  changes: SchedulePlan["changes"];
  days: {
    dayIndex: number;
    beforeStart: string;
    beforeEnd: string;
    afterStart: string;
    afterEnd: string;
  }[];
  queriedLegs: number;
  estimatedLegs: number;
  warnings: string[];
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
  /** 숙소를 경유한 경우에만 존재합니다. 이동 분과 숙소 체류를 분리하고 두 조회 출처를 보존합니다. */
  overnight?: {
    lodgingId: string;
    lodgingName: string;
    minimumStayMinutes: number;
    notBefore: string;
    arrival: string;
    departure: string;
    inbound: RouteLeg;
    outbound: RouteLeg;
  } | null;
};
export type RouteLeg = {
  minutes: number;
  distanceMeters: number;
  queriedAt: string;
  source: string;
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
