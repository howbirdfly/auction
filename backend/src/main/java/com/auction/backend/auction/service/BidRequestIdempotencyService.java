package com.auction.backend.auction.service;

import com.auction.backend.auction.dto.BidRequest;
import com.auction.backend.auction.mapper.AuctionBidRecordMapper;
import com.auction.backend.auction.mapper.AuctionBidRequestMapper;
import com.auction.backend.auction.model.AuctionBidRecordEntity;
import com.auction.backend.auction.model.AuctionBidRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class BidRequestIdempotencyService {

    private static final int MAX_ERROR_LENGTH = 255;

    private final AuctionBidRequestMapper auctionBidRequestMapper;
    private final AuctionBidRecordMapper auctionBidRecordMapper;

    public BidRequestIdempotencyService(AuctionBidRequestMapper auctionBidRequestMapper,
                                        AuctionBidRecordMapper auctionBidRecordMapper) {
        this.auctionBidRequestMapper = auctionBidRequestMapper;
        this.auctionBidRecordMapper = auctionBidRecordMapper;
    }

    @Transactional
    public BidRequestDecision begin(String roomId, BidRequest request) {
        AuctionBidRequest existing = auctionBidRequestMapper.findByRequestId(request.requestId());
        if (existing == null) {
            Instant now = Instant.now();
            AuctionBidRequest bidRequest = new AuctionBidRequest();
            bidRequest.setRequestId(request.requestId());
            bidRequest.setRoomId(roomId);
            bidRequest.setUserId(request.userId());
            bidRequest.setNickname(request.nickname());
            bidRequest.setAmount(request.amount());
            bidRequest.setStatus("PROCESSING");
            bidRequest.setCreatedAt(now);
            bidRequest.setUpdatedAt(now);
            try {
                auctionBidRequestMapper.insert(bidRequest);
                return BidRequestDecision.newRequest();
            } catch (DuplicateKeyException ignored) {
                existing = auctionBidRequestMapper.findByRequestId(request.requestId());
            }
        }

        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "出价请求正在处理中，请稍后刷新");
        }

        assertSameRequest(existing, roomId, request);
        if ("SUCCESS".equals(existing.getStatus())) {
            return BidRequestDecision.duplicateSuccess();
        }

        if ("FAILED".equals(existing.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    existing.getErrorMessage() == null || existing.getErrorMessage().isBlank()
                            ? "这次出价请求已失败，请重新发起一次新的出价"
                            : existing.getErrorMessage()
            );
        }

        AuctionBidRecordEntity bidRecord = auctionBidRecordMapper.findByRequestId(request.requestId());
        if (bidRecord != null && bidRecord.getBidVersion() != null) {
            markSuccess(request.requestId(), bidRecord.getBidVersion());
            return BidRequestDecision.duplicateSuccess();
        }

        throw new ResponseStatusException(HttpStatus.CONFLICT, "相同出价请求仍在处理中，请勿重复提交");
    }

    @Transactional
    public void markSuccess(String requestId, long bidVersion) {
        auctionBidRequestMapper.markSuccess(requestId, bidVersion, Instant.now());
    }

    @Transactional
    public void markFailed(String requestId, String errorMessage) {
        auctionBidRequestMapper.markFailed(requestId, truncateError(errorMessage), Instant.now());
    }

    private void assertSameRequest(AuctionBidRequest existing, String roomId, BidRequest request) {
        boolean sameRoom = roomId.equals(existing.getRoomId());
        boolean sameUser = request.userId().equals(existing.getUserId());
        boolean sameAmount = request.amount().compareTo(existing.getAmount()) == 0;
        if (sameRoom && sameUser && sameAmount) {
            return;
        }

        throw new ResponseStatusException(HttpStatus.CONFLICT, "requestId 已被其他出价请求占用，请生成新的请求号");
    }

    private String truncateError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "出价失败";
        }
        return errorMessage.length() <= MAX_ERROR_LENGTH
                ? errorMessage
                : errorMessage.substring(0, MAX_ERROR_LENGTH);
    }

    public record BidRequestDecision(boolean accepted) {

        public static BidRequestDecision newRequest() {
            return new BidRequestDecision(true);
        }

        public static BidRequestDecision duplicateSuccess() {
            return new BidRequestDecision(false);
        }
    }
}
