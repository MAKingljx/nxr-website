# 独立静态发布

本目录只管理 NXR 图片命名工具。服务器仅发送经过审计的静态构建文件；图片读取、二维码和标签文字识别、配对、哈希校验和改名都在浏览器本地完成，没有照片上传接口。

## 边界

- 专属根目录：`/var/www/nxr-photo-renamer`
- 公开地址：`https://nxrgrading.com/tools/photo-renamer/`
- 专属 Nginx snippet：`/etc/nginx/snippets/nxr-photo-renamer.conf`
- 共享 TLS vhost 只增加一行本工具 include，位置紧跟已有 Java snippet include。
- 不修改 `/root/nxr_website`、`Data/`、8080/8081 进程、默认 vhost 或 Java snippet；不执行 Nginx restart。
- 当前构建包含十四个文件：`dist/index.html`、一个 CSS、主 JS、QR Worker、WebP Worker、OCR Worker、六种 OCR core、语言模型 JS 和许可证。源码、依赖目录、测试、source map、隐藏文件、照片、数据库和 `.PhoenixBrain` 都不会上传。

## 使用

先在项目目录完成构建和测试。发布命令默认仅执行本地审计与远端只读基线检查：

```bash
python3 deploy/publish.py
```

预检会核对项目目录所有权、URL 是否为空闲或已由本工具使用、现网主站/Admin HTTP、Python 与 Nginx PID、共享配置哈希和当前版本。证据默认写入已忽略的 `test-results/deploy/`；正式发布使用 `--evidence-dir /Users/phoenix/Documents/Phoenxi/nxr_website/output/nxr-photo-renamer/deploy` 持久保存，避免后续浏览器测试清理结果目录。

审阅预检结果后才执行：

```bash
python3 deploy/publish.py --apply
```

发布器上传到 `releases/<UTC时间>-<构建树哈希>/dist`，逐文件比对本地与远端 SHA-256，原子切换本工具的 `current` 链接。首次安装或本工具 snippet 内容变化时才运行 `nginx -t` 并执行 `systemctl reload nginx`；普通静态更新和版本回滚只切本工具链接，不扰动共享 Nginx。随后分别通过公网 DNS HTTPS 和服务器上的 `curl --resolve nxrgrading.com:443:147.182.183.201` 源站 HTTPS 下载全部构建文件复核 SHA-256，并验证页面、Worker、308、404、POST 403、安全响应头、现网 HTTP、监听 PID 和受保护配置未变化。成功后保留当前版本和最多两个经逐文件复核的本工具历史版本；没有有效本工具 manifest 的目录绝不清理。

## 回滚

先只读检查指定版本：

先运行普通只读预检，从证据的 `baseline.owned_releases` 中选择真实的已验证版本。下面的命令只列出实际保留版本，不生成示例版本号：

```bash
python3 deploy/publish.py
EVIDENCE_FILE="$(ls -t test-results/deploy/*.json | head -n 1)"
python3 -c 'import json,sys; [print(x["releaseId"]) for x in json.load(open(sys.argv[1]))["baseline"]["owned_releases"] if x["verified"]]' "$EVIDENCE_FILE"
read -r RELEASE_ID
python3 deploy/publish.py --rollback "$RELEASE_ID"
```

确认后切换：

```bash
python3 deploy/publish.py --rollback "$RELEASE_ID" --apply
```

回滚版本必须仍在本工具 `releases/` 中，manifest 所有权正确且已验证。发布或回滚的后置检查失败时，脚本按本次操作记录做 CAS：只有 vhost、snippet 和 `current` 仍等于本脚本刚写入的值时才恢复，避免覆盖并发修改。

## 运维说明

首次安装和每次切换前的共享 vhost、snippet 与旧 `current` 记录保存在 `/var/www/nxr-photo-renamer/operations/<操作ID>/`，目录权限为 `0700`。发布目录可由 Nginx 读取，项目元数据、manifest 和运维记录不在公开 `current/` 下。

WebP 编解码 WASM 内联在专属 worker 中。基础版构建为五个文件；包含文字参考识别的新版为十四个文件，增加 OCR worker、六种兼容 core、延迟加载的语言模型 JS 和许可证。发布审计限定这些文件名、完整组成、单文件 8 MiB 和总量 32 MiB；旧版四文件、五文件和新版十四文件均可核验后回滚。仅本工具的 CSP 增加 `script-src 'wasm-unsafe-eval'` 来运行本地编码器，不放宽 JavaScript eval 或图片上传限制。

页面响应统一 `Cache-Control: no-store, no-transform`，避免缓存混用并禁止 CDN 修改 HTML；CSP 的 `connect-src 'none'` 禁止页面向服务器或第三方发起上传/API 连接。File System Access API 仍要求桌面 Chrome/Edge、HTTPS、用户手势和用户对所选目录的明确读写授权。

## 当前已验证发布

