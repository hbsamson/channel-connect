package com.svi.channelconnect.whatsapp.messaging.messenger.persistence;

import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyStatisticRepository extends JpaRepository<DailyStatistic, LocalDate> {
}
