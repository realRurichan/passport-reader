# two-way-permit-reader

离线 Android 往来港澳通行证（双程证）芯片读取器。项目处于早期开发阶段，不具有法定身份认证效力。

## 功能

1. **机读码识别**：拍摄证件背面单行机读码（MRZ-B），离线 OCR，识别结果可手动校对修改。
2. **芯片读取**：用机读码三要素做 BAC 鉴权（芯片不支持 PACE），读取标准 LDS 数据组与隐藏记录文件。
3. **签注记录**（隐藏文件 0x0111）：
   - 签注代码与目的地（H=赴港、M=赴澳）
   - 签注类别（第三位字母：D 逗留、G 个人旅游、T 团队旅游、S 商务、Q 探亲、F 其他）
   - 签发日期、有效期、签发地（GB/T 2260 市级 4 位区划码，全国数据）
4. **出入境记录**（隐藏文件 0x0112/0x0114）：
   - 内地侧签注激活记录（次数编码 + 时间）
   - 香港/澳门侧激活记录（入境时间、批准逗留截止日、口岸三字码如 HZM=港珠澳大桥）
5. **技术信息**：数据组清单、SOD 声明、隐藏文件计数、APDU 探针结果。

## 芯片协议备注

- 标准 LDS 文件：DG1（MRZ）、DG2（头像）、DG11、DG12、DG15、COM、SOD。
- 隐藏记录文件 0x0111–0x0115 需 **P1=0x02 + 安全报文** SELECT，READ RECORD 按 6Cxx 提示重发。
- 该芯片 SELECT 必须全程加密（明文命令会破坏 SM 会话），与普通 eMRTD 不同。

## 隐私与安全

见 [PRIVACY.md](PRIVACY.md)：无网络权限、OCR 模型内置、识别结果仅存内存、不自动保存证件数据。

## 构建

需要 JDK 17 和 Android SDK 35。仓库包含 Gradle Wrapper；Windows 下运行：

```
gradlew.bat test assembleDebug
```

## 调试工具

`tools/pc-reader/` 提供电脑端经手机 NFC 中继访问芯片的工具（与 App 内「布防中继」配合）：

- `run.ps1`：一键编译并 dump 双程证芯片全部可读记录（用法：`run.ps1 <证件号> <生日YYMMDD> <有效期YYMMDD>`）
- `TcpCardService.java`：把 localhost:8983 的行式 hex APDU 转发到手机中继
- `Probe.java` / `RecordProbe.java` / `AuthProbe.java` / `PermitDump.java`：协议探针与记录转储

使用：App 内点「布防中继」→ 电脑执行 `adb forward tcp:8983 tcp:8983` → 贴卡 → 运行工具。个人数据 dump 目录已被 gitignore，不会入库。