- 上线时间：2026-09-07 16:24（北京时间），发布版本 `20260907T082242Z-b429ff7db2b0`。
- 源码已先提交并推送至 [GitHub ce8d48e](https://github.com/MAKingljx/nxr-website/commit/ce8d48e3e5fb1c4d6a8da7ede331a2eae8103cc4)，分支 `java-version/java-platform`。随后从该提交重新构建，产物与本地已验收版本一致。后续发布记录与合成测试编号整理不改变运行源码或构建。
- 当前目录：`/var/www/nxr-photo-renamer/releases/20260907T082242Z-b429ff7db2b0/dist`；保留旧版 `20260907T051820Z-b5dea7760fa1` 和 `20260907T051504Z-2b4e50374bed`。
- 本次只发布独立静态工具，十四个文件共 31,306,104 字节，公网与源站逐文件 SHA-256 校验通过；Nginx 配置字节未变，没有 reload。主站、后台、Nginx master PID 保持不变，主站和后台 HTTP 均为 200。
- 发布证据：仓库根目录 `output/nxr-photo-renamer/deploy/20260907T082436Z-1b3e8eda.json`。线上两张真实背图均正确配对：第一张自动深度补扫恢复，第二张普通扫描成功；后台 OCR 均读到相应标签证号。原始图片 SHA-256 与发布前一致。
- 线上合成样本验证文字不一致时保留二维码，不显示差异、不增加待检查数；只有文字时不自动配对，可在人工录入中带入参考。待检查直达、切图、放大、全角空白整理及前导零保留均通过，390px 窄屏可操作。网络仅有本站 GET 静态资源，无上传、外域请求、页面异常或 CSP 违规。浏览器证据为 `output/nxr-photo-renamer/deploy/browser-20260907T082242Z.json`；本轮只读识别和命名预览，未再次执行 WebP 文件写入。

## 上一版 WebP 发布验收记录

- 上线日期：2026-09-07（北京时间）。
- 当时发布版本：`20260907T051820Z-b5dea7760fa1`（版本时间使用 UTC）。
- 当时服务器目录：`/var/www/nxr-photo-renamer/releases/20260907T051820Z-b5dea7760fa1/dist`；当时保留 `20260907T051504Z-2b4e50374bed` 和 `20260907T051055Z-de4a55ee1dce` 两个已验证历史版本，现存可回滚版本以上方当前记录为准。
- 无损 WebP 更新只在工具 snippet 中增加 WASM 执行许可，经过 `nginx -t` 后平滑 reload 一次。随后仅修正示例扩展名、页脚与确认标题的静态发布没有再次重载。TLS vhost、默认 vhost、Java snippet 均未改变。
- 主站 PID `1750942`、后台 PID `1839302`、Nginx master PID `1750134` 在发布前后相同，主站与后台 HTTP 均为 200；生产数据库和 Python/Java 应用文件未改动。
- 源站与公网五个静态文件逐文件 SHA-256 一致；308、404、POST 403 及安全响应头检查通过。
- 线上 HTTPS 页面用一组真实卡图生成的两张 PNG 副本完成识别、无损 WebP 输出与原图恢复，尺寸均保持 3648×5472，浏览器像素哈希和恢复后的原文件哈希完全一致。此前本地输出也通过 Pillow 格式和逐像素验证，满足后台命名与本批次大小限制。照片没有上传，页面异常、非 GET 请求与外域请求均为零。
- 文件操作使用浏览器本地 OPFS 真实目录句柄替代原生选择器，本轮没有重复系统选择器测试。独立磁盘/OPFS 测试目录已删除，临时浏览器及预览服务已关闭，原始卡图保持不变。
- 功能验收后只调整两处静态 JPG 示例、页脚与确认标题，二维码和 WebP worker 哈希未改变，复用上述功能验收并追加线上示例显示检查。
- 持久证据均位于仓库根目录 `output/nxr-photo-renamer/`：本地验收 `webp-local-acceptance.json`；WebP 上线与平滑重载 `deploy/20260907T051143Z-3f3341a3.json`；线上功能 `deploy/browser-webp-20260907T051055Z.json`；最终发布 `deploy/20260907T051909Z-a0156e7f.json`；最终界面 `deploy/browser-webp-20260907T051820Z.json`。

首轮校验曾发现 Cloudflare Web Analytics 自动注入统计脚本导致公网 HTML 与源文件不同，发布器已完成自动回滚并恢复原配置。最终只在本工具路径加入 `no-transform` 后通过严格校验，没有更改 Cloudflare 全站设置。以后不能通过放宽 HTML 校验来绕过此问题。Cloudflare 对该指令的说明见 <https://developers.cloudflare.com/web-analytics/get-started/>。

## 当前 Mac 的 TUN 直连方式

2026-09-07 核实物理默认路由为 `en1`，Clash TUN 使用 `utun10`。仅取消代理环境变量不能绕过 TUN。本项目可按单个 socket 绑定物理网卡，无需关闭 Clash、改变系统路由或降低 SSH 主机密钥验证。

```bash
route -n get default
python3 deploy/publish.py --direct-interface en1
python3 deploy/publish.py --direct-interface en1 --apply
```

`en1` 是本次核实的网卡名称；在其他机器或网络环境中必须重新确认，不能照搬。连接器只允许已登记的 NXR SSH 地址 `147.182.183.201:22`，不开放本地监听端口。此次部署已用该方式绕过代理节点并验证成功。
