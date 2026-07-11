package com.auction.backend;

import com.auction.backend.auction.service.AuctionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

	@Autowired
	private AuctionService auctionService;

	@Test
	void contextLoads() {
		assertThat(auctionService.listRooms()).isNotEmpty();
	}

}
