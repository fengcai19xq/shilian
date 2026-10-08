package com.shilian.dispatch;

import static com.shilian.dispatch.model.TicketStatus.ACCEPTED;
import static com.shilian.dispatch.model.TicketStatus.ASSIGNED;
import static com.shilian.dispatch.model.TicketStatus.OPEN;
import static com.shilian.dispatch.model.TicketStatus.REJECTED;
import static com.shilian.dispatch.model.TicketStatus.UPLOADED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shilian.dispatch.model.TicketStatus;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 状态机迁移表。 */
class TicketStatusTest {

    @Test
    void transitions() {
        assertThat(OPEN.next()).isEqualTo(Set.of(ASSIGNED));
        assertThat(ASSIGNED.next()).isEqualTo(Set.of(UPLOADED));
        assertThat(UPLOADED.next()).isEqualTo(Set.of(ACCEPTED, REJECTED));
        assertThat(REJECTED.next()).isEqualTo(Set.of(UPLOADED));
        assertThat(ACCEPTED.next()).isEmpty();
    }

    @Test
    void cannotSkipUploadOrAcceptTwice() {
        assertThat(ASSIGNED.canTransitionTo(ACCEPTED)).isFalse();
        assertThat(OPEN.canTransitionTo(UPLOADED)).isFalse();
        assertThat(ACCEPTED.canTransitionTo(REJECTED)).isFalse();
    }

    @Test
    void fromValue() {
        assertThat(TicketStatus.fromValue("uploaded")).isEqualTo(UPLOADED);
        assertThatThrownBy(() -> TicketStatus.fromValue("done")).isInstanceOf(IllegalArgumentException.class);
    }
}
