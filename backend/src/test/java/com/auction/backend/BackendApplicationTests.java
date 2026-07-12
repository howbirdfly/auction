package com.auction.backend;

import com.auction.backend.auction.service.AuctionService;
import com.auction.backend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
		"spring.config.import=",
		"spring.datasource.url=jdbc:h2:mem:auction;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.sql.init.mode=always"
})
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
