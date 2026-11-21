package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesUserApiClient;
import com.bx.implatform.config.props.AiCustomerServiceProperties;
import com.bx.implatform.contant.AiBrandingConstants;
import com.bx.implatform.entity.User;
import com.bx.implatform.enums.UserType;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.mapper.UserMapper;
import com.bx.implatform.service.AiToolService;
import com.bx.implatform.service.AvailableActivitiesService;
import com.bx.implatform.service.CommissionBonus999Service;
import com.bx.implatform.service.DailyGoodLuckGoldService;
import com.bx.implatform.service.FriendDepositBonus4215Service;
import com.bx.implatform.service.SportWeeklyBonusService;
import com.bx.implatform.service.SportDailyBonusService;
import com.bx.implatform.service.SportWinStreakService;
import com.bx.implatform.service.SportTurnoverService;
import com.bx.implatform.service.InviteFriendsBonusService;
import com.bx.implatform.service.InviteSpreadService;
import com.bx.implatform.service.LuckyBetSuffixService;
import com.bx.implatform.service.SpreadCommissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具实现：把 OpenAI tool_call 路由到对应的 aagames 用户接口。
 *
 * 第一阶段只实现 1 个工具（{@code get_balance}），跑通端到端后再扩。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolServiceImpl implements AiToolService {

    private final AagamesUserApiClient userApiClient;
    private final UserMapper userMapper;
    private final AiCustomerServiceProperties aiProps;
    private final LuckyBetSuffixService luckyBetSuffixService;
    private final DailyGoodLuckGoldService dailyGoodLuckGoldService;
    private final InviteFriendsBonusService inviteFriendsBonusService;
    private final InviteSpreadService inviteSpreadService;
    private final CommissionBonus999Service commissionBonus999Service;
    private final FriendDepositBonus4215Service friendDepositBonus4215Service;
    private final SportWeeklyBonusService sportWeeklyBonusService;
    private final SportDailyBonusService sportDailyBonusService;
    private final SportWinStreakService sportWinStreakService;
    private final SportTurnoverService sportTurnoverService;
    private final AvailableActivitiesService availableActivitiesService;
    private final SpreadCommissionService spreadCommissionService;

    @Override
    public JSONArray getToolsSchema() {
        JSONArray tools = new JSONArray();
        tools.add(buildTool(
            "get_server_time",
            "查询服务器当前时间（北京时间 UTC+8）。用户问现在几点、今天几号、昨天是哪天、当前日期时间时必须调用；"
                + "返回 now、today、yesterday、day_before_yesterday、timezone。回答时只使用该工具或系统提示里的【当前系统时间】。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_balance",
            "查询当前对话用户的钱包综合数据。返回字段含义：\n" +
                "- money: 主钱包可用余额（人民币元）\n" +
                "- flow_require / need_flow_money: 待完成的流水要求金额\n" +
                "- valid_bet: 有效投注\n" +
                "- deposit_times / deposit_money: 存款次数 / 累计存款金额\n" +
                "- draw_times / draw_money: 提款次数 / 累计提款金额\n" +
                "- benefit_times / benefit_money: 优惠/红包领取次数 / 金额\n" +
                "- can_get_benefit_money: 当前可领取的优惠金额\n" +
                "- payout: 待结算\n" +
                "- xcoin: 平台积分（X 币）\n" +
                "- *_money 形如 dc_pg_money / fb_sport_money / gmag_*_money: 各游戏平台子钱包余额\n" +
                "用户问余额、流水要求(flow_require/need_flow_money)、待完成打码量、钱包 valid_bet、"
                + "存提款、返水、X币、各平台钱包等都用此工具。"
                + "**「体育流水」请用 get_sport_turnover_summary；游戏投注明细/分类流水请用 get_bet_log**。"
                + "**绝不允许凭印象给具体金额，必须先调此工具拿到真实数据**。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_sport_turnover_summary",
            "查询当前对话用户的【体育流水/体育有效投注】汇总，与体育活动（日投注闯关、体育周周领）统计口径一致。"
                + "返回 today/yesterday/this_week/last_week 各时段 valid_bet（体育有效投注合计）"
                + "与 activity_eligible_valid_bet（活动计入口径，赔率≥1.50）；"
                + "可选 start_date/end_date（yyyy-MM-dd）查 custom 时段。"
                + "**用户问「体育流水」「体育有效投注」「今天/本周体育投了多少」时必须优先调用此工具**；"
                + "需要逐笔明细时再配合 get_bet_log（game_cate_id=4）。"
                + "不要用活动 status 接口代替流水查询。相对日期先 get_server_time。",
            optionalStringParams(new String[][]{
                {"start_date", "可选，自定义开始日期 yyyy-MM-dd"},
                {"end_date", "可选，自定义结束日期 yyyy-MM-dd"},
            })
        ));
        tools.add(buildTool(
            "get_wallet_record",
            "查询当前对话用户的【账户流水/账变记录】，对应 GET /walletrecord。"
                + "用户问「转账记录」「账户流水」「账变」「资金明细」「钱包记录」时必须调用，"
                + "禁止改口说无法查询或只引导去【我的】→【转账】。"
                + "type：1会员存款 2会员提现 5领取红利 6额度转账；问转账记录传 6，问全部账户流水不要传 type。"
                + "start_time/end_time 格式 yyyy-MM-dd，问今天/昨天时先用 get_server_time 再传入。"
                + "每条记录看 money、type、log、second_type_name、created_at、before_money、after_money。"
                + "金额和时间以工具返回为准。怎么转入转出的操作说明不要用本工具。",
            paginatedListParams("查询页码，默认 1", new String[][]{
                {"type", "可选。1存款 2提现 5红利 6额度转账。查转账记录传 6；查全部账户流水不要传"},
                {"start_time", "可选，开始日期 yyyy-MM-dd"},
                {"end_time", "可选，结束日期 yyyy-MM-dd"},
            })
        ));
        tools.add(buildTool(
            "get_bet_log",
            "查询当前对话用户的【投注记录与盈亏】。返回 data.data 按日期分组，list[] 含 winorlosses(单笔输赢)、" +
                "game_cate_name(分类)、bet、win、valid_wager、time 等；stat.valid_bet 为时段有效投注合计。" +
                "默认 page=1，可传 game_cate_id / start_time / end_time（yyyy-MM-dd）；筛体育明细传 game_cate_id=4。" +
                "**用户问「游戏流水」「投注流水」「投注明细」时用本工具**（不传 game_cate_id=全部分类）；"
                + "体育流水合计优先 get_sport_turnover_summary。" +
                "**用户只问盈亏/输赢/盈利/亏损且未问活动时，必须用此工具**：按用户所说日期传 start_time/end_time，"
                + "汇总 winorlosses 得该时段**全部分类**合计盈亏；不要调活动接口。" +
                "用户问投注明细、某游戏输赢、某日盈亏也用此工具；相对日期先 get_server_time。"
                + "**金额以工具返回为准，不得凭空估计**。",
            paginatedListParams("查询页码，默认 1", new String[][]{
                {"game_cate_id", "可选，游戏分类 id 过滤；不传则全部分类"},
                {"start_time", "可选，开始时间，格式 yyyy-MM-dd"},
                {"end_time", "可选，结束时间，格式 yyyy-MM-dd"},
            })
        ));
        tools.add(buildTool(
            "get_daily_good_luck_gold_status",
            "查询「天天好运金 / 雪中送炭 / 昨日亏损彩金」资格。只有用户点名这几项时才调用。"
                + "用户只说「活动」「优惠」「有什么活动」「有活动吗」时禁止调用，改用 get_available_activities。"
                + "统计昨日【电子+捕鱼+棋牌+德州】毛亏损，不含真人/彩票/体育。"
                + "用户只问盈亏/输赢、未点名这项彩金时禁止调用，应改用 get_bet_log。",
            dailyGoodLuckGoldStatusParams()
        ));
        tools.add(buildTool(
            "get_invite_friends_quota",
            "查询 " + AiBrandingConstants.GAME_SITE + "「邀请游戏好友送彩礼」活动（id=150137）：有效推广人数、各档彩礼与可领额度。"
                + "【好友=" + AiBrandingConstants.GAME_SITE + " 推广邀请的下级游戏用户，非 "
                + AiBrandingConstants.IM_PRODUCT + " 通讯录好友】。"
                + "当用户问邀请好友、推广、彩礼、拉新、好友注册充值等时必须调用。"
                + "数据来自 redpacket/quota，含 invite_friends.friend_scope、valid_promotion_count、"
                + "tiers、claimable_tiers、next_tier。若 canClaim=true 系统会自动展示领取按钮。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_invite_spread",
            "查询 " + AiBrandingConstants.GAME_SITE + "「邀请游戏好友」推广数据（friend_scope=aagames_invite_spread，"
                + "**不是** " + AiBrandingConstants.IM_PRODUCT + " 通讯录好友关系）："
                + "register_count=邀请注册下级总数、valid_bet_user_count=有有效投注人数、"
                + "valid_bet_invitees/no_valid_bet_invitees=按有效投注分组的用户名列表、"
                + "invitees=完整下级（has_valid_bet/has_deposit）。"
                + "当用户问有多少好友、邀请多少人、哪些有有效投注、谁充过值时必须调用；"
                + "与 get_invite_friends_quota 互补。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_spread_info",
            "查询 " + AiBrandingConstants.GAME_SITE + " 推广页数据 GET /spreadinfo（与 H5 赚钱页一致）。"
                + "关键字段：no_get_benefits=未领取佣金、total_benefits=累计佣金、has_get_benefits=已领取佣金、"
                + "register_count/valid_promotion_count、list=下级列表、invit_users_total、spread_url、spread_code。"
                + "当用户问未领取佣金、能领多少佣金、推广信息、推广链接时必须调用；"
                + "回答待领金额优先用 no_get_benefits。",
            spreadInfoParams()
        ));
        tools.add(buildTool(
            "get_spread_stat",
            "查询 " + AiBrandingConstants.GAME_SITE + " 推广统计 GET /spreadstat：period_label、period_stats（注册/活跃/有效投注人数）、"
                + "friend_summary（好友数、下级总有效投注、total_commission）、chart（按日新增与有效投注曲线）。"
                + "当用户问本周/本月推广数据、推广趋势、好友汇总统计时调用。"
                + "可选 period：this_week/last_week/this_month/last_month。",
            spreadStatParams()
        ));
        tools.add(buildTool(
            "get_spread_commission_status",
            "查询推广佣金钱包简要汇总（" + AiBrandingConstants.IM_PRODUCT + " 对接接口）：pending_claimable、claimed_commission、total_commission。"
                + "若需与 H5 一致的未领字段 no_get_benefits，请改用 get_spread_info。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_spread_commission_log",
            "查询 " + AiBrandingConstants.GAME_SITE + " 佣金明细 GET /spread_commission_log（与 H5 佣金记录 Tab 一致）。"
                + "返回 period_label、commission_summary（period_pending/period_claimed/period_total）、"
                + "list[]（money、status_label、new_username、created_at、claimable）。"
                + "当用户问佣金记录、佣金明细、某段时间佣金时必须调用。"
                + "可选 period：this_week/last_week/this_month/last_month；status：1待领 2已领；page 默认 1。",
            spreadCommissionLogParams()
        ));
        tools.add(buildTool(
            "get_commission_bonus_status",
            "查询「佣金加倍 999 奖上奖」活动（id=143755）：按累计已领取推广佣金分档加码。"
                + "当用户问能否享受加倍活动、佣金加码、999奖上奖、躺着赚佣金、每周任务佣金奖时必须调用。"
                + "返回 claimed_commission_total、tiers（门槛 30/200/1000/10000）、claimable_tiers、"
                + "remain_bonus_total、next_tier、flow_multiplier(3倍流水)。"
                + "若 can_claim=true 且有 claimable_tiers，系统会在回复后自动展示领取按钮。"
                + "用户同时问基础佣金与加码时，应同时调用 get_spread_commission_status 与本工具。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_friend_deposit_bonus_status",
            "查询「好友存款领赏金 4215」活动（id=143523）：按当周邀请下级累计存款分档领取。"
                + "当用户问好友存款赏金、4215、好友任务存款奖、赚钱好友任务、当周好友存款时必须调用。"
                + "返回 weekly_friend_deposit_total、week_start/week_end、tiers（门槛 1000/5000/10000/100000/300000/1000000）、"
                + "claimable_tiers、remain_bonus_total、next_tier、flow_multiplier(3倍流水)。"
                + "若 can_claim=true 且有 claimable_tiers，系统会在回复后自动展示领取按钮。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_weekly_sports_bonus_status",
            "查询「体育周周领」活动：按自然周体育有效投注（赔率≥1.50）匹配最高档彩金，次周一 12:00 起 3 天内领取。"
                + "当用户问体育周周领、周周领、体育周彩金、最高领588时必须调用。"
                + "返回 current_week（本周投注进度）、claim（上周可领：weekly_valid_bet/bonus/can_claim/claim_window_open）、"
                + "flow_multiplier(3倍体育流水)。若 claim.can_claim=true，系统会自动展示领取按钮。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_daily_sports_bonus_status",
            "查询「日投注闯关」活动：按自然日体育有效投注（赔率≥1.50）匹配最高档彩金，次日 00:00 至 23:59 领取。"
                + "当用户问日投注闯关、体育每日任务、每日体育任务、最高领58时必须调用。"
                + "返回 current_day（今日投注进度）、claim（昨日可领：daily_valid_bet/bonus/can_claim/claim_window_open）、"
                + "flow_multiplier(3倍体育流水)。若 claim.can_claim=true，系统会自动展示领取按钮。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_sport_win_streak_status",
            "查询「体育连红挑战」活动：连续符合条件的单关体育赢单（投注≥200、赔率≥1.75）累计连红，达标后领取最高档彩金，每周一次。"
                + "当用户问体育连红挑战、连红、连红挑战、最高领88时必须调用。"
                + "返回 streak（current_streak/current_level/need_more_wins）、claim（bonus/can_apply/can_claim_redpacket/already_claimed_this_week）、"
                + "flow_multiplier(3倍体育流水)。流程：先点「提交申请」系统自动核查，通过后点「领取红包」。"
                + "若 claim.can_apply=true 展示申请按钮；若 claim.can_claim_redpacket=true 展示领取红包按钮。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_available_activities",
            "汇总当前用户所属商户正在进行的活动。名单以接口返回为准，10011 与 10012 不同，禁止补进接口里没有的活动。"
                + "用户说「活动」「有活动吗」「优惠」「优惠活动」「有什么活动」「可以参加的活动有哪些」时必须调用，"
                + "不要改查天天好运金。"
                + "返回 activity_summaries[]，每条含 name、summary、can_claim_now、remain_bonus_total 等；"
                + "回答时只逐条列出本次 summaries，不要增删；若 can_claim_now=true，系统会自动展示对应领取按钮。",
            emptyParams()
        ));
        tools.add(buildTool(
            "get_lucky_bet_eligibility",
            "查询当前对话用户在活动 id=132499（幸运电子/老虎机注单尾号加码）中的可领取注单。"
                + "当用户提到「幸运电子活动」「幸运老虎机活动」或询问尾号 8 加码时必须调用。"
                + "返回 eligible、matches（含 applicable/bonus/transaction_id/suffix/bet）、best_match、claimable_matches。"
                + "用查询结果组织自然语言回复即可；若 applicable=1，系统会在你的文字回复之后自动单独展示申请按钮，无需在回复中引导点击。"
                + "禁止传 date_from、date_to、start_time、end_time 等任何日期参数（接口按全历史注单查询）。",
            luckyBetEligibilityParams()
        ));
        tools.add(buildTool(
            "get_benefit_list",
            "查询当前对话用户的【优惠/红利领取记录】。返回结构 data.data 是按日期分组的数组，每个元素含 date 与 list[]；" +
                "list 内每条记录关键字段：money(优惠金额)、name/act_name(活动名)、status(状态)、flow(流水倍数)、" +
                "flow_money(已完成流水)、benefit_type(类型)、created_at(领取时间)、symbol(币种)。" +
                "默认 page=1（每页 20 条），可指定 status / start_time / end_time 过滤。" +
                "用户问'我领过哪些红利'、'X 月的优惠'、'还差多少流水'都用此工具；金额/笔数一律以工具返回为准。",
            paginatedListParams("查询页码，默认 1", new String[][]{
                {"status", "可选，状态过滤；不传则全部"},
                {"start_time", "可选，开始时间，格式 yyyy-MM-dd"},
                {"end_time", "可选，结束时间，格式 yyyy-MM-dd"},
            })
        ));
        tools.add(buildTool(
            "get_member",
            "查询当前对话用户在 " + AiBrandingConstants.GAME_SITE + " 的【会员资料】。对应 GET /member（routes/user_api.php）。\n" +
                "关键字段：\n" +
                "- created_at: 注册时间（判断用户是否新注册的唯一依据，**必须先调此工具再下结论**）\n" +
                "- username: 用户名\n" +
                "- level: 会员等级\n" +
                "- last_login_time: 最后登录时间\n" +
                "- login_counts: 登录次数\n" +
                "- register_ip_area / last_login_ip_area: 注册/登录 IP 归属地\n" +
                "- user_wallet: 钱包摘要（含 deposit_times/deposit_money 等）\n" +
                "用户问'我是不是新用户'、'什么时候注册的'、'注册多久了'、新人福利资格等，**必须**先调此工具，" +
                "用 created_at 与当前时间对比判断是否新注册；不得凭印象回答。",
            emptyParams()
        ));
        return tools;
    }

    // ---------- tool params builder ----------

    private static JSONObject emptyParams() {
        JSONObject p = new JSONObject();
        p.put("type", "object");
        p.put("properties", new JSONObject());
        p.put("required", new JSONArray());
        return p;
    }

    /**
     * 构造分页 + 时间区间风格的 tool 参数 schema。
     */
    private static JSONObject dailyGoodLuckGoldStatusParams() {
        JSONObject props = new JSONObject();
        JSONObject statDate = new JSONObject();
        statDate.put("type", "string");
        statDate.put("description", "可选，统计日 Y-m-d，默认昨日（北京时间）");
        props.put("stat_date", statDate);
        JSONObject p = new JSONObject();
        p.put("type", "object");
        p.put("properties", props);
        p.put("required", new JSONArray());
        return p;
    }

    private static JSONObject spreadInfoParams() {
        JSONObject props = new JSONObject();
        JSONObject page = new JSONObject();
        page.put("type", "integer");
        page.put("description", "下级列表页码，默认 1");
        page.put("minimum", 1);
        props.put("page", page);
        JSONObject start = new JSONObject();
        start.put("type", "string");
        start.put("description", "可选，筛选下级注册开始日期 yyyy-MM-dd");
        props.put("start_time", start);
        JSONObject end = new JSONObject();
        end.put("type", "string");
        end.put("description", "可选，筛选下级注册结束日期 yyyy-MM-dd");
        props.put("end_time", end);
        JSONObject p = new JSONObject();
        p.put("type", "object");
        p.put("properties", props);
        p.put("required", new JSONArray());
        return p;
    }

    private static JSONObject spreadStatParams() {
        JSONObject props = new JSONObject();
        JSONObject period = new JSONObject();
        period.put("type", "string");
        period.put("description", "可选，this_week/last_week/this_month/last_month，默认 this_week");
        props.put("period", period);
        JSONObject start = new JSONObject();
        start.put("type", "string");
        start.put("description", "可选，自定义开始日期 yyyy-MM-dd（与 end_time 成对）");
        props.put("start_time", start);
        JSONObject end = new JSONObject();
        end.put("type", "string");
        end.put("description", "可选，自定义结束日期 yyyy-MM-dd");
        props.put("end_time", end);
        JSONObject p = new JSONObject();
        p.put("type", "object");
        p.put("properties", props);
        p.put("required", new JSONArray());
        return p;
    }

    private static JSONObject spreadCommissionLogParams() {
        JSONObject props = new JSONObject();
        JSONObject period = new JSONObject();
        period.put("type", "string");
        period.put("description", "可选，this_week/last_week/this_month/last_month，默认 this_week");
        props.put("period", period);
        JSONObject page = new JSONObject();
        page.put("type", "integer");
        page.put("description", "页码，默认 1");
        page.put("minimum", 1);
        props.put("page", page);
        JSONObject status = new JSONObject();
        status.put("type", "integer");
        status.put("description", "可选，1=待领取 2=已领取");
        props.put("status", status);
        JSONObject p = new JSONObject();
        p.put("type", "object");
        p.put("properties", props);
        p.put("required", new JSONArray());
        return p;
    }

    /** 仅 transaction_id 可选；活动固定 132499，禁止日期参数 */
    private static JSONObject luckyBetEligibilityParams() {
        JSONObject props = new JSONObject();
        JSONObject txn = new JSONObject();
        txn.put("type", "string");
        txn.put("description", "可选，仅校验指定注单号时传入");
        props.put("transaction_id", txn);
        JSONObject p = new JSONObject();
        p.put("type", "object");
        p.put("properties", props);
        p.put("required", new JSONArray());
        return p;
    }

    private static JSONObject paginatedListParams(String pageDesc, String[][] extras) {
        JSONObject props = new JSONObject();
        JSONObject pageProp = new JSONObject();
        pageProp.put("type", "integer");
        pageProp.put("description", pageDesc);
        pageProp.put("minimum", 1);
        props.put("page", pageProp);
        for (String[] kv : extras) {
            JSONObject sp = new JSONObject();
            sp.put("type", "string");
            sp.put("description", kv[1]);
            props.put(kv[0], sp);
        }
        JSONObject p = new JSONObject();
        p.put("type", "object");
        p.put("properties", props);
        p.put("required", new JSONArray());
        return p;
    }

    @Override
    public JSONObject invoke(Long userId, String name, String arguments) {
        if (userId == null || userId <= 0) {
            return error("invalid_user", "用户身份缺失");
        }
        if (name == null || name.isBlank()) {
            return error("invalid_tool", "工具名缺失");
        }
        if (!"get_server_time".equals(name) && isGuestUser(userId)) {
            return error("guest", "访客未登录游戏账号，无法查询账户数据");
        }
        // arguments 解析（容错）：AI 可能传空串或裸 {}
        JSONObject args;
        try {
            args = (arguments == null || arguments.isBlank())
                ? new JSONObject() : JSON.parseObject(arguments);
        } catch (Exception e) {
            log.warn("[ai-cs.tool] {} bad arguments: {}", name, arguments);
            args = new JSONObject();
        }
        try {
            switch (name) {
                case "get_server_time":
                    return invokeGetServerTime();
                case "get_balance":
                    return invokeGetBalance(userId);
                case "get_wallet_record":
                    return invokeGetWalletRecord(userId, args);
                case "get_bet_log":
                    return invokeGetBetLog(userId, args);
                case "get_sport_turnover_summary":
                    return invokeGetSportTurnoverSummary(userId, args);
                case "get_benefit_list":
                    return invokeGetBenefitList(userId, args);
                case "get_member":
                    return invokeGetMember(userId);
                case "get_lucky_bet_eligibility":
                    return invokeGetLuckyBetEligibility(userId, args);
                case "get_daily_good_luck_gold_status":
                    return invokeGetDailyGoodLuckGoldStatus(userId, args);
                case "get_invite_friends_quota":
                    return invokeGetInviteFriendsQuota(userId);
                case "get_invite_spread":
                    return invokeGetInviteSpread(userId);
                case "get_spread_info":
                    return invokeGetSpreadInfo(userId, args);
                case "get_spread_stat":
                    return invokeGetSpreadStat(userId, args);
                case "get_spread_commission_status":
                    return invokeGetSpreadCommissionStatus(userId);
                case "get_spread_commission_log":
                    return invokeGetSpreadCommissionLog(userId, args);
                case "get_commission_bonus_status":
                    return invokeGetCommissionBonusStatus(userId);
                case "get_friend_deposit_bonus_status":
                    return invokeGetFriendDepositBonusStatus(userId);
                case "get_weekly_sports_bonus_status":
                    return invokeGetWeeklySportsBonusStatus(userId);
                case "get_daily_sports_bonus_status":
                    return invokeGetDailySportsBonusStatus(userId);
                case "get_sport_win_streak_status":
                    return invokeGetSportWinStreakStatus(userId);
                case "get_available_activities":
                    return invokeGetAvailableActivities(userId);
                default:
                    return error("unknown_tool", "未知工具: " + name);
            }
        } catch (GlobalException ge) {
            log.info("[ai-cs.tool] {} userId={} business err code={} msg={}", name, userId, ge.getCode(), ge.getMessage());
            JSONObject err = new JSONObject();
            err.put("ok", false);
            err.put("error", ge.getMessage());
            err.put("code", ge.getCode());
            return err;
        } catch (Exception e) {
            log.warn("[ai-cs.tool] {} userId={} unexpected err: {}", name, userId, e.getMessage(), e);
            return error("internal_error", "工具调用失败");
        }
    }

    // ---------- 各工具实现 ----------

    private JSONObject invokeGetServerTime() {
        String tzId = aiProps.getTimezone();
        if (tzId == null || tzId.isBlank()) {
            tzId = "Asia/Shanghai";
        }
        ZoneId zone = ZoneId.of(tzId);
        ZonedDateTime now = ZonedDateTime.now(zone);
        String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        JSONObject data = new JSONObject(new LinkedHashMap<>());
        data.put("now", formatted);
        data.put("today", now.toLocalDate().toString());
        data.put("yesterday", now.minusDays(1).toLocalDate().toString());
        data.put("day_before_yesterday", now.minusDays(2).toLocalDate().toString());
        data.put("timezone", tzId);
        data.put("utc_offset", "+08:00");
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "im-platform:server-clock");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetBalance(Long userId) {
        JSONObject data = userApiClient.getJson(userId, "/wallet", null, "get_balance");
        return wrapUserApiResult(data, "aagames:/wallet");
    }

    private JSONObject invokeGetWalletRecord(Long userId, JSONObject args) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("page", String.valueOf(parsePositiveInt(args.get("page"), 1)));
        q.put("page_size", "20");
        q.put("data_type", "1");
        putIfNonBlank(q, "type", args.getString("type"));
        putIfNonBlank(q, "start_time", args.getString("start_time"));
        putIfNonBlank(q, "end_time", args.getString("end_time"));
        JSONObject data = userApiClient.getJson(userId, "/walletrecord", q, "get_wallet_record");
        return wrapUserApiResult(data, "aagames:/walletrecord");
    }

    private JSONObject invokeGetBetLog(Long userId, JSONObject args) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("page", String.valueOf(parsePositiveInt(args.get("page"), 1)));
        q.put("data_type", "2");
        q.put("inte_currency_id", "");
        putIfNonBlank(q, "game_cate_id", args.getString("game_cate_id"));
        putIfNonBlank(q, "start_time", args.getString("start_time"));
        putIfNonBlank(q, "end_time", args.getString("end_time"));

        JSONObject data = userApiClient.getJson(userId, "/betlog", q, "get_bet_log");
        return wrapUserApiResult(data, "aagames:/betlog");
    }

    private JSONObject invokeGetSportTurnoverSummary(Long userId, JSONObject args) {
        Map<String, String> q = new LinkedHashMap<>();
        putIfNonBlank(q, "start_date", args == null ? null : args.getString("start_date"));
        putIfNonBlank(q, "end_date", args == null ? null : args.getString("end_date"));
        try {
            JSONObject data = userApiClient.getJson(userId, "/sport-turnover/summary",
                q.isEmpty() ? null : q, "get_sport_turnover_summary");
            JSONObject wrapped = wrapUserApiResult(data, "aagames:/sport-turnover/summary");
            if (Boolean.TRUE.equals(wrapped.getBoolean("ok"))
                && isSportTurnoverPayload(wrapped.getJSONObject("data"))) {
                log.info("[ai-cs.tool] sport-turnover user_api ok userId={} today_valid_bet={}",
                    userId, wrapped.getJSONObject("data").getJSONObject("today").get("valid_bet"));
                return wrapped;
            }
            log.warn("[ai-cs.tool] sport-turnover user_api invalid payload userId={} body={}", userId, wrapped);
        } catch (GlobalException ge) {
            log.warn("[ai-cs.tool] sport-turnover user_api err userId={} code={} msg={}",
                userId, ge.getCode(), ge.getMessage());
        } catch (Exception e) {
            log.warn("[ai-cs.tool] sport-turnover user_api err userId={} msg={}", userId, e.getMessage());
        }
        try {
            String startDate = args == null ? null : args.getString("start_date");
            String endDate = args == null ? null : args.getString("end_date");
            JSONObject data = sportTurnoverService.fetchSummary(userId, startDate, endDate);
            if (isSportTurnoverPayload(data)) {
                JSONObject out = new JSONObject(new LinkedHashMap<>());
                out.put("ok", true);
                out.put("source", "aagames:/external/user/{userId}/sport-turnover/summary");
                out.put("data", data);
                log.info("[ai-cs.tool] sport-turnover external ok userId={} today_valid_bet={}",
                    userId, data.getJSONObject("today").get("valid_bet"));
                return out;
            }
            log.warn("[ai-cs.tool] sport-turnover external invalid payload userId={}", userId);
        } catch (GlobalException ge) {
            log.warn("[ai-cs.tool] sport-turnover external err userId={} code={} msg={}",
                userId, ge.getCode(), ge.getMessage());
        }
        JSONObject betlogFallback = sportTurnoverFromBetLog(userId, args);
        if (betlogFallback != null) {
            log.info("[ai-cs.tool] sport-turnover betlog fallback ok userId={}", userId);
            return betlogFallback;
        }
        log.warn("[ai-cs.tool] sport-turnover all paths failed userId={}", userId);
        return error("sport_turnover_unavailable", "体育流水接口不可用，请确认已登录游戏账号或稍后重试");
    }

    /** 接口不可用时，用 /betlog?game_cate_id=4 拼出最小汇总。 */
    private JSONObject sportTurnoverFromBetLog(Long userId, JSONObject args) {
        JSONObject blArgs = new JSONObject();
        blArgs.put("game_cate_id", "4");
        blArgs.put("page", 1);
        String start = args == null ? null : args.getString("start_date");
        String end = args == null ? null : args.getString("end_date");
        if (start != null && !start.isBlank()) {
            blArgs.put("start_time", start.trim());
        }
        if (end != null && !end.isBlank()) {
            blArgs.put("end_time", end.trim());
        }
        JSONObject betlog = invokeGetBetLog(userId, blArgs);
        if (betlog == null || !Boolean.TRUE.equals(betlog.getBoolean("ok"))) {
            return null;
        }
        JSONObject data = betlog.getJSONObject("data");
        if (data == null) {
            return null;
        }
        JSONObject stat = data.getJSONObject("stat");
        if (stat == null) {
            return null;
        }
        double validBet = parseAmount(stat.getString("valid_bet"));
        if (validBet < 0) {
            return null;
        }
        String day = (start != null && !start.isBlank()) ? start.trim() : java.time.LocalDate.now(
            java.time.ZoneId.of(aiProps.getTimezone() != null && !aiProps.getTimezone().isBlank()
                ? aiProps.getTimezone() : "Asia/Shanghai")).toString();
        JSONObject period = new JSONObject(new LinkedHashMap<>());
        period.put("start_date", day);
        period.put("end_date", (end != null && !end.isBlank()) ? end.trim() : day);
        period.put("valid_bet", validBet);
        period.put("activity_eligible_valid_bet", validBet);
        period.put("bet_count", null);
        JSONObject summary = new JSONObject(new LinkedHashMap<>());
        summary.put("timezone", aiProps.getTimezone());
        summary.put("stat_time_basis", "betlog fallback");
        summary.put("scope", "game_cate_id=4");
        summary.put("today", period);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/betlog?game_cate_id=4 (fallback)");
        out.put("data", summary);
        return out;
    }

    private static boolean isSportTurnoverPayload(JSONObject data) {
        return data != null && data.getJSONObject("today") != null;
    }

    private static double parseAmount(String s) {
        if (s == null || s.isBlank()) {
            return -1;
        }
        try {
            return Double.parseDouble(s.replace(",", "").trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private JSONObject invokeGetInviteFriendsQuota(Long userId) {
        JSONObject data = inviteFriendsBonusService.fetchQuota(userId);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/external/user/{userId}/redpacket/quota");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetInviteSpread(Long userId) {
        JSONObject data = inviteSpreadService.fetchSummary(userId);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/external/user/{userId}/invite-spread");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetSpreadInfo(Long userId, JSONObject args) {
        Integer page = args == null ? null : args.getInteger("page");
        String startTime = args == null ? null : args.getString("start_time");
        String endTime = args == null ? null : args.getString("end_time");
        JSONObject data = spreadCommissionService.fetchSpreadInfo(userId,
            page == null ? 1 : page, 20, startTime, endTime);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/spreadinfo");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetSpreadStat(Long userId, JSONObject args) {
        String period = args == null ? null : args.getString("period");
        String startTime = args == null ? null : args.getString("start_time");
        String endTime = args == null ? null : args.getString("end_time");
        JSONObject data = spreadCommissionService.fetchSpreadStat(userId, period, startTime, endTime);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/spreadstat");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetSpreadCommissionStatus(Long userId) {
        JSONObject data = spreadCommissionService.fetchWalletStatus(userId);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/external/user/{userId}/spread-commission/status");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetSpreadCommissionLog(Long userId, JSONObject args) {
        String period = args == null ? null : args.getString("period");
        Integer page = args == null ? null : args.getInteger("page");
        Integer status = args == null ? null : args.getInteger("status");
        JSONObject data = spreadCommissionService.fetchCommissionLog(userId, period, page, 20, status);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/spread_commission_log");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetCommissionBonusStatus(Long userId) {
        JSONObject data = commissionBonus999Service.fetchStatus(userId);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/external/user/{userId}/commission-bonus/status");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetFriendDepositBonusStatus(Long userId) {
        JSONObject data = friendDepositBonus4215Service.fetchStatus(userId);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/external/user/{userId}/friend-deposit-bonus/status");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetWeeklySportsBonusStatus(Long userId) {
        JSONObject data = sportWeeklyBonusService.fetchStatus(userId);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/external/user/{userId}/weekly-sports-bonus/status");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetDailySportsBonusStatus(Long userId) {
        JSONObject data = sportDailyBonusService.fetchStatus(userId);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/external/user/{userId}/daily-sports-bonus/status");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetSportWinStreakStatus(Long userId) {
        JSONObject data = sportWinStreakService.fetchStatus(userId);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/external/user/{userId}/sport-win-streak/status");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetAvailableActivities(Long userId) {
        JSONObject data = availableActivitiesService.fetchOverview(userId);
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "im-platform:available-activities-overview");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetDailyGoodLuckGoldStatus(Long userId, JSONObject args) {
        String statDate = args == null ? null : args.getString("stat_date");
        JSONObject data = statDate == null || statDate.isBlank()
            ? dailyGoodLuckGoldService.fetchStatus(userId)
            : dailyGoodLuckGoldService.fetchStatus(userId, statDate.trim());
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/activity/daily-good-luck-gold/status");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetLuckyBetEligibility(Long userId, JSONObject args) {
        if (args != null) {
            for (String forbidden : new String[]{"date_from", "date_to", "start_time", "end_time", "date"}) {
                if (args.containsKey(forbidden) && args.get(forbidden) != null
                    && !String.valueOf(args.get(forbidden)).isBlank()) {
                    log.warn("[ai-cs.tool] get_lucky_bet_eligibility ignored forbidden param {}", forbidden);
                }
            }
        }
        String txn = args == null ? null : args.getString("transaction_id");
        JSONObject data = txn == null || txn.isBlank()
            ? luckyBetSuffixService.fetchEligibility(userId, LuckyBetSuffixService.DEFAULT_ACTIVITY_ID)
            : luckyBetSuffixService.fetchEligibility(userId, LuckyBetSuffixService.DEFAULT_ACTIVITY_ID, txn.trim());
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/activity/lucky-bet-suffix/eligibility");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetBenefitList(Long userId, JSONObject args) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("page", String.valueOf(parsePositiveInt(args.get("page"), 1)));
        q.put("data_type", "2");
        q.put("inte_currency_id", "");
        putIfNonBlank(q, "status", args.getString("status"));
        putIfNonBlank(q, "start_time", args.getString("start_time"));
        putIfNonBlank(q, "end_time", args.getString("end_time"));

        JSONObject data = userApiClient.getJson(userId, "/benefitlist", q, "get_benefit_list");
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/benefitlist");
        out.put("data", data);
        return out;
    }

    private JSONObject invokeGetMember(Long userId) {
        // aagames_api user_api.php: GET /member → UserModel::getDetail，created_at 即注册时间
        JSONObject data = userApiClient.getJson(userId, "/member", null, "get_member");
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", "aagames:/member");
        out.put("data", data);
        String createdAt = data == null ? null : data.getString("created_at");
        if (createdAt != null && !createdAt.isBlank()) {
            out.put("register_time", createdAt);
        }
        return out;
    }

    // ---------- arg parsing ----------

    private static int parsePositiveInt(Object v, int dft) {
        if (v == null) return dft;
        try {
            int n;
            if (v instanceof Integer i) n = i;
            else if (v instanceof Long l) n = l.intValue();
            else n = Integer.parseInt(String.valueOf(v).trim());
            return n > 0 ? n : dft;
        } catch (Exception e) {
            return dft;
        }
    }

    private static void putIfNonBlank(Map<String, String> q, String k, String v) {
        if (v != null && !v.isBlank()) {
            q.put(k, v);
        }
    }

    // ---------- helper ----------

    private static JSONObject buildTool(String name, String description, JSONObject parameters) {
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", parameters);
        tool.put("function", fn);
        return tool;
    }

    private static JSONObject optionalStringParams(String[][] fields) {
        JSONObject props = new JSONObject();
        for (String[] kv : fields) {
            JSONObject sp = new JSONObject();
            sp.put("type", "string");
            sp.put("description", kv[1]);
            props.put(kv[0], sp);
        }
        JSONObject p = new JSONObject();
        p.put("type", "object");
        p.put("properties", props);
        p.put("required", new JSONArray());
        return p;
    }

    private static JSONObject wrapUserApiResult(JSONObject data, String source) {
        if (data != null) {
            Integer code = data.getInteger("code");
            if (code != null && code != 0 && code != 200) {
                JSONObject out = new JSONObject(new LinkedHashMap<>());
                out.put("ok", false);
                out.put("source", source);
                out.put("code", code);
                String msg = data.getString("msg");
                if (msg == null || msg.isBlank()) {
                    msg = data.getString("message");
                }
                out.put("message", msg == null ? "upstream business error" : msg);
                return out;
            }
        }
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", true);
        out.put("source", source);
        out.put("data", data);
        return out;
    }

    private static JSONObject toolError(GlobalException ge) {
        JSONObject out = new JSONObject(new LinkedHashMap<>());
        out.put("ok", false);
        out.put("code", ge.getCode());
        out.put("message", ge.getMessage());
        return out;
    }

    private boolean isGuestUser(Long userId) {
        User user = userMapper.selectById(userId);
        return user != null && UserType.GUEST.getValue().equals(user.getType());
    }

    private static JSONObject error(String code, String msg) {
        JSONObject e = new JSONObject();
        e.put("ok", false);
        e.put("error", code);
        e.put("message", msg);
        return e;
    }
}
