<?php require_once 'supwisdom/Init.php'; ?>
<!doctype html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>phpCAS simple client</title>
</head>
<body>
<h1>1 当前登录用户姓名：

    <?php
    if (isset($_SESSION[LOGIN_USER_KEY]) && isset($_SESSION[LOGIN_USER_KEY]['account'])) {
        echo $_SESSION[LOGIN_USER_KEY]['account'];
    }
    ?>

</h1>

<h1>2 当前登录用户认证系统(CAS)帐号：
    <?php
    if (isset($_SESSION[LOGIN_USER_KEY]) && isset($_SESSION[LOGIN_USER_KEY]['ssoAccount'])) {
        echo $_SESSION[LOGIN_USER_KEY]['ssoAccount'];
    }
    ?>
</h1>

<h1>3 当前登录用户业务系统帐号：
    <?php
    if (isset($_SESSION[LOGIN_USER_KEY]) && isset($_SESSION[LOGIN_USER_KEY]['localAccount'])) {
        echo $_SESSION[LOGIN_USER_KEY]['localAccount'];
    }
    ?>
</h1>

<span style='color: red;'>注:同一个用户，在业务系统和认证系统(CAS)中的帐号不一致时会用到；</span>
当前登录用户业务系统帐号

<h1><a href="logout.php" style="color: blue;">注销</a></h1>
</body>
</html>