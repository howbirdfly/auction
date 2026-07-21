package com.auction.backend.auction.mapper;

import com.auction.backend.auction.model.AuctionBidPersistenceLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;

@Mapper
public interface AuctionBidPersistenceLogMapper {

    AuctionBidPersistenceLog findByEventId(@Param("eventId") String eventId);

    int insert(AuctionBidPersistenceLog log);

    int updateStatus(@Param("eventId") String eventId,
                     @Param("status") String status,
                     @Param("lastError") String lastError,
                     @Param("persistedAt") Instant persistedAt,
                     @Param("updatedAt") Instant updatedAt);

    int incrementAttemptCount(@Param("eventId") String eventId,
                              @Param("updatedAt") Instant updatedAt);

    java.util.List<AuctionBidPersistenceLog> findPendingForCompensation(@Param("staleBefore") Instant staleBefore,
                                                                        @Param("limit") int limit);
}
