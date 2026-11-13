package kr.yeohaengpick.catalog;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kr.yeohaengpick.common.*;
import org.junit.jupiter.api.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/** 로컬 합성 공급자만 호출하여 공개 검색의 캐시·일일 예산·동시 요청·장애 경계를 검증합니다. */
class CatalogBudgetTest {

  private final JsonCodec json = new JsonCodec();
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger peak = new AtomicInteger();
  private final AtomicBoolean fail = new AtomicBoolean();
  private final AtomicBoolean malformed = new AtomicBoolean();
  private volatile CountDownLatch entered;
  private volatile CountDownLatch release;
  private HttpServer supplier;
  private ExecutorService supplierThreads;
  private CatalogService catalog;
  private MutableClock clock;

  @BeforeEach
  void prepareLocalSupplier() throws Exception {
    supplier = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    supplierThreads = Executors.newVirtualThreadPerTaskExecutor();
    supplier.setExecutor(supplierThreads);
    supplier.createContext("/", exchange -> {
      calls.incrementAndGet();
      int inFlight = active.incrementAndGet();
      peak.accumulateAndGet(inFlight, Math::max);
      try {
        if (entered != null) entered.countDown();
        if (release != null) {
          try {
            if (
              !release.await(5, TimeUnit.SECONDS)
            ) throw new IllegalStateException("합성 공급자 대기 시간 초과");
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          }
        }
        byte[] body = (
          fail.get()
            ? "private-supplier-sentinel"
            : malformed.get()
              ? "{}"
              : json.write(
                  Map.of(
                    "documents",
                    List.of(
                      Map.of(
                        "id",
                        "budget-place",
                        "place_name",
                        "합성 카페",
                        "category_group_code",
                        "CE7",
                        "category_name",
                        "음식점 > 카페",
                        "address_name",
                        "서울특별시 종로구 검증로 1",
                        "y",
                        "37.57",
                        "x",
                        "126.98",
                        "place_url",
                        "https://example.test/place"
                      )
                    )
                  )
                )
        ).getBytes(StandardCharsets.UTF_8);
        exchange
          .getResponseHeaders()
          .set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(fail.get() ? 503 : 200, body.length);
        try (var out = exchange.getResponseBody()) {
          out.write(body);
        }
      } finally {
        active.decrementAndGet();
        exchange.close();
      }
    });
    supplier.start();
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(2000);
    factory.setReadTimeout(3000);
    var client = RestClient.builder()
      .requestFactory((uri, method) ->
        factory.createRequest(
          URI.create(
            "http://127.0.0.1:" +
              supplier.getAddress().getPort() +
              uri.getRawPath() +
              "?" +
              uri.getRawQuery()
          ),
          method
        )
      )
      .build();
    catalog = new CatalogService(json);
    clock = new MutableClock(Instant.parse("2026-11-12T14:59:59Z"));
    ReflectionTestUtils.setField(catalog, "client", client);
    ReflectionTestUtils.setField(catalog, "kakaoKey", "synthetic-budget-key");
    ReflectionTestUtils.setField(catalog, "clock", clock);
    ReflectionTestUtils.setField(catalog, "dailyLimit", 2);
    ReflectionTestUtils.setField(catalog, "sample", false);
  }

  @AfterEach
  void stopLocalSupplier() {
    if (release != null) release.countDown();
    supplier.stop(0);
    supplierThreads.shutdownNow();
  }

  @Test
  void cacheHitsDoNotSpendBudgetAndKoreanMidnightResetsIt() {
    var first = catalog.search("서울", "같은 검색");
    assertThat(first).hasSize(1);
    assertThat(catalog.search("서울", "같은 검색")).isEqualTo(first);
    assertThat(calls).hasValue(1);
    catalog.search("서울", "다른 검색");
    assertThatThrownBy(() ->
      catalog.search("서울", "세 번째 검색")
    ).isInstanceOfSatisfying(DomainException.class, failure ->
      assertThat(failure.status).isEqualTo(429)
    );
    assertThat(calls).hasValue(2);
    assertThat(catalog.search("서울", "같은 검색")).isEqualTo(first);
    assertThat(calls).hasValue(2);
    clock.advance(Duration.ofSeconds(2));
    catalog.search("서울", "세 번째 검색");
    assertThat(calls).hasValue(3);
  }

