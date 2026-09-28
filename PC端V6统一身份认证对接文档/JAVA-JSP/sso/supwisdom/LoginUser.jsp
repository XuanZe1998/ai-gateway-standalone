<%@ page language="java"
	import="javax.xml.parsers.*, java.io.*, org.w3c.dom.*, java.lang.reflect.Field"
	contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="CommonUtils.jsp"%>
<%!public static class LoginUser {

		public static final String CAS_PREFIX = "cas:";
		public static final String LOGIN_SUCCESS_KEY = CAS_PREFIX
				+ "authenticationSuccess";
		public static final String ACCOUNT_KEY = CAS_PREFIX + "user";
		public static final String ATTRIBUTES_KEY = CAS_PREFIX + "attributes";

		private String account;

		private String ssoAccount;
		private String deptName;
		private String idCard;
		private String studentNo;
		private String remark;
		private String localAccount;
		private String tel;
		private String dicOrgId;
		private String nick;
		private String email;
		private String staffNo;
		private String name;
		private String deptCode;
		private String dn;
		private String mobile;
		private String typeCode;
		private String type;
		private String typeName;

		public LoginUser(String loginUserXmlStr) {
			if (StringUtils.isEmpty(loginUserXmlStr))
				return;
			DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory
					.newInstance();
			InputStream in = null;
			try {
				DocumentBuilder docBuilder = docBuilderFactory
						.newDocumentBuilder();
				in = IOUtils.toInputStream(loginUserXmlStr, Constants.UTF_8);
				Document rootDoc = docBuilder.parse(in);
				NodeList successNodeList = rootDoc
						.getElementsByTagName(LOGIN_SUCCESS_KEY);
				if (successNodeList.getLength() > 0) {
					Node successNode = successNodeList.item(0);
					Document successDocument = successNode.getOwnerDocument();
					NodeList accountNodeList = successDocument
							.getElementsByTagName(ACCOUNT_KEY);
					if (accountNodeList != null
							&& accountNodeList.getLength() > 0) {
						Node accountNode = accountNodeList.item(0);
						Node accountText = accountNode.getFirstChild();
						this.account = accountText.getNodeValue();
					}
					NodeList attrsNodeList = successDocument
							.getElementsByTagName(ATTRIBUTES_KEY);
					if (attrsNodeList.getLength() > 0) {
						Node attrsNode = attrsNodeList.item(0);
						if (attrsNode.hasChildNodes()) {
							Document attrsDoc = attrsNode.getOwnerDocument();
							Field[] fields = getClass().getDeclaredFields();
							for (Field field : fields) {
								String fieldName = field.getName();
								String attrTagName = CAS_PREFIX + fieldName;
								NodeList attrNodeList = attrsDoc
										.getElementsByTagName(attrTagName);
								if (attrNodeList.getLength() > 0) {
									Node attrNode = attrNodeList.item(0);
									Node attrText = attrNode.getFirstChild();
									if(attrText!=null){
										field.set(this, attrText.getNodeValue()
												.trim());
									}
								}
							}
						}
					}
				}
			} catch (Exception e) {
				// 解析用户信息失败！
				e.printStackTrace();
			} finally {
				IOUtils.closeQuietly(in);
			}
		}

		public String getAccount() {
			return account;
		}

		public String getSsoAccount() {
			return ssoAccount;
		}

		public String getDeptName() {
			return deptName;
		}

		public String getIdCard() {
			return idCard;
		}

		public String getStudentNo() {
			return studentNo;
		}

		public String getRemark() {
			return remark;
		}

		public String getLocalAccount() {
			return localAccount;
		}

		public String getTel() {
			return tel;
		}

		public String getDicOrgId() {
			return dicOrgId;
		}

		public String getNick() {
			return nick;
		}

		public String getEmail() {
			return email;
		}

		public String getStaffNo() {
			return staffNo;
		}

		public String getName() {
			return name;
		}

		public String getDeptCode() {
			return deptCode;
		}

		public String getDn() {
			return dn;
		}

		public String getMobile() {
			return mobile;
		}

		public String getTypeCode() {
			return typeCode;
		}

		public void setTypeCode(String typeCode) {
			this.typeCode = typeCode;
		}

		public String getType() {
			return type;
		}

		public void setType(String type) {
			this.type = type;
		}

		public String getTypeName() {
			return typeName;
		}

		public void setTypeName(String typeName) {
			this.typeName = typeName;
		}

		public boolean isLogin() {
			return account != null && account.length() != 0;
		}

	}%>