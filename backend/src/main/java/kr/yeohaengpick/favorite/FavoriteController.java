package kr.yeohaengpick.favorite;

import jakarta.validation.Valid;
import kr.yeohaengpick.auth.AuthService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** 회원 ID를 요청으로 받지 않습니다. 로그인 세션에서 얻은 본인 찜만 다룹니다. */
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

  private final FavoriteService favorites;
  private final AuthService auth;

  public FavoriteController(FavoriteService favorites, AuthService auth) {
    this.favorites = favorites;
    this.auth = auth;
  }

  @GetMapping
  Object list(Authentication a) {
    return favorites.list(auth.id(a));
  }

  @PostMapping
  Object save(
    @Valid @RequestBody FavoriteService.Save input,
    Authentication a
  ) {
    return favorites.save(auth.id(a), input);
  }

  @DeleteMapping("/{place}")
  void delete(@PathVariable String place, Authentication a) {
    favorites.delete(auth.id(a), place);
  }
}
