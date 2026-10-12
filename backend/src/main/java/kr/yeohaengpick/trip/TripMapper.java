package kr.yeohaengpick.trip;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** SQL에는 항상 바인딩 변수를 사용합니다. 일정 수정은 version 비교 후 같은 트랜잭션에서 실행합니다. */
@Mapper
public interface TripMapper {
  @Select(
    "SELECT payload FROM trip_route_timings WHERE trip_id=#{id} ORDER BY to_item_id"
  )
  List<String> routeTimings(String id);

  @Delete(
    "DELETE FROM trip_route_timings WHERE trip_id=#{trip} AND to_item_id=#{item}"
  )
  void clearRouteTiming(@Param("trip") String trip, @Param("item") String item);

  @Insert(
    "INSERT INTO trip_route_timings(trip_id,to_item_id,payload) VALUES(#{trip},#{item},#{payload})"
  )
  void routeTiming(
    @Param("trip") String trip,
    @Param("item") String item,
    @Param("payload") String payload
  );

  @Select(
    "SELECT * FROM external_bookings WHERE trip_id=#{trip} ORDER BY departure"
  )
  List<Map<String, Object>> externalBookings(String trip);

  @Insert(
    "INSERT INTO external_bookings(id,trip_id,label,category,departure,arrival,departure_place,arrival_place,before_minutes,after_minutes,schedule_linked,amount,status,reason,updated_by) VALUES(#{id},#{trip},#{b.label},#{b.category},#{b.departure},NULLIF(#{b.arrival},''),COALESCE(#{b.departurePlace},''),COALESCE(#{b.arrivalPlace},''),COALESCE(#{b.beforeMinutes},30),COALESCE(#{b.afterMinutes},30),COALESCE(#{b.scheduleLinked},FALSE),#{b.amount},#{b.status},#{b.reason},#{member})"
  )
  void addExternalBooking(
    @Param("id") String id,
    @Param("trip") String trip,
    @Param("b") ExternalBookingController.Booking booking,
    @Param("member") String member
  );

  @Update(
    "UPDATE external_bookings SET label=#{b.label},category=#{b.category},departure=#{b.departure},arrival=NULLIF(#{b.arrival},''),departure_place=COALESCE(#{b.departurePlace},''),arrival_place=COALESCE(#{b.arrivalPlace},''),before_minutes=COALESCE(#{b.beforeMinutes},30),after_minutes=COALESCE(#{b.afterMinutes},30),schedule_linked=COALESCE(#{b.scheduleLinked},FALSE),amount=#{b.amount},status=#{b.status},reason=#{b.reason},updated_by=#{member} WHERE id=#{id} AND trip_id=#{trip}"
  )
  void updateExternalBooking(
    @Param("id") String id,
    @Param("trip") String trip,
    @Param("b") ExternalBookingController.Booking booking,
    @Param("member") String member
  );

  @Insert(
    "INSERT INTO trip_lodgings(trip_id,place_id,region,name,latitude,longitude,nightly_estimate,source,place_url) VALUES(#{trip},#{p.id},#{p.region},#{p.name},#{p.latitude},#{p.longitude},#{p.cost},#{p.source},#{p.url})"
  )
  void lodging(
    @Param("trip") String trip,
    @Param("p") kr.yeohaengpick.catalog.Place place
  );

  @Select("SELECT * FROM trip_lodgings WHERE trip_id=#{trip} ORDER BY region")
  List<Map<String, Object>> lodgings(String trip);

  @Delete(
    "DELETE FROM trip_lodgings WHERE trip_id=#{trip} AND region=#{region}"
  )
  int removeLodging(@Param("trip") String trip, @Param("region") String region);

  @Update(
    "UPDATE trip_lodgings SET check_in_date=#{s.checkInDate},check_out_date=#{s.checkOutDate},guest_count=#{s.guestCount},room_count=#{s.roomCount},total_amount=#{s.totalAmount},check_in_time=#{s.checkInTime},check_out_time=#{s.checkOutTime} WHERE trip_id=#{trip} AND place_id=#{place}"
  )
  int lodgingStay(
    @Param("trip") String trip,
    @Param("place") String place,
    @Param("s") LodgingStay.Conditions stay
  );

  @Update(
    "UPDATE trip_lodgings SET booking_status=#{status},booking_reason=#{reason} WHERE trip_id=#{trip} AND place_id=#{place}"
  )
  int lodgingBooking(
    @Param("trip") String trip,
    @Param("place") String place,
    @Param("status") String status,
    @Param("reason") String reason
  );

  @Insert(
    "INSERT INTO trips(id,owner_id,title,regions,start_date,end_date,origin,party_size,budget,transport,preferences,lodging) VALUES(#{id},#{ownerId},#{title},#{regions},#{startDate},#{endDate},#{origin},#{partySize},#{budget},#{transport},#{preferences},#{lodging})"
  )
  void insert(Map<String, Object> trip);

  @Insert(
    "INSERT INTO trip_members(trip_id,member_id) VALUES(#{trip},#{member})"
  )
  void member(@Param("trip") String trip, @Param("member") String member);

