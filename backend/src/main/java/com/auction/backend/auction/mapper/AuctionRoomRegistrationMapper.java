package com.auction.backend.auction.mapper;

import com.auction.backend.auction.model.AuctionRoomRegistration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AuctionRoomRegistrationMapper {

    AuctionRoomRegistration findByRoomAndUser(@Param("roomId") String roomId, @Param("userId") String userId);

    int insert(AuctionRoomRegistration registration);

    int updateForRegistration(AuctionRoomRegistration registration);
}
