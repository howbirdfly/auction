package com.auction.backend;

import com.auction.backend.auction.service.AuctionService;
import com.auction.backend.user.service.UserService;
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

	@Autowired
	private UserService userService;

	@Test
	void contextLoads() {
		assertThat(auctionService.listRooms()).isNotEmpty();
		assertThat(userService.listUsers()).isNotEmpty();
	}

}
