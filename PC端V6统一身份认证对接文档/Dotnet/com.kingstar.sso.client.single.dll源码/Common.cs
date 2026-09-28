using System;
using System.Collections.Generic;
using System.Text;
using System.Web.SessionState;
using System.Web;
using System.Security.Cryptography.X509Certificates;
using System.Net.Security;

namespace com.kingstar.sso.client.single
{
    #region Common Code
    public static class Constants
    {
        // CAS根地址
        public static String CAS_BASE_PATH = System.Configuration.ConfigurationManager.AppSettings["CAS_BASE_PATH"];

        // CAS票据验证地址
        public static String CAS_VALIDATE_URL = CAS_BASE_PATH + "serviceValidate";
        // CAS登录地址
        public static String CAS_LOGIN_URL = CAS_BASE_PATH + "login";
        // CAS注销地址
        public static String CAS_LOGOUT_URL = CAS_BASE_PATH + "logout";

        //登录成功默认跳转地址
        public static String DEF_TARGET_URI = System.Configuration.ConfigurationManager.AppSettings["DEF_TARGET_URI"];

        // 业务系统认证集成改造之后的登录URI
        public static String SSO_LOGIN_URI = System.Configuration.ConfigurationManager.AppSettings["SSO_LOGIN_URI"];

        // 业务系统需要显式使用的端口配置，包括80端口，如果不需要配置显式端口，则配置空字符串""即可
        public static String CLIENT_SYSTEM_EXPLICIT_PORT = System.Configuration.ConfigurationManager.AppSettings["CLIENT_SYSTEM_EXPLICIT_PORT"];

        // REQUEST中获取需要跳转URL的KEY
        public static String TARGET_URL_KEY = "targetUrl";

        // SESSION中判断是否登录的KEY
        public static String LOGIN_KEY = "isSupwisdomCasLogin";
        public static String LOGIN_USER_KEY = "supwisdomCasLoginUser";

        // REQUEST中获取票据的KEY
        public static String TICKET_KEY = "ticket";
        // CAS Server验证成功后需跳转客户端Url的Key
        public static String SERVICE_KEY = "service";

        // BASE64编码的前缀
        public static String BASE64_PREFIX = "base64";

        // 默认编码字符串格式
        public static String UTF_8_STR = "UTF-8";
        //默认编码
        public static Encoding UTF_8 = Encoding.UTF8;
    }

    #region LoginUser
    [Serializable]
    public class LoginUser
    {

        public static String CAS_PREFIX = "cas:";
        public static String LOGIN_SUCCESS_KEY = CAS_PREFIX + "authenticationSuccess";
        public static String ACCOUNT_KEY = CAS_PREFIX + "user";
        public static String ATTRIBUTES_KEY = CAS_PREFIX + "attributes";

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

