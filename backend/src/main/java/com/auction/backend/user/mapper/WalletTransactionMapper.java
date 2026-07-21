package com.auction.backend.user.mapper;

import com.auction.backend.user.model.WalletTransaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WalletTransactionMapper {

    WalletTransaction findByBusinessKey(@Param("businessKey") String businessKey);

    List<WalletTransaction> findLatestByUserId(@Param("userId") String userId, @Param("limit") int limit);

    int insert(WalletTransaction transaction);
}
