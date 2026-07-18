package com.auction.backend.user.mapper;

import com.auction.backend.user.model.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserAccountMapper {

    long countUsers();

    List<UserAccount> findAllOrderByCreatedAtDesc();

    UserAccount findById(@Param("userId") String userId);

    UserAccount findByAccount(@Param("account") String account);

    UserAccount findByAccountForUpdate(@Param("account") String account);

    List<String> findAllUserIds();

    int insert(UserAccount userAccount);

    int updateProfile(UserAccount userAccount);

    int updatePassword(UserAccount userAccount);

    int updateWallet(UserAccount userAccount);
}
