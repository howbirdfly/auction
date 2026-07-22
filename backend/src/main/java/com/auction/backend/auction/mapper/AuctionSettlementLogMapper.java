package com.auction.backend.auction.mapper;

import com.auction.backend.auction.model.AuctionSettlementLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AuctionSettlementLogMapper {

    AuctionSettlementLog findByRoomId(@Param("roomId") String roomId);

    int insert(AuctionSettlementLog log);

    int markProcessing(@Param("roomId") String roomId,
                       @Param("winnerUserId") String winnerUserId,
                       @Param("finalPrice") java.math.BigDecimal finalPrice,
                       @Param("updatedAt") Instant updatedAt,
                       @Param("staleBefore") Instant staleBefore);

    int markWinnerFundsSettled(@Param("roomId") String roomId,
                               @Param("updatedAt") Instant updatedAt);

    int markDepositsReleased(@Param("roomId") String roomId,
                             @Param("updatedAt") Instant updatedAt);

    int markSuccess(@Param("roomId") String roomId,
                    @Param("updatedAt") Instant updatedAt,
                    @Param("settledAt") Instant settledAt);

    int markFailed(@Param("roomId") String roomId,
                   @Param("lastError") String lastError,
                   @Param("updatedAt") Instant updatedAt);

    List<AuctionSettlementLog> findPendingForCompensation(@Param("staleBefore") Instant staleBefore,
                                                          @Param("limit") int limit);
}
