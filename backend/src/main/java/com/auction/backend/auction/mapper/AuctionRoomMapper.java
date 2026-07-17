package com.auction.backend.auction.mapper;

import com.auction.backend.auction.model.AuctionRoom;
import com.auction.backend.auction.model.AuctionStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AuctionRoomMapper {

    long countRooms();

    List<AuctionRoom> findAllOrderByEndsAtAsc();

    AuctionRoom findById(@Param("roomId") String roomId);

    AuctionRoom findByIdForUpdate(@Param("roomId") String roomId);

    List<AuctionRoom> findExpiredRooms(@Param("status") AuctionStatus status, @Param("now") Instant now);

    List<String> findAllRoomIds();

    int insert(AuctionRoom room);

    int updateAfterBid(AuctionRoom room);

    int updateStatus(@Param("roomId") String roomId,
                     @Param("status") AuctionStatus status,
                     @Param("version") long version);

    int deleteById(@Param("roomId") String roomId);
}
