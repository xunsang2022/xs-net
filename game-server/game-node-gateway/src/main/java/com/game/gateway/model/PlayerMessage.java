package com.game.gateway.model;

// 玩家消息协议
public class PlayerMessage {
    
//    // 创建玩家的请求消息
//    @
//    public static class CreatePlayerRequest {
//        private String playerId;
//        private String playerName;
//    }
    
    // 创建玩家的响应消息
    public static class CreatePlayerResponse {
        private boolean success;
        private String playerId;
        private String message;
        
        // 默认构造函数
        public CreatePlayerResponse() {}
        
        // 带参数的构造函数
        public CreatePlayerResponse(boolean success, String playerId, String message) {
            this.success = success;
            this.playerId = playerId;
            this.message = message;
        }
        
        // getter和setter方法
        public boolean isSuccess() {
            return success;
        }
        
        public void setSuccess(boolean success) {
            this.success = success;
        }
        
        public String getPlayerId() {
            return playerId;
        }
        
        public void setPlayerId(String playerId) {
            this.playerId = playerId;
        }
        
        public String getMessage() {
            return message;
        }
        
        public void setMessage(String message) {
            this.message = message;
        }
    }
}
