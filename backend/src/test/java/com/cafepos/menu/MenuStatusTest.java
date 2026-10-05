package com.cafepos.menu;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class MenuStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 4);

    @Test
    void draftStaysDraft() {
        assertThat(MenuStatus.resolve("DRAFT", null, TODAY, null)).isEqualTo(MenuStatus.DRAFT);
    }

    @Test
    void futureScheduledIsScheduled() {
        assertThat(MenuStatus.resolve("SCHEDULED", TODAY.plusDays(1), TODAY, null)).isEqualTo(MenuStatus.SCHEDULED);
    }

    @Test
    void pastScheduledWithoutSuccessorIsActive() {
        assertThat(MenuStatus.resolve("SCHEDULED", LocalDate.of(2026, 5, 1), TODAY, null)).isEqualTo(MenuStatus.ACTIVE);
    }

    @Test
    void supersededVersionIsArchived() {
        assertThat(MenuStatus.resolve("SCHEDULED", LocalDate.of(2026, 5, 1), TODAY, TODAY)).isEqualTo(MenuStatus.ARCHIVED);
    }

    @Test
    void successorInFutureKeepsCurrentActive() {
        assertThat(MenuStatus.resolve("SCHEDULED", LocalDate.of(2026, 5, 1), TODAY, TODAY.plusDays(5)))
                .isEqualTo(MenuStatus.ACTIVE);
    }

    @Test
    void onlyDraftAndScheduledAreEditable() {
        assertThat(MenuStatus.DRAFT.editable()).isTrue();
        assertThat(MenuStatus.SCHEDULED.editable()).isTrue();
        assertThat(MenuStatus.ACTIVE.editable()).isFalse();
        assertThat(MenuStatus.ARCHIVED.editable()).isFalse();
    }
}
