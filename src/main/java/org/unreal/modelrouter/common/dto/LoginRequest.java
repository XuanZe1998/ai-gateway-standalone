// 文件说明：LoginRequest：负责通用基础能力中的数据传输。
package org.unreal.modelrouter.common.dto;

public class LoginRequest {
    private String username;
    private String password;

    public LoginRequest() {
    }

    public String getUsername() {
        return this.username;
    }

    public void setUsername(final String username) {
        this.username = username;
    }

    public String getPassword() {
        return this.password;
    }

    public void setPassword(final String password) {
        this.password = password;
    }

    public String toString() {
        return "LoginRequest(username=" + this.getUsername() + ", password=" + this.getPassword() + ")";
    }
}