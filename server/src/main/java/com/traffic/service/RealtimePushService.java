package com.traffic.service;

import com.traffic.websocket.LiveWebSocketHandler;
import org.springframework.stereotype.Service;

@Service
public class RealtimePushService {

    public void broadcast(String json) {
        LiveWebSocketHandler.broadcast(json);
    }
}
