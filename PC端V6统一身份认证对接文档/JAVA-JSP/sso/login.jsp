<%@ page language="java" contentType="text/html; charset=UTF-8"
	pageEncoding="UTF-8"%>
<%@ include file="supwisdom/CASUtils.jsp"%>
<%!public boolean doLogin(LoginUser loginUser, HttpServletRequest request) {
		HttpSession session = request.getSession();
		ServletContext application = session.getServletContext();
		// 如果使用了Spring可以用下面的方法获取spring的context对象
		// WebApplicationContext appContext = WebApplicationContextUtils.getWebApplicationContext(application);
		// 如果需要使用SpringMVC上下文、可以用下面的方法获取springMVC的context对象
		// WebApplicationContext mvcContext = RequestContextUtils.getWebApplicationContext(request);
		
		// TODO 需要业务系统重写
		
		
		return true;
	}%>
<%
	
	String targetUrl = CasUtils.getTargetUrl(request);
	if (CasUtils.isLogin(session)) {
		// LoginUser loginUser = (LoginUser)session.getAttribute(Constants.LOGIN_USER_KEY);
		response.sendRedirect(targetUrl);
	} else {
		if (CasUtils.hasTicket(request)) {
			LoginUser loginUser = CasUtils.getLoginUser(request);
			if (loginUser.isLogin() && doLogin(loginUser, request)) {
				CasUtils.login(loginUser, session);
				response.sendRedirect(targetUrl);
			} else {
				
				// TODO 可选：业务系统可根据实际情况进行处理
				response.sendRedirect(CasUtils.getErrorUrl(request));
			}
		} else {
			String loginUrl = CasUtils.getLoginUrl(request);
			response.sendRedirect(loginUrl);
		}
	}
%>