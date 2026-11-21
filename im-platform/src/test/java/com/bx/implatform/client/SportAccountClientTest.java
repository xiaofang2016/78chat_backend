package com.bx.implatform.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SportAccountClientTest {

    @Test
    void readsSportProfileAndPrefixesAvatar() {
        String body = """
            {"msg":"ok","code":"0","data":{"userId":"0c9a1e4b7d2f4a6e8b1c3d5f709182a4","phone":"13800138000","nickname":"138****8000","avatar":"/prod-api/user/avatar/0c9a1e4b7d2f4a6e8b1c3d5f709182a4?v=1","token":"kept"}}
            """;
        SportAccountClient.SportProfile profile = SportAccountClient.SportProfile.parse(200, body, "https://dgtplaoir00la.cloudfront.net");
        assertEquals("0c9a1e4b7d2f4a6e8b1c3d5f709182a4", profile.getUserId());
        assertEquals("13800138000", profile.getPhone());
        assertEquals("138****8000", profile.getNickname());
        assertEquals("https://dgtplaoir00la.cloudfront.net/prod-api/user/avatar/0c9a1e4b7d2f4a6e8b1c3d5f709182a4?v=1", profile.getAvatar());
    }

    @Test
    void expiredTokenUsesSportMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> SportAccountClient.SportProfile.parse(400, "{\"code\":\"400\",\"msg\":\"登录已过期\",\"data\":null}", "https://example.com"));
        assertEquals("登录已过期", ex.getMessage());
    }
}
