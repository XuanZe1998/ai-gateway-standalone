<?php
require_once 'Config.php';
// 设置时区
date_default_timezone_set('Asia/Shanghai');
// 设置头信息
header('Content-Type: text/html; charset=utf-8');
// 获取项目IP PORT
define('BASH_PATH', $_SERVER['REQUEST_SCHEME'] . "://" . $_SERVER['SERVER_NAME'] . ':' . $_SERVER['SERVER_PORT'] . '/');

// CAS 初始化
require_once 'supwisdom/CAS.php';
// 初始化CAS客户端
phpCAS::client(CAS_VERSION_2_0, CAS_HOST, CAS_PORT, CAS_CONTEXT);
// 允许非SSL请求认证
phpCAS::setNoCasServerValidation();
// 设置单点登出监听、本来用处应该是统一登出、但是因为集成方式问题、没有具体作用
phpCAS::handleLogoutRequests();

// 如果SESSION没有开启 则开启
if (!isset($_SESSION)) {
    session_start();
}