  @Select(
    "SELECT t.* FROM trips t JOIN trip_members m ON t.id=m.trip_id WHERE m.member_id=#{id} ORDER BY t.created_at DESC"
  )
  List<Map<String, Object>> list(String id);

  @Select("SELECT * FROM trips WHERE id=#{id}")
  Map<String, Object> get(String id);

  /** 참여 해제와 편집을 같은 여행 행에서 직렬화합니다. 먼저 완료한 요청 다음에 권한을 다시 확인합니다. */
  @Select("SELECT * FROM trips WHERE id=#{id} FOR UPDATE")
  Map<String, Object> lock(String id);

  @Delete(
    "DELETE FROM trip_members WHERE trip_id=#{trip} AND member_id=#{member}"
  )
  int removeMember(@Param("trip") String trip, @Param("member") String member);

  @Select(
    "SELECT COUNT(*) FROM trip_members WHERE trip_id=#{trip} AND member_id=#{member}"
  )
  int access(@Param("trip") String trip, @Param("member") String member);

  @Select(
    "SELECT m.id,m.nickname,m.preferences FROM members m JOIN trip_members tm ON m.id=tm.member_id WHERE tm.trip_id=#{id}"
  )
  List<Map<String, Object>> members(String id);

  @Select(
    "SELECT * FROM itinerary_items WHERE trip_id=#{id} ORDER BY day_index,sort_order"
  )
  List<Map<String, Object>> items(String id);

  @Insert(
    "INSERT INTO itinerary_items(id,trip_id,day_index,sort_order,place_id,place_name,region,category,latitude,longitude,start_time,duration,cost,indoor,source,place_url,fixed,booking_status,booking_reason,visit_start,visit_end,visit_cost,visit_note) VALUES(#{id},#{tripId},#{dayIndex},#{sortOrder},#{placeId},#{placeName},#{region},#{category},#{latitude},#{longitude},#{startTime},#{duration},#{cost},#{indoor},#{source},#{placeUrl},#{fixed},#{bookingStatus},#{bookingReason},#{visitStart},#{visitEnd},#{visitCost},COALESCE(#{visitNote},''))"
  )
  void item(Map<String, Object> item);

  @Update(
    "UPDATE itinerary_items SET visit_start=#{v.start},visit_end=#{v.end},visit_cost=#{v.cost},visit_note=#{v.note} WHERE trip_id=#{trip} AND id=#{item}"
  )
  int visitConditions(
    @Param("trip") String trip,
    @Param("item") String item,
    @Param("v") VisitConditions.Values values
  );

  @Delete("DELETE FROM itinerary_items WHERE trip_id=#{id}")
  void clearItems(String id);

  @Update(
    "UPDATE trips SET version=version+1 WHERE id=#{id} AND version=#{version}"
  )
  int bump(@Param("id") String id, @Param("version") int version);

  @Insert(
    "INSERT INTO trip_history(id,trip_id,member_id,version,summary,snapshot) VALUES(#{id},#{trip},#{member},#{version},#{summary},#{snapshot})"
  )
  void history(
    @Param("id") String id,
    @Param("trip") String trip,
    @Param("member") String member,
    @Param("version") int version,
    @Param("summary") String summary,
    @Param("snapshot") String snapshot
  );

  @Select(
    "SELECT h.id,h.version,h.summary,h.created_at,m.nickname FROM trip_history h JOIN members m ON m.id=h.member_id WHERE h.trip_id=#{trip} ORDER BY h.version DESC LIMIT 30"
  )
  List<Map<String, Object>> historyList(String trip);

  @Select("SELECT * FROM trip_history WHERE id=#{id}")
  Map<String, Object> historyById(String id);

  @Update("UPDATE trips SET share_token=#{token} WHERE id=#{id}")
  void share(@Param("id") String id, @Param("token") String token);

  @Select("SELECT id FROM trips WHERE share_token=#{token}")
  String byShare(String token);

  @Select("SELECT * FROM trip_tasks WHERE trip_id=#{id} ORDER BY content")
  List<Map<String, Object>> tasks(String id);

  @Insert(
    "INSERT INTO trip_tasks(id,trip_id,content) VALUES(#{id},#{trip},#{content})"
  )
  void addTask(
    @Param("id") String id,
    @Param("trip") String trip,
    @Param("content") String content
  );

  @Update(
    "UPDATE trip_tasks SET done=#{done} WHERE id=#{id} AND trip_id=#{trip}"
  )
  int task(
    @Param("id") String id,
    @Param("trip") String trip,
    @Param("done") boolean done
  );

  @Select(
    "SELECT e.*,m.nickname FROM expenses e JOIN members m ON e.member_id=m.id WHERE trip_id=#{id} ORDER BY e.created_at DESC"
  )
  List<Map<String, Object>> expenses(String id);

  @Insert(
    "INSERT INTO expenses(id,trip_id,member_id,label,amount) VALUES(#{id},#{trip},#{member},#{label},#{amount})"
  )
  void expense(
    @Param("id") String id,
    @Param("trip") String trip,
    @Param("member") String member,
    @Param("label") String label,
    @Param("amount") int amount
  );

  @Delete("DELETE FROM trips WHERE id=#{id}")
  void delete(String id);
}
