#!/bin/bash
echo "=== 设备端证书安装状态检查（需要在设备终端执行） ==="
echo "方法：检查 /apex/com.android.conscrypt/cacerts/ 和 /system/etc/security/cacerts/"
echo "如果看到 1680 文件或新增加的 .0 文件，说明安装成功。"
echo "=== SystemCertInstaller 方法确认 ==="
echo "代码已简化为：cp -> /data/local/tmp/ -> mount --bind 到 conscrypt/system"
