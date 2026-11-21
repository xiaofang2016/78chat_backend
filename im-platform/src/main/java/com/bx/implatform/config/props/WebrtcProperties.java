package com.bx.implatform.config.props;

import com.bx.implatform.config.ICEServer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Component
@ConfigurationProperties(prefix = "webrtc")
public class WebrtcProperties {

    private Integer maxChannel = 9;

    /** TURN/STUN 域名，换节点只改配置 */
    @JsonIgnore
    private String turnHost;

    @JsonIgnore
    private String turnUsername;

    @JsonIgnore
    private String turnCredential;

    /** 可选自定义列表；正常走 turn-host 自动组装 */
    private List<ICEServer> iceServers = new ArrayList<>();

    /** 给 /system/config 返回，保证每条 urls 非空 */
    public List<ICEServer> clientIceServers() {
        List<ICEServer> manual = iceServers.stream()
            .filter(s -> StringUtils.hasText(s.getUrls()))
            .toList();
        if (!manual.isEmpty() && manual.size() == iceServers.size()) {
            return manual;
        }
        return buildFromTurnHost();
    }

    public WebrtcProperties clientSnapshot() {
        WebrtcProperties snap = new WebrtcProperties();
        snap.setMaxChannel(maxChannel);
        snap.setIceServers(clientIceServers());
        return snap;
    }

    private List<ICEServer> buildFromTurnHost() {
        String host = StringUtils.hasText(turnHost) ? turnHost.trim() : "sturn.78chat0.com";
        String user = turnUsername;
        String cred = turnCredential;
        List<ICEServer> list = new ArrayList<>(4);
        list.add(ice("stun:" + host + ":3478", null, null));
        list.add(ice("turn:" + host + ":3478", user, cred));
        list.add(ice("turn:" + host + ":3478?transport=tcp", user, cred));
        list.add(ice("turns:" + host + ":5349?transport=tcp", user, cred));
        return list;
    }

    private static ICEServer ice(String urls, String username, String credential) {
        ICEServer s = new ICEServer();
        s.setUrls(urls);
        s.setUsername(username);
        s.setCredential(credential);
        return s;
    }

}