        public LoginUser(String loginUserXmlStr)
        {
            if (StringUtils.isEmpty(loginUserXmlStr))
                return;
            System.Xml.XmlDocument xDoc = new System.Xml.XmlDocument();
            xDoc.LoadXml(loginUserXmlStr);
            if (xDoc.DocumentElement.LocalName != "serviceResponse") { return; }

            if (xDoc.DocumentElement.GetElementsByTagName("cas:authenticationSuccess").Count != 0)
            {
                System.Xml.XmlNode xNode_success = xDoc.DocumentElement.GetElementsByTagName("cas:authenticationSuccess").Item(0);
                if (((System.Xml.XmlElement)xNode_success).GetElementsByTagName("cas:user").Count == 0) { return; }

                System.Xml.XmlNode xNode_user = ((System.Xml.XmlElement)xNode_success).GetElementsByTagName("cas:user").Item(0);

                String principal = xNode_user.InnerText;
                this.account = principal;

                System.Collections.Generic.Dictionary<String, String> attributes = new System.Collections.Generic.Dictionary<String, String>();
                if (((System.Xml.XmlElement)xNode_success).GetElementsByTagName("cas:attributes").Count != 0)
                {
                    System.Xml.XmlNode xNode_attr = ((System.Xml.XmlElement)xNode_success).GetElementsByTagName("cas:attributes").Item(0);

                    if (xNode_attr.HasChildNodes)
                    {
                        foreach (System.Xml.XmlNode xNode_attr_child in xNode_attr.ChildNodes)
                        {
                            String key = xNode_attr_child.LocalName;
                            String value = xNode_attr_child.InnerText.Trim();
                            attributes.Add(key, value);
                        }
                    }
                }

                this.ssoAccount = attributes["ssoAccount"];
                this.deptName = attributes["deptName"];
                this.studentNo = attributes["studentNo"];
                this.idCard = attributes["idCard"];
                this.remark = attributes["remark"];
                this.localAccount = attributes["localAccount"];
                this.tel = attributes["tel"];
                this.dicOrgId = attributes["dicOrgId"];
                this.nick = attributes["nick"];
                this.staffNo = attributes["staffNo"];
                this.email = attributes["email"];
                this.name = attributes["name"];
                this.dn = attributes["dn"];
                this.deptCode = attributes["deptCode"];
                this.mobile = attributes["mobile"];
            }
        }

        public String setAccount()
        {
            return account;
        }

        public String setSsoAccount()
        {
            return ssoAccount;
        }

        public String setDeptName()
        {
            return deptName;
        }

        public String setIdCard()
        {
            return idCard;
        }

        public String setStudentNo()
        {
            return studentNo;
        }

        public String setRemark()
        {
            return remark;
        }

        public String setLocalAccount()
        {
            return localAccount;
        }

        public String setTel()
        {
            return tel;
        }

        public String setDicOrgId()
        {
            return dicOrgId;
        }

        public String setNick()
        {
            return nick;
        }

        public String setEmail()
        {
            return email;
        }

        public String setStaffNo()
        {
            return staffNo;
        }

        public String setName()
        {
            return name;
        }

        public String setDeptCode()
        {
            return deptCode;
        }

        public String setDn()
        {
            return dn;
        }

        public String setMobile()
        {
            return mobile;
        }

        public String getAccount()
        {
            return account;
        }

        public String getSsoAccount()
        {
            return ssoAccount;
        }

        public String getDeptName()
        {
            return deptName;
        }

        public String getIdCard()
        {
            return idCard;
        }

        public String getStudentNo()
        {
            return studentNo;
        }

        public String getRemark()
        {
            return remark;
        }

        public String getLocalAccount()
        {
            return localAccount;
        }

        public String getTel()
        {
            return tel;
        }

        public String getDicOrgId()
        {
            return dicOrgId;
        }

        public String getNick()
        {
            return nick;
        }

        public String getEmail()
        {
            return email;
        }

        public String getStaffNo()
        {
            return staffNo;
        }

        public String getName()
        {
            return name;
        }

        public String getDeptCode()
        {
            return deptCode;
        }

        public String getDn()
        {
            return dn;
        }

        public String getMobile()
        {
            return mobile;
        }

        public bool isLogin()
        {
            return !String.IsNullOrEmpty(account);
        }

    }
    #endregion

    public abstract class StringUtils
    {

        public static Boolean isEmpty(String str)
        {
            return String.IsNullOrEmpty(str);
        }

        public static byte[] getBytesUtf8(String str)
        {
            return getBytes(str, Constants.UTF_8);
        }

        public static byte[] getBytes(String str, Encoding encoding)
        {
            if (str == null)
            {
                return null;
            }
            return encoding.GetBytes(str);
        }

        public static String newStringUtf8(byte[] bytes)
        {
            return newString(bytes, Constants.UTF_8);
        }

        public static String newString(byte[] bytes, Encoding encoding)
        {
            return bytes == null ? null : encoding.GetString(bytes);
        }
    }

    public abstract class BooleanUtils
    {

        public static bool toBoolean(String str)
        {
            return str == Boolean.TrueString;
        }

    }

