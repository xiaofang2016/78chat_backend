package com.bx.implatform.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.bx.implatform.client.AagamesBusinessClient;
import com.bx.implatform.service.SportTurnoverService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SportTurnoverServiceImpl implements SportTurnoverService {

    private final AagamesBusinessClient businessClient;

    @Override
    public JSONObject fetchSummary(Long userId) {
        return fetchSummary(userId, null, null);
    }

    @Override
    public JSONObject fetchSummary(Long userId, String startDate, String endDate) {
        return businessClient.sportTurnoverSummary(userId, startDate, endDate);
    }
}
