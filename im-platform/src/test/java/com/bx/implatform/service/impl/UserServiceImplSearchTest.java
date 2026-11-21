package com.bx.implatform.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UserServiceImplSearchTest {

    @Test
    void sportChatIdIsExact() {
        assertEquals(8000000006L, UserServiceImpl.exactUserId("8000000006"));
    }

    @Test
    void rejectsBlankPrefixAndOverflow() {
        assertNull(UserServiceImpl.exactUserId(""));
        assertNull(UserServiceImpl.exactUserId("08000000006"));
        assertNull(UserServiceImpl.exactUserId("s8000000006"));
        assertNull(UserServiceImpl.exactUserId("1234567890123456789"));
    }
}