  @Test
  void concurrentCacheMissesCannotExceedTheSingleRemainingCall()
    throws Exception {
    ReflectionTestUtils.setField(catalog, "dailyLimit", 1);
    var gate = new CyclicBarrier(8);
    try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Integer>> responses = new ArrayList<>();
      for (int index = 0; index < 8; index++) {
        String query = "동시 검색 " + index;
        responses.add(
          callers.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            try {
              catalog.search("서울", query);
              return 200;
            } catch (DomainException failure) {
              return failure.status;
            }
          })
        );
      }
      List<Integer> statuses = new ArrayList<>();
      for (var response : responses)
        statuses.add(response.get(10, TimeUnit.SECONDS));
      assertThat(statuses)
        .filteredOn(status -> status == 200)
        .hasSize(1);
      assertThat(statuses)
        .filteredOn(status -> status == 429)
        .hasSize(7);
      assertThat(calls).hasValue(1);
    }
  }

  @Test
  void atMostTwoSupplierCallsRunTogetherAndBusyRejectionKeepsBudget()
    throws Exception {
    ReflectionTestUtils.setField(catalog, "dailyLimit", 3);
    entered = new CountDownLatch(2);
    release = new CountDownLatch(1);
    try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = callers.submit(() ->
        catalog.search("서울", "느린 검색 하나")
      );
      var second = callers.submit(() -> catalog.search("서울", "느린 검색 둘"));
      try {
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() ->
          catalog.search("서울", "동시 한도 초과")
        ).isInstanceOfSatisfying(DomainException.class, failure ->
          assertThat(failure.status).isEqualTo(429)
        );
        assertThat(calls).hasValue(2);
        assertThat(peak).hasValue(2);
      } finally {
        release.countDown();
      }
      assertThat(first.get(5, TimeUnit.SECONDS)).hasSize(1);
      assertThat(second.get(5, TimeUnit.SECONDS)).hasSize(1);
      catalog.search("서울", "동시 한도 초과");
      assertThat(calls).hasValue(3);
    }
  }

  @Test
  void supplierFailureNeverFallsBackToExamplesAndReleasesConcurrencyPermit() {
    ReflectionTestUtils.setField(catalog, "sample", true);
    fail.set(true);
    assertThatThrownBy(() -> catalog.search("서울", "장애 검색"))
      .isInstanceOfSatisfying(DomainException.class, failure ->
        assertThat(failure.status).isEqualTo(503)
      )
      .hasMessageNotContaining("private-supplier-sentinel")
      .hasMessageNotContaining("synthetic-budget-key");
    fail.set(false);
    assertThat(catalog.search("서울", "정상 검색")).hasSize(1);
    assertThatThrownBy(() ->
      catalog.search("서울", "실패도 예산 포함")
    ).isInstanceOfSatisfying(DomainException.class, failure ->
      assertThat(failure.status).isEqualTo(429)
    );
    assertThat(calls).hasValue(2);
  }

  @Test
  void malformedSuccessIsNotCachedAsEmptySearch() {
    malformed.set(true);
    assertThatThrownBy(() ->
      catalog.search("서울", "응답 형식 검증")
    ).isInstanceOfSatisfying(DomainException.class, failure ->
      assertThat(failure.status).isEqualTo(503)
    );
    malformed.set(false);
    assertThat(catalog.search("서울", "응답 형식 검증")).hasSize(1);
    assertThat(calls).hasValue(2);
  }

  private static final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    MutableClock(Instant now) {
      this(new AtomicReference<>(now), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
      this.now = now;
      this.zone = zone;
    }

    void advance(Duration duration) {
      now.updateAndGet(value -> value.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
