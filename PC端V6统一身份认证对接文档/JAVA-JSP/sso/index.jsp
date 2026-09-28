<%@ page language="java" contentType="text/html; charset=UTF-8"
	pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
<title>业务系统认证集成-成功</title>
</head>

<body>
	<h1>1 当前登录用户姓名：${supwisdomCasLoginUser.name}</h1>
	<h1>2 当前登录用户认证系统(CAS)帐号：${supwisdomCasLoginUser.account}</h1>
	<h1>3 当前登录用户业务系统帐号：${supwisdomCasLoginUser.localAccount}</h1>
	<span style='color: red;'>注:同一个用户，在业务系统和认证系统(CAS)中的帐号不一致时会用到：</span>
	当前登录用户业务系统帐号
	<h1>4
		当前登录用户身份信息(代码和名称)：${supwisdomCasLoginUser.type}/${supwisdomCasLoginUser.typeName}</h1>
	<h1>
		<a href="logout.jsp" style="color: blue;">注销</a>
	</h1>
</body>
</html>