<%@ Page Language="C#" %>

<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<script runat="server">

    public bool doLogin(com.kingstar.sso.client.single.LoginUser loginUser, HttpRequest request)
    {
        // ADD YOUR CODE HERE
        return true;
    }

    protected void Page_Load(object sender, EventArgs e)
    {
        String targetUrl = com.kingstar.sso.client.single.CasUtils.getTargetUrl(Request);
        if (com.kingstar.sso.client.single.CasUtils.isLogin(Session))
        {
            Response.Redirect(targetUrl);
        }
        else
        {
            if (com.kingstar.sso.client.single.CasUtils.hasTicket(Request))
            {
                com.kingstar.sso.client.single.LoginUser loginUser
                    = com.kingstar.sso.client.single.CasUtils.getLoginUser(Request);
                if (loginUser.isLogin() && doLogin(loginUser, Request))
                {
                    com.kingstar.sso.client.single.CasUtils.login(loginUser, Session);
                    Response.Redirect(targetUrl);
                }
                else
                {
                    Response.Redirect(com.kingstar.sso.client.single.CasUtils.getLogoutUrl(Request));
                }
            }
            else
            {
                String loginUrl = com.kingstar.sso.client.single.CasUtils.getLoginUrl(Request);
                Response.Redirect(loginUrl);
            }
        }
    }

</script>
<html xmlns="http://www.w3.org/1999/xhtml">
<head runat="server">
    <title></title>
</head>
<body>
    <form id="form1" runat="server">
    <div>
    </div>
    </form>
</body>
</html>
