package com.auction.backend.auction.mapper;

import com.auction.backend.auction.model.AuctionRoomRegistration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AuctionRoomRegistrationMapper {

    AuctionRoomRegistration findByRoomAndUser(@Param("roomId") String roomId, @Param("userId") String userId);

    List<AuctionRoomRegistration> findAllByRoomId(@Param("roomId") String roomId);

    int insert(AuctionRoomRegistration registration);

    int updateForRegistration(AuctionRoomRegistration registration);
}
