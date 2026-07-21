package com.auction.backend.auction.mapper;

import com.auction.backend.auction.model.AuctionBidRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface AuctionBidRequestMapper {

    AuctionBidRequest findByRequestId(@Param("requestId") String requestId);

    int insert(AuctionBidRequest bidRequest);

    int markSuccess(@Param("requestId") String requestId,
                    @Param("bidVersion") long bidVersion,
                    @Param("updatedAt") Instant updatedAt);

    int markFailed(@Param("requestId") String requestId,
                   @Param("errorMessage") String errorMessage,
                   @Param("updatedAt") Instant updatedAt);
}
