package com.svi.channelconnect.whatsapp.messaging.messenger.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "daily_statistics")
public class DailyStatistic {
    @Id
    @Column(name = "activity_day")
    private LocalDate activityDay;

    @Column(name = "incoming_count", nullable = false)
    private long incomingCount;

    @Column(name = "outgoing_count", nullable = false)
    private long outgoingCount;

    protected DailyStatistic() {
    }

    public DailyStatistic(LocalDate activityDay) {
        this.activityDay = activityDay;
    }

    public void recordIncoming() {
        incomingCount++;
    }

    public void recordOutgoing() {
        outgoingCount++;
    }
}
