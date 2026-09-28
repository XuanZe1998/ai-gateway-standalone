<?php
require_once 'supwisdom/Init.php';

// doLogin方法需要实现
// 本方法中请勿调用session_commit() 只要给session赋值就可以了
// session_start()和session_commit() 操作已经在此方法调用前后处理
// 方法需要返回登录结果 成功或失败
function doLogin(array $loginUser = array())
{
    // 业务系统的登录逻辑   开始

    // Example
    $_SESSION['loginUser'] = $loginUser;
    // TODO
    return true;

    // 业务系统的登录逻辑   结束
}


// 后面的代码都不用看

if (!isset($_SESSION[LOGIN_KEY]) || !$_SESSION[LOGIN_KEY]) {
    // 判断是否登录、未登录跳转登录页面登录、
    phpCAS::forceAuthentication();
    // 获取登录用户信息
    $loginUser = phpCAS::getAttributes();
    $loginUser['account'] = phpCAS::getUser();
    if (isset($loginUser['account']) && doLogin($loginUser)) {
        $_SESSION[LOGIN_USER_KEY] = $loginUser;
        $_SESSION[LOGIN_KEY] = true;
        session_commit();
        redirectTargetUrl();
    } else {
        header('Location: ' . BASH_PATH . LOGOUT_URI);
    }
} else {
    redirectTargetUrl();
}


function redirectTargetUrl()
{
    // 如果存在参数targetUrl 则登录成功后跳转
    if (isset($_REQUEST[REDIRECT_KEY])) {
        header('Location: ' . $_REQUEST[REDIRECT_KEY]);
    } else {
        header('Location: ' . BASH_PATH . WELCOME_URI);
    }
}

