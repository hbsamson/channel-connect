package com.svi.channelconnect.dealership.config;

import java.time.Clock;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.dealership.persistence.DealershipRepository;

@Component
public class DealershipStartup implements ApplicationRunner {
	private final DealershipRepository store;
	private final Clock clock;
	public DealershipStartup(DealershipRepository store, Clock clock) { this.store = store; this.clock = clock; }
	@Override public void run(ApplicationArguments arguments) { store.recover(clock.millis()); }
}
