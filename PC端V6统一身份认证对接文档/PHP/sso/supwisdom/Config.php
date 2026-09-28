<?php
const CAS_HOST = 'app.supwisdom.com';
const CAS_CONTEXT = '/cas';
const CAS_PORT = 7879;

// 写除IP:端口的URI后面的地址
// 例:
// 登录地址全路径:http://localhost:8080/sso/login.php ==> LOGIN_URI:sso/login.php
const LOGIN_URI = 'sso/login.php';
const LOGOUT_URI = 'sso/logout.php';
const WELCOME_URI = 'sso/index.php';


// 获取最终跳转URL的key
const REDIRECT_KEY = 'targetUrl';

// SESSION中判断是否登录的KEY
const LOGIN_KEY = "isSupwisdomCasLogin";
const LOGIN_USER_KEY = "supwisdomCasLoginUser";