    public abstract class Base64Utils
    {
        public static String encodeBase64Str(String serviceURI)
        {
            if (StringUtils.isEmpty(serviceURI)) return "";

            byte[] result = System.Text.Encoding.Default.GetBytes(serviceURI);
            String encodedServiceURI = Convert.ToBase64String(result);
            return encodedServiceURI;
        }

        public static String decodeBase64Str(String encodedServiceURI)
        {
            if (StringUtils.isEmpty(encodedServiceURI)) return "";

            byte[] result = Convert.FromBase64String(encodedServiceURI);
            String serviceURI = System.Text.Encoding.Default.GetString(result);
            return serviceURI;
        }
    }


    public abstract class HttpRequestUtils
    {
        public static bool CheckValidationResult(object sender, X509Certificate certificate, X509Chain chain, SslPolicyErrors errors)
        {   // 总是接受    
            return true;
        }

        public static string doGet(string url, bool requireHttp200, int timeout)
        {
            string responseBody = null;

            try
            {
                System.Net.ServicePointManager.ServerCertificateValidationCallback = new System.Net.Security.RemoteCertificateValidationCallback(CheckValidationResult);

                System.Net.HttpWebRequest request = (System.Net.HttpWebRequest)System.Net.WebRequest.Create(url);
                if (timeout > 0)
                    request.Timeout = timeout;

                using (System.Net.HttpWebResponse response = (System.Net.HttpWebResponse)request.GetResponse())
                {
                    if (!requireHttp200 || response.StatusCode == System.Net.HttpStatusCode.OK)
                    {
                        using (System.IO.Stream responseStream = response.GetResponseStream())
                        {
                            if (responseStream != null)
                            {
                                using (System.IO.StreamReader responseReader = new System.IO.StreamReader(responseStream))
                                {
                                    responseBody = responseReader.ReadToEnd();
                                }
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
               // HttpContext.Current.Response.Write("Message = " + ex.Message);
               // HttpContext.Current.Response.Write("StackTrace = " + ex.StackTrace);
               // HttpContext.Current.Response.End();
               
            }

            return responseBody;
        }
    }

    public abstract class CasUtils
    {

        /** 判断是否已经登录过 */
        public static bool isLogin(HttpSessionState session)
        {
            Object isLogin = session[Constants.LOGIN_KEY];
            if (isLogin == null)
            {
                return false;
            }
            return BooleanUtils.toBoolean(isLogin.ToString());
        }

        /** 获取TargetUrl */
        public static String getTargetUrl(HttpRequest request)
        {
            String basePath = getBasePath(request);

            // 获取请求中的targetUrl
            String targetUrl = request.Params[Constants.TARGET_URL_KEY];

            if (StringUtils.isEmpty(targetUrl))
            {
                // 若不存在，则使用默认页面作为targetUrl
                targetUrl = basePath + Constants.DEF_TARGET_URI;
            }
            else
            {
                // 判断target是否编码
                if (targetUrl.StartsWith(Constants.BASE64_PREFIX))
                {
                    targetUrl = targetUrl.Substring(Constants.BASE64_PREFIX.Length);
                    targetUrl = Base64Utils.decodeBase64Str(targetUrl);
                }
            }
            return targetUrl;
        }

        public static String getBasePath(HttpRequest request)
        {
            // 获取本次请求的根Path
            String scheme = request.Url.Scheme;
            String serverName = request.Url.Host;
            int serverPort = request.Url.Port;
            String contextPath = request.ApplicationPath;
            if (contextPath != null && contextPath.Equals("/"))
            {
                contextPath = "";
            }

            // 判断是否配置了显式端口
            bool explicit_port = Constants.CLIENT_SYSTEM_EXPLICIT_PORT != null
                    && !"".Equals(Constants.CLIENT_SYSTEM_EXPLICIT_PORT);

            String url = "";

            if (explicit_port)
            {
                serverPort = int.Parse(Constants.CLIENT_SYSTEM_EXPLICIT_PORT);
                url = scheme + "://" + serverName + ":" + serverPort
                        + contextPath + "/";
                // return url;

            }
            else
            {
                if ((serverPort == 80) || (serverPort == 443))
                {

                    url = scheme + "://" + serverName + contextPath
                            + "/";
                    // return url;
                }
                else
                {
                    url = scheme + "://" + serverName + ":" + serverPort
                            + contextPath + "/";
                    // return url;
                }
            }

            return url;

            // return scheme + "://" + serverName + (serverPort.Equals("80") ? "" : ":" + serverPort) + contextPath + "/";
        }

        /** 判断票据是否存在 */
        public static bool hasTicket(HttpRequest request)
        {
            Object ticket = request[Constants.TICKET_KEY];
            return ticket != null && !StringUtils.isEmpty(ticket.ToString());
        }

        public static LoginUser getLoginUser(HttpRequest request)
        {
            String serviceValidateUrl = getServiceValidateUrl(request);
            String casUserInfoXml = HttpRequestUtils.doGet(serviceValidateUrl, true, 0);
            //HttpContext.Current.Response.Write("serviceValidateUrl = " + serviceValidateUrl);
            //HttpContext.Current.Response.Write("        ");
            //HttpContext.Current.Response.Write("casUserInfoXml = " + casUserInfoXml);
            //HttpContext.Current.Response.End();
            return new LoginUser(casUserInfoXml);
        }

        /** 获取校验票据Url */
        public static String getServiceValidateUrl(HttpRequest request)
        {

            String encodeServiceUrl = getURLEncodeServiceUrl(request);
            Object ticket = HttpContext.Current.Request[Constants.TICKET_KEY];

            return Constants.CAS_VALIDATE_URL
                    + "?" + Constants.TICKET_KEY + "=" + ticket.ToString()
                    + "&" + Constants.SERVICE_KEY + "=" + encodeServiceUrl;
        }

        public static String getURLEncodeServiceUrl(HttpRequest request)
        {

            // 编码成系统可识别的加密串
            String targetUrl = getTargetUrl(request);
            String base64TargetUrl = Base64Utils.encodeBase64Str(targetUrl);

            String serviceUrlRoot = getBasePath(request) + Constants.SSO_LOGIN_URI;

            String serviceUrl = serviceUrlRoot + "?" + Constants.TARGET_URL_KEY + "=" + Constants.BASE64_PREFIX + base64TargetUrl;
            return System.Web.HttpUtility.UrlEncode(serviceUrl, Constants.UTF_8);
        }

        /** 获取Cas登录Url 登录成功后返回票据 */
        public static String getLoginUrl(HttpRequest request)
        {
            String encodeServiceUrl = getURLEncodeServiceUrl(request);

            return Constants.CAS_LOGIN_URL + "?" + Constants.SERVICE_KEY + "=" + encodeServiceUrl;
        }

        /** 获取登出地址 */
        public static String getLogoutUrl(HttpRequest request)
        {
            // 获取本次请求的根Path
            String loginUrlRoot = getBasePath(request) + Constants.SSO_LOGIN_URI;
            String encodeLoginUrlRoot = System.Web.HttpUtility.UrlEncode(loginUrlRoot, Constants.UTF_8);

            return Constants.CAS_LOGOUT_URL + "?" + Constants.SERVICE_KEY + "=" + encodeLoginUrlRoot;
        }

        /** 写入单页面登录判断标志 */
        public static void login(LoginUser loginUser, HttpSessionState session)
        {
            session.Add(Constants.LOGIN_KEY, true);
            session.Add(Constants.LOGIN_USER_KEY, loginUser);
        }

        /** 移出单页面登录判断标志 */
        public static void logout(HttpSessionState session)
        {
            session.Remove(Constants.LOGIN_KEY);
            session.Remove(Constants.LOGIN_USER_KEY);
        }
    }
    #endregion Common Code

}
