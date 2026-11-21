package com.bx.implatform.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvailableActivitiesListQuestionTest {

    @Test
    void shortActivityQuestionsUseTheMerchantList() {
        assertTrue(AvailableActivitiesServiceImpl.matchesActivityListQuestion("活动"));
        assertTrue(AvailableActivitiesServiceImpl.matchesActivityListQuestion("问活动"));
        assertTrue(AvailableActivitiesServiceImpl.matchesActivityListQuestion("有活动吗"));
        assertTrue(AvailableActivitiesServiceImpl.matchesActivityListQuestion("现在有什么活动"));
        assertTrue(AvailableActivitiesServiceImpl.matchesActivityListQuestion("优惠活动"));
        assertTrue(AvailableActivitiesServiceImpl.matchesActivityListQuestion("能领什么"));
    }

    @Test
    void namedBonusAndOtherTopicsStayOut() {
        assertFalse(AvailableActivitiesServiceImpl.matchesActivityListQuestion("天天好运金"));
        assertFalse(AvailableActivitiesServiceImpl.matchesActivityListQuestion("昨天亏了多少"));
        assertFalse(AvailableActivitiesServiceImpl.matchesActivityListQuestion("能领多少佣金"));
        assertFalse(AvailableActivitiesServiceImpl.matchesActivityListQuestion("体育连红挑战"));
    }
}
