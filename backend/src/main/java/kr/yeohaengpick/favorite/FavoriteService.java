package kr.yeohaengpick.favorite;

import jakarta.validation.constraints.*;
import java.util.*;
import kr.yeohaengpick.catalog.*;
import kr.yeohaengpick.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 검색으로 확인한 장소만 찜합니다. 보관 당시 정보는 다시 보는 용도이며 추천에서는 공급자를 재조회합니다. */
@Service
public class FavoriteService {

  private final FavoriteMapper favorites;
  private final CatalogService catalog;
  private final JsonCodec json;

  public FavoriteService(
    FavoriteMapper favorites,
    CatalogService catalog,
    JsonCodec json
  ) {
    this.favorites = favorites;
    this.catalog = catalog;
    this.json = json;
  }

  public record Save(
    @NotBlank @Size(max = 100) String placeId,
    @NotBlank @Size(max = 30) String region,
    @NotNull @Size(max = 100) String query,
    @NotBlank @Size(max = 30) String category
  ) {}

  public record Saved(Place place, String savedAt) {}

  private Saved view(Map<String, Object> row) {
    return new Saved(
      json.read(row.get("placeJson").toString(), Place.class),
      row.get("createdAt").toString()
    );
  }

  public List<Saved> list(String member) {
    return favorites.list(member).stream().map(this::view).toList();
  }

  @Transactional
  public Saved save(String member, Save input) {
    lock(member);
    var existing = favorites.get(member, input.placeId());
    // 같은 장소 재전송은 최초 저장일과 내용을 유지합니다. 해제 후 다시 저장해야 새 정보로 보관됩니다.
    if (existing != null) return view(existing);
    if (favorites.count(member) >= 200) throw new DomainException(
      409,
      "찜은 최대 200곳까지 보관할 수 있어요. 먼저 필요 없는 장소를 해제해 주세요."
    );
    Place place = catalog
      .search(input.region(), input.query(), input.category())
      .stream()
      .filter(p -> p.id().equals(input.placeId()))
      .findFirst()
      .orElseThrow(() ->
        new DomainException(
          409,
          "검색 결과가 바뀌었어요. 장소를 다시 검색한 뒤 찜해 주세요."
        )
      );
    // 이름·좌표·금액·외부 주소를 클라이언트에서 받지 않아 조작된 장소나 URL을 저장할 수 없습니다.
    favorites.insert(member, place.id(), json.write(place));
    return view(favorites.get(member, place.id()));
  }

  @Transactional
  public void delete(String member, String place) {
    lock(member);
    favorites.delete(member, place); // 이미 해제한 경우에도 성공하므로 불확실한 응답 뒤 안전하게 재시도할 수 있습니다.
  }

  private void lock(String member) {
    if (favorites.lockMember(member) == null) throw new DomainException(
      401,
      "로그인이 필요해요."
    );
  }

  /**
   * 선택한 개인 찜을 추천 후보에 합칩니다. 과거 스냅샷을 현재 장소/요금으로 사용하지 않습니다.
   * 조회 실패는 503, 지역/분류 불일치는 400, 해제·현재 검색에서 찾을 수 없는 장소는 409로 전체 요청을 중단합니다.
   */
  public List<Place> resolve(
    String member,
    List<String> ids,
    List<String> regions
  ) {
    if (
      ids.size() > 5 || new HashSet<>(ids).size() != ids.size()
    ) throw DomainException.bad(
      "찜한 방문 장소는 중복 없이 최대 5곳을 선택해 주세요."
    );
    List<Place> result = new ArrayList<>();
    for (String id : ids) {
      var row = favorites.get(member, id);
      if (row == null) throw new DomainException(
        409,
        "선택한 찜이 해제됐거나 내 찜이 아니에요. 찜한 장소 선택을 다시 확인해 주세요."
      );
      Place saved = view(row).place();
      if (
        !regions.contains(saved.region()) || saved.category().equals("숙소")
      ) throw DomainException.bad(
        "이번 여행 지역의 방문 장소만 선택해 주세요. 숙소는 숙소 선택 단계에서 정해요."
      );
      Place current = catalog
        .search(saved.region(), saved.name())
        .stream()
        .filter(p -> p.id().equals(id) && !p.category().equals("숙소"))
        .findFirst()
        .orElseThrow(() ->
          new DomainException(
            409,
            saved.name() +
              "을 현재 검색에서 찾지 못했어요. 찜 선택을 해제하거나 장소를 다시 검색해 주세요."
          )
        );
      result.add(current);
    }
    return result;
  }
}
