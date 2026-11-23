package com.bx.implatform.contant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 官方客服分开服务：小红只接吉祥体育，小绿只接 UED，客服美美和通讯录客服只接说球帝。
 * 通讯录客服沿用原站联系人里的名字。
 */
public final class OfficialCsNames {

    public static final long XIAOHONG_ID = 900001L;

    public static final long XIAOLV_ID = 900002L;

    public static final long MEIMEI_ID = 9000000004L;

    public static final long XINER_ID = 9000000005L;

    public static final long NIANNIAN_ID = 9000000006L;

    public static final long MENGYAO_ID = 9000000007L;

    public static final long RUANRUAN_ID = 9000000008L;

    public static final long DOUDOU_ID = 9000000009L;

    public static final long JIANGNAN_ID = 9000000010L;

    public static final long TIANTIAN_ID = 9000000011L;

    public static final long LIZI_ID = 9000000012L;

    private static final long[] SPORT_DESK_IDS = {
        XINER_ID, NIANNIAN_ID, MENGYAO_ID, RUANRUAN_ID,
        DOUDOU_ID, JIANGNAN_ID, TIANTIAN_ID, LIZI_ID
    };

    private static final Map<Long, String> SPORT_DESK_NAMES = Map.of(
        XINER_ID, "A 官方客服馨儿-加31领新",
        NIANNIAN_ID, "A_A客服年年 ID: 666",
        MENGYAO_ID, "AA 吉祥/UED客服官方-梦瑶",
        RUANRUAN_ID, "AAA-VIP高级助理软软",
        DOUDOU_ID, "官方客服🔥豆豆【新人福利】",
        JIANGNAN_ID, "官方群管⭐江南",
        TIANTIAN_ID, "官方专属小助理·甜甜",
        LIZI_ID, "吉祥/UED高级专属 栗子"
    );

    public static final String JIXIANG_MERCHANT = "10011";

    public static final String UED_MERCHANT = "10012";

    public static final String SPORT_MERCHANT = "sport";

    private OfficialCsNames() {
    }

    public static boolean isSportDesk(Long botId) {
        return botId != null && SPORT_DESK_NAMES.containsKey(botId);
    }

    /** 说球帝通讯录里固定出现的客服：客服美美，再加上原站那一批联系人。 */
    public static List<Long> sportContactIds() {
        List<Long> ids = new ArrayList<>(SPORT_DESK_IDS.length + 1);
        ids.add(MEIMEI_ID);
        for (long id : SPORT_DESK_IDS) {
            ids.add(id);
        }
        return ids;
    }

    /**
     * 说球帝查在线时，把通讯录里固定的客服补进 ids。没有好友记录也查。
     * 不是说球帝时不改 ids，返回空列表。
     */
    public static List<Long> appendSportOnlineIds(String merchantNo, List<Long> ids) {
        if (ids == null || merchantNo == null || !SPORT_MERCHANT.equals(merchantNo.trim())) {
            return List.of();
        }
        Set<Long> present = new HashSet<>(ids);
        for (Long id : sportContactIds()) {
            if (present.add(id)) {
                ids.add(id);
            }
        }
        return sportContactIds();
    }

    public static boolean isOfficial(Long botId) {
        return botId != null && (botId == XIAOHONG_ID || botId == XIAOLV_ID || botId == MEIMEI_ID
            || isSportDesk(botId));
    }

    public static String displayName(Long botId) {
        if (botId == null) {
            return null;
        }
        if (botId == XIAOHONG_ID) {
            return "吉祥体育官方客服";
        }
        if (botId == XIAOLV_ID) {
            return "UED官方客服";
        }
        if (botId == MEIMEI_ID) {
            return "客服美美";
        }
        return SPORT_DESK_NAMES.get(botId);
    }

    public static String platformName(Long botId) {
        if (botId == null) {
            return "";
        }
        if (botId == XIAOHONG_ID) {
            return "吉祥体育";
        }
        if (botId == XIAOLV_ID) {
            return "UED";
        }
        if (botId == MEIMEI_ID || isSportDesk(botId)) {
            return "说球帝";
        }
        return "";
    }

    public static String merchantNo(Long botId) {
        if (botId == null) {
            return null;
        }
        if (botId == XIAOHONG_ID) {
            return JIXIANG_MERCHANT;
        }
        if (botId == XIAOLV_ID) {
            return UED_MERCHANT;
        }
        if (botId == MEIMEI_ID || isSportDesk(botId)) {
            return SPORT_MERCHANT;
        }
        return null;
    }

    public static Long botIdForMerchant(String merchantNo) {
        if (merchantNo == null) {
            return null;
        }
        return switch (merchantNo.trim()) {
            case JIXIANG_MERCHANT -> XIAOHONG_ID;
            case UED_MERCHANT -> XIAOLV_ID;
            case SPORT_MERCHANT -> MEIMEI_ID;
            default -> null;
        };
    }

    /**
     * 各客服只检索自己的知识库。吉祥是 JX 文档，客服美美是职圈文档。
     * 来源标题以这个前缀开头才算该客服的标准说明。
     */
    public static String knowledgeSourcePrefix(Long botId) {
        if (botId == null) {
            return null;
        }
        if (botId == XIAOHONG_ID) {
            return "kb-entry-JX-";
        }
        if (botId == MEIMEI_ID || isSportDesk(botId)) {
            return "kb-entry-ZQ-";
        }
        return null;
    }
}
