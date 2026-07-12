package com.auction.backend.auction.mapper;

import com.auction.backend.auction.model.AuctionLeaderboardRow;
import com.auction.backend.auction.model.AuctionBidRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AuctionBidRecordMapper {

    int insert(AuctionBidRecordEntity bidRecord);

    List<AuctionBidRecordEntity> findLatestByRoomId(@Param("roomId") String roomId, @Param("limit") int limit);

    List<AuctionLeaderboardRow> findLeaderboardByRoomId(@Param("roomId") String roomId, @Param("limit") int limit);

    long countByRoomId(@Param("roomId") String roomId);

    int deleteByRoomId(@Param("roomId") String roomId);
}
