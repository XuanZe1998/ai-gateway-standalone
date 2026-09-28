<%@ Page Language="C#" %>

<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<script runat="server">

</script>
<html xmlns="http://www.w3.org/1999/xhtml">
<head runat="server">
    <title></title>
</head>
<body>
    <form id="form1" runat="server">
    <div>
        <h1>
            1 当前登录用户姓名：<%= ((com.kingstar.sso.client.single.LoginUser)Session["supwisdomCasLoginUser"]).getName() %></h1>
        <h1>
            2 当前登录用户认证系统(CAS)帐号：<%= ((com.kingstar.sso.client.single.LoginUser)Session["supwisdomCasLoginUser"]).getAccount() %></h1>
        <h1>
            3 当前登录用户业务系统帐号：<%= ((com.kingstar.sso.client.single.LoginUser)Session["supwisdomCasLoginUser"]).getLocalAccount() %></h1>
        <span style='color: red;'>注:同一个用户，在业务系统和认证系统(CAS)中的帐号不一致时会用到：</span> 当前登录用户业务系统帐号
        <h1>
            <a href="logout.aspx" style="color: blue;">注销</a></h1>
    </div>
    </form>
</body>
</html>
