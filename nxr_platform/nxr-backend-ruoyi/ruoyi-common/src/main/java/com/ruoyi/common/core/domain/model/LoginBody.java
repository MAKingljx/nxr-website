package com.ruoyi.common.core.domain.model;

/**
 * 用户登录对象
 * 
 * @author ruoyi
 */
public class LoginBody
{
    /**
     * 用户名
     */
    private String username;

    /**
     * 用户密码
     */
    private String password;

    /**
     * 验证码
     */
    private String code;

    /**
     * 唯一标识
     */
    private String uuid;

    /**
     * Whether this browser requested a persistent sign-in session.
     * The server still controls the token lifetime; this flag is not a device trust bypass.
     */
    private boolean rememberMe;

    public String getUsername()
    {
        return username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    public String getPassword()
    {
        return password;
    }

    public void setPassword(String password)
    {
        this.password = password;
    }

    public String getCode()
    {
        return code;
    }

    public void setCode(String code)
    {
        this.code = code;
    }

    public String getUuid()
    {
        return uuid;
    }

    public void setUuid(String uuid)
    {
        this.uuid = uuid;
    }

    public boolean isRememberMe()
    {
        return rememberMe;
    }

    public void setRememberMe(boolean rememberMe)
    {
        this.rememberMe = rememberMe;
    }
}
