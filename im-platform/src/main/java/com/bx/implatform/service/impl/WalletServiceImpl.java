package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesMerchantSupport;
import com.bx.implatform.client.AagamesUserApiClient;
import com.bx.implatform.exception.GlobalException;
import com.bx.implatform.service.WalletService;
import com.bx.implatform.vo.WalletRecordItemVO;
import com.bx.implatform.vo.WalletRecordsVO;
import com.bx.implatform.vo.WalletSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class WalletServiceImpl implements WalletService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AagamesUserApiClient userApiClient;
    private final AagamesMerchantSupport merchantSupport;

    @Override
    public WalletSummaryVO summary(Long userId, String merchantNo) {
        String merchant = merchantSupport.requireMatchesUser(userId, merchantNo);
        JSONObject data = requireData(userApiClient.getJson(userId, "/wallet", null, "wallet-summary"));
        WalletSummaryVO vo = new WalletSummaryVO();
        vo.setMerchantNo(merchant);
        vo.setMoney(money(data.get("money")));
        vo.setDrawMoney(money(data.get("draw_money")));
        vo.setBenefitMoney(money(data.get("benefit_money")));
        return vo;
    }

    @Override
    public WalletRecordsVO records(Long userId, String merchantNo, String kind, Integer page) {
        String merchant = merchantSupport.requireMatchesUser(userId, merchantNo);
        String normalized = kind == null ? "" : kind.trim();
        int pageNo = page == null || page < 1 ? 1 : page;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("page", String.valueOf(pageNo));
        query.put("data_type", "1");
        String path;
        if ("redpacket".equals(normalized)) {
            path = "/benefitlist";
        } else if ("withdraw".equals(normalized)) {
            path = "/withdrawlist";
        } else if ("bill".equals(normalized)) {
            path = "/walletrecord";
        } else {
            throw new GlobalException(400, "流水类型无效");
        }
        JSONObject data = requireData(userApiClient.getJson(userId, path, query, "wallet-" + normalized));
        WalletRecordsVO vo = new WalletRecordsVO();
        vo.setMerchantNo(merchant);
        vo.setKind(normalized);
        JSONArray rows = data.getJSONArray("data");
        if (rows == null) {
            return vo;
        }
        for (int i = 0; i < rows.size(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row == null) {
                continue;
            }
            vo.getRecords().add(toItem(normalized, row, i));
        }
        return vo;
    }

    private WalletRecordItemVO toItem(String kind, JSONObject row, int index) {
        WalletRecordItemVO item = new WalletRecordItemVO();
        String id = row.getString("id");
        item.setId(id == null || id.isBlank() ? kind + "-" + index : id);
        item.setKind(kind);
        item.setCreatedAt(epochMillis(row.get("created_at")));
        BigDecimal amount = money(row.get("money"));
        if ("withdraw".equals(kind)) {
            item.setTitle(firstText(row, "提现", "bank_name", "log"));
            item.setAmount(amount.negate());
            return item;
        }
        if ("redpacket".equals(kind)) {
            item.setTitle(firstText(row, "红包", "name", "act_name"));
            item.setAmount(amount);
            return item;
        }
        item.setTitle(firstText(row, "账单", "log", "name", "act_name"));
        Integer type = row.getInteger("type");
        if (type != null && type == 2 && amount.signum() > 0) {
            amount = amount.negate();
        }
        item.setAmount(amount);
        return item;
    }

    private JSONObject requireData(JSONObject data) {
        if (data == null) {
            throw new GlobalException(500, "钱包数据为空");
        }
        Integer code = data.getInteger("code");
        if (code != null && code != 0 && code != 200) {
            String msg = data.getString("msg");
            throw new GlobalException(400, msg == null || msg.isBlank() ? "钱包查询失败" : msg);
        }
        return data;
    }

    private static BigDecimal money(Object value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
    }

    private static String firstText(JSONObject row, String fallback, String... keys) {
        for (String key : keys) {
            String value = row.getString(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return fallback;
    }

    private static Long epochMillis(Object value) {
        if (value == null) {
            return 0L;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "null".equals(text)) {
            return 0L;
        }
        if (text.chars().allMatch(Character::isDigit)) {
            long raw = Long.parseLong(text);
            return raw < 1_000_000_000_000L ? raw * 1000L : raw;
        }
        String normalized = text.replace('T', ' ');
        if (normalized.length() > 19) {
            normalized = normalized.substring(0, 19);
        }
        try {
            return LocalDateTime.parse(normalized, DATE_TIME).atZone(ZONE).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }
}
