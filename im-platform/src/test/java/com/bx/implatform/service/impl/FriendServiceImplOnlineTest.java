package com.bx.implatform.service.impl;

import com.bx.imcommon.enums.IMTerminalType;
import com.bx.implatform.contant.OfficialCsNames;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendServiceImplOnlineTest {

    @Test
    void sportLookupIncludesDeskContacts() {
        List<Long> ids = new ArrayList<>(List.of(11L, OfficialCsNames.MEIMEI_ID));
        List<Long> watch = OfficialCsNames.appendSportOnlineIds(" sport ", ids);
        assertEquals(OfficialCsNames.sportContactIds(), watch);
        assertEquals(11L, ids.get(0));
        assertEquals(1, ids.stream().filter(OfficialCsNames.MEIMEI_ID::equals).count());
        assertTrue(ids.contains(OfficialCsNames.TIANTIAN_ID));
        assertEquals(1 + OfficialCsNames.sportContactIds().size(), ids.size());
    }

    @Test
    void otherMerchantsStayOnRealFriends() {
        List<Long> ids = new ArrayList<>(List.of(11L));
        assertTrue(OfficialCsNames.appendSportOnlineIds("10011", ids).isEmpty());
        assertTrue(OfficialCsNames.appendSportOnlineIds(null, ids).isEmpty());
        assertEquals(List.of(11L), ids);
    }

    @Test
    void onlyCsTerminalAlsoCountsAsWeb() {
        Map<Long, List<IMTerminalType>> online = new HashMap<>();
        online.put(OfficialCsNames.NIANNIAN_ID, new ArrayList<>(List.of(IMTerminalType.CS)));
        online.put(OfficialCsNames.XINER_ID, new ArrayList<>(List.of(IMTerminalType.APP)));
        FriendServiceImpl.preferClientTerminal(online, OfficialCsNames.sportContactIds());
        assertTrue(online.get(OfficialCsNames.NIANNIAN_ID).contains(IMTerminalType.WEB));
        assertFalse(online.get(OfficialCsNames.XINER_ID).contains(IMTerminalType.WEB));
        assertEquals(List.of(IMTerminalType.APP), online.get(OfficialCsNames.XINER_ID));
    }
}
