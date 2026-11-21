package com.bx.implatform.contant;

/**
 * AI 客服对用户可见回复中的产品/站点品牌名（内部代码与配置键仍可用 boxim/aagames）。
 */
public final class AiBrandingConstants {

    private AiBrandingConstants() {
    }

    /** 即时通讯产品对外名称 */
    public static final String IM_PRODUCT = "职圈";

    /**
     * 知识库共用词。吉祥体育和 UED 共用同一份知识库，条目里不写死其中一家。
     * 对用户说话时必须改成当前商户的平台名。
     */
    public static final String GAME_SITE = "本平台";

    /** 注入 system prompt 的品牌约束段落 */
    public static final String PROMPT_BLOCK =
        "【品牌用语 — 必读】即时通讯、聊天、客服入口统称「" + IM_PRODUCT + "」（禁止说 78CHAT、78chat、box-im、盒子IM）。"
            + "游戏、投注、余额、充值、提现、活动、推广属于当前用户的平台。"
            + "知识库里的「本平台」「本平台官网」不要原样说给用户，要改成当前平台名。"
            + "禁止说 7PG、7pg、7pg.IM、aagames、aagames_api。"
            + "知识库没有给出具体网址时，不要编造域名。\n\n";
}
