package com.bx.implatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 晒图识别记录（接口②）
 */
@Data
@TableName("im_show_image")
public class ShowImage {

    private Long id;

    /**
     * box-im 生成的识别事件唯一 id
     */
    private String eventId;

    private Long messageId;

    private Long groupId;

    private Long userId;

    private String userName;

    private String userAvatar;

    private String imageUrl;

    private String ocrText;

    /**
     * AI 抽取的结构化 JSON
     */
    private String fields;

    /**
     * AI 自判是否为有效晒图: NULL=未识别完成, true=是, false=否
     */
    private Boolean isValid;

    private String validReason;

    /**
     * 0:pending 1:ok 2:failed
     */
    private Integer recognizeStatus;

    /**
     * 0:pending 1:ok 2:failed 3:giveup
     */
    private Integer notifyStatus;

    private Integer notifyTryCount;

    private Date recognizedAt;

    private Date notifiedAt;

    private Date createdAt;

    private Date updatedAt;
}
