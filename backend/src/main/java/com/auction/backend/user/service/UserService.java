package com.auction.backend.user.service;

import com.auction.backend.user.dto.CreateUserRequest;
import com.auction.backend.user.dto.UpdateUserRequest;
import com.auction.backend.user.dto.UserLoginRequest;
import com.auction.backend.user.dto.UserProfileSnapshot;
import com.auction.backend.user.dto.UserRechargeRequest;
import com.auction.backend.user.mapper.UserAccountMapper;
import com.auction.backend.user.model.UserAccount;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class UserService {

    private static final String DEFAULT_AVATAR_URL = "https://placehold.co/256x256/f3f4f6/111827?text=User";
    private static final int PASSWORD_STRENGTH = 10;
    private static final BigDecimal DEMO_INITIAL_BALANCE = BigDecimal.valueOf(2000);

    private final AtomicLong userSequence = new AtomicLong(10000);
    private final UserAccountMapper userAccountMapper;
    private final HotWalletCacheService hotWalletCacheService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(PASSWORD_STRENGTH);

    public UserService(UserAccountMapper userAccountMapper,
                       HotWalletCacheService hotWalletCacheService) {
        this.userAccountMapper = userAccountMapper;
        this.hotWalletCacheService = hotWalletCacheService;
    }

    @PostConstruct
    public void initializeData() {
        userSequence.set(resolveCurrentSequence());
        if (userAccountMapper.countUsers() == 0) {
            seedDemoUsers();
        }
    }

    @Transactional(readOnly = true)
    public List<UserProfileSnapshot> listUsers() {
        return userAccountMapper.findAllOrderByCreatedAtDesc().stream()
                .map(this::toSnapshot)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserProfileSnapshot getUser(String userId) {
        return toSnapshot(findUser(userId));
    }

    @Transactional
    public UserProfileSnapshot register(CreateUserRequest request) {
        validateAccountAvailable(request.account());

        Instant now = Instant.now();
        UserAccount userAccount = new UserAccount(
                "U-" + userSequence.incrementAndGet(),
                request.account().trim(),
                hashPassword(request.password()),
                request.nickname().trim(),
                resolveAvatarUrl(request.avatarUrl()),
                resolveBio(request.bio()),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                now,
                now
        );

        userAccountMapper.insert(userAccount);
        return toSnapshot(userAccount);
    }

    @Transactional
    public UserProfileSnapshot updateUser(String userId, UpdateUserRequest request) {
        UserAccount userAccount = findUser(userId);
        userAccount.setNickname(request.nickname().trim());
        userAccount.setAvatarUrl(resolveAvatarUrl(request.avatarUrl()));
        userAccount.setBio(resolveBio(request.bio()));
        if (request.password() != null && !request.password().isBlank()) {
            if (request.password().length() < 6) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "password must be at least 6 characters");
            }
            userAccount.setPassword(hashPassword(request.password()));
            userAccount.setUpdatedAt(Instant.now());
            userAccountMapper.updatePassword(userAccount);
        }
        userAccount.setUpdatedAt(Instant.now());

        userAccountMapper.updateProfile(userAccount);
        return toSnapshot(userAccount);
    }

    @Transactional
    public UserProfileSnapshot login(UserLoginRequest request) {
        UserAccount userAccount = userAccountMapper.findByAccount(request.account().trim());
        if (userAccount == null || !passwordMatches(request.password(), userAccount)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "account or password is incorrect");
        }
        return toSnapshot(userAccount);
    }

    @Transactional
    public UserProfileSnapshot recharge(String userId, UserRechargeRequest request) {
        UserAccount userAccount = findUser(userId);
        userAccount.setBalance(userAccount.getBalance().add(request.amount()));
        userAccount.setUpdatedAt(Instant.now());
        userAccountMapper.updateWallet(userAccount);
        hotWalletCacheService.syncAuthoritativeWallet(userAccount);
        return toSnapshot(userAccount);
    }

    private UserAccount findUser(String userId) {
        UserAccount userAccount = userAccountMapper.findById(userId);
        if (userAccount == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        return hotWalletCacheService.overlayWallet(userAccount);
    }

    private void validateAccountAvailable(String account) {
        if (userAccountMapper.findByAccount(account.trim()) != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "account already exists");
        }
    }

    private UserProfileSnapshot toSnapshot(UserAccount userAccount) {
        UserAccount displayedUserAccount = hotWalletCacheService.overlayWallet(userAccount);
        return new UserProfileSnapshot(
                displayedUserAccount.getUserId(),
                displayedUserAccount.getAccount(),
                displayedUserAccount.getNickname(),
                displayedUserAccount.getAvatarUrl(),
                displayedUserAccount.getBio(),
                displayedUserAccount.getBalance(),
                displayedUserAccount.getFrozenAmount(),
                displayedUserAccount.getCreatedAt(),
                displayedUserAccount.getUpdatedAt()
        );
    }

    private long resolveCurrentSequence() {
        return userAccountMapper.findAllUserIds().stream()
                .map(id -> id.replace("U-", ""))
                .mapToLong(id -> {
                    try {
                        return Long.parseLong(id);
                    } catch (NumberFormatException ignored) {
                        return 10000L;
                    }
                })
                .max()
                .orElse(10000L);
    }

    private String resolveAvatarUrl(String avatarUrl) {
        if (avatarUrl == null || avatarUrl.isBlank()) {
            return DEFAULT_AVATAR_URL;
        }
        return avatarUrl.trim();
    }

    private String resolveBio(String bio) {
        if (bio == null || bio.isBlank()) {
            return "This user has not written a bio yet.";
        }
        return bio.trim();
    }

    private String hashPassword(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    private boolean passwordMatches(String rawPassword, UserAccount userAccount) {
        String storedPassword = userAccount.getPassword();
        if (storedPassword == null || storedPassword.isBlank()) {
            return false;
        }

        if (isLegacyPlaintextPassword(storedPassword)) {
            boolean matched = storedPassword.equals(rawPassword);
            if (matched) {
                userAccount.setPassword(hashPassword(rawPassword));
                userAccount.setUpdatedAt(Instant.now());
                userAccountMapper.updatePassword(userAccount);
            }
            return matched;
        }

        return passwordEncoder.matches(rawPassword, storedPassword);
    }

    private boolean isLegacyPlaintextPassword(String storedPassword) {
        return !storedPassword.startsWith("$2a$")
                && !storedPassword.startsWith("$2b$")
                && !storedPassword.startsWith("$2y$");
    }

    private void seedDemoUsers() {
        addSeedUser(
                "ava_host",
                "123456",
                "Host Ava",
                "https://placehold.co/256x256/fdf2f8/7c2d12?text=Ava",
                "Sneaker blind box host."
        );
        addSeedUser(
                "miko_live",
                "123456",
                "Host Miko",
                "https://placehold.co/256x256/e0f2fe/0f172a?text=Miko",
                "Collectible toy room curator."
        );
        addSeedUser(
                "u10001",
                "123456",
                "Bidder A",
                "https://placehold.co/256x256/fef3c7/1f2937?text=Bidder",
                "Enjoys flash auctions and trendy collectibles."
        );
    }

    private void addSeedUser(String account,
                             String password,
                             String nickname,
                             String avatarUrl,
                             String bio) {
        Instant now = Instant.now();
        userAccountMapper.insert(new UserAccount(
                "U-" + userSequence.incrementAndGet(),
                account,
                hashPassword(password),
                nickname,
                avatarUrl,
                bio,
                DEMO_INITIAL_BALANCE,
                BigDecimal.ZERO,
                now,
                now
        ));
    }
}
