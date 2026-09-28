// 文件说明：TokenRefreshRequest：负责通用基础能力中的数据传输。
package org.unreal.modelrouter.common.dto;

public class TokenRefreshRequest {
    private String token;

    public TokenRefreshRequest() {
    }

    public String getToken() {
        return this.token;
    }

    public void setToken(final String token) {
        this.token = token;
    }

    public String toString() {
        return "TokenRefreshRequest(token=" + this.getToken() + ")";
    }
}
