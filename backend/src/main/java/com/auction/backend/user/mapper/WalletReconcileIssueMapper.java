package com.auction.backend.user.mapper;

import com.auction.backend.user.model.WalletReconcileIssue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface WalletReconcileIssueMapper {

    WalletReconcileIssue findOpenByUserId(@Param("userId") String userId);

    List<WalletReconcileIssue> findLatest(@Param("limit") int limit);

    List<WalletReconcileIssue> findByUserId(@Param("userId") String userId, @Param("limit") int limit);

    int insert(WalletReconcileIssue issue);

    int updateOpen(WalletReconcileIssue issue);

    int markOpenResolvedByUserId(@Param("userId") String userId,
                                 @Param("resolvedAt") Instant resolvedAt);
